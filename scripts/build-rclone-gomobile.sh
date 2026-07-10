#!/usr/bin/env bash
# =============================================================================
# Build rclone as a gomobile shared library (AAR) for Android
# =============================================================================
#
# This script builds rclone as a gomobile-bindable shared library (.aar)
# instead of an executable. The resulting AAR contains:
#   - jni/arm64-v8a/librclone.so (shared library, loaded via dlopen)
#   - jni/armeabi-v7a/librclone.so
#   - classes.jar (Java/Kotlin bindings for RcloneRPC)
#
# Why gomobile instead of executable?
#   Android 16 W^X policy blocks exec() from nativeLibraryDir.
#   gomobile produces a shared library loaded via System.loadLibrary()
#   (dlopen), which is NOT blocked by W^X.
#   This also allows useLegacyPackaging=false (needed for SQLCipher).
#
# PREREQUISITES:
#   - Go 1.22+ with gomobile installed
#   - Android NDK installed
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RCLONE_VERSION="${RCLONE_VERSION:-v1.68.2}"
GO_BIN="${GO_BIN:-go}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$ANDROID_SDK_ROOT}/ndk/27.2.12479018}"
NDK_API_LEVEL="${NDK_API_LEVEL:-21}"

RCLONE_SRC_DIR="${RCLONE_SRC_DIR:-$REPO_ROOT/.rclone-src}"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/app/libs}"

echo "================================================================"
echo "Building rclone $RCLONE_VERSION as gomobile AAR for Android"
echo "================================================================"
echo "  Go:               $($GO_BIN version)"
echo "  NDK:              $ANDROID_NDK_HOME"
echo "  rclone version:   $RCLONE_VERSION"
echo ""

# ─── Install gomobile if not present ────────────────────────────────────
if ! $GO_BIN list -m golang.org/x/mobile 2>/dev/null; then
    echo "--- Installing gomobile..."
    $GO_BIN install golang.org/x/mobile/cmd/gomobile@latest
fi

# Ensure gomobile is on PATH
export PATH="$PATH:$(go env GOPATH)/bin"

# ─── Clone rclone source ────────────────────────────────────────────────
if [[ ! -d "$RCLONE_SRC_DIR/.git" ]]; then
    echo "--- Cloning rclone $RCLONE_VERSION → $RCLONE_SRC_DIR"
    git clone --depth=1 --branch "$RCLONE_VERSION" \
        https://github.com/rclone/rclone.git "$RCLONE_SRC_DIR"
else
    echo "--- Reusing existing rclone source at $RCLONE_SRC_DIR"
    (cd "$RCLONE_SRC_DIR" && git fetch --depth=1 origin "$RCLONE_VERSION" && git checkout "$RCLONE_VERSION")
fi

# ─── Set up NDK environment ─────────────────────────────────────────────
export ANDROID_NDK_HOME

# ─── 16KB page alignment (Android 15+ requirement) ─────────────────────
# Go 1.23+ supports 16KB page alignment via CGO_LDFLAGS.
# Without this, .so files have 4KB alignment → dlopen crash on Android 16.
export CGO_ENABLED=1
export CGO_CFLAGS="-fPIC"
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"

gomobile init 2>/dev/null || true

# ─── Build gomobile AAR ─────────────────────────────────────────────────
echo ""
echo "=== Building gomobile AAR (16KB aligned) ==="
mkdir -p "$OUTPUT_DIR"

cd "$RCLONE_SRC_DIR"

# Build for arm64 + arm32 (arm64 is primary; arm32 for older devices)
# CGO_ENABLED=1: required for Android DNS resolution (pure-Go resolver fails)
# CGO_LDFLAGS: 16KB page alignment (Android 15+ requirement)
gomobile bind \
    -v \
    -target=android/arm64,android/arm \
    -androidapi=$NDK_API_LEVEL \
    -ldflags="-s -w -X github.com/rclone/rclone/fs.Version=$RCLONE_VERSION-android-gomobile" \
    -tags="android,noweb,nomount,nserve" \
    -trimpath \
    github.com/rclone/rclone/librclone/gomobile

# gomobile produces .aar named after the last package segment
# For github.com/rclone/rclone/librclone/gomobile → gomobile.aar
AAR_SRC=""
for name in gomobile.aar rclone.aar librclone.aar; do
    if [[ -f "$name" ]]; then
        AAR_SRC="$name"
        break
    fi
done

if [[ -n "$AAR_SRC" ]]; then
    cp "$AAR_SRC" "$OUTPUT_DIR/librclone.aar"
    echo ""
    echo "=== AAR built successfully (source: $AAR_SRC) ==="
    ls -lh "$OUTPUT_DIR/librclone.aar"
    echo ""

    # Verify .so files — gomobile names them libgojni.so
    echo "=== Contents ==="
    unzip -l "$OUTPUT_DIR/librclone.aar" | grep "\.so"

    # Check 16KB alignment
    echo ""
    echo "=== 16KB alignment check (arm64-v8a) ==="
    TMPDIR=$(mktemp -d)
    unzip -q "$OUTPUT_DIR/librclone.aar" "jni/arm64-v8a/libgojni.so" -d "$TMPDIR"
    if [[ -f "$TMPDIR/jni/arm64-v8a/libgojni.so" ]]; then
        ALIGN=$(readelf -lW "$TMPDIR/jni/arm64-v8a/libgojni.so" 2>/dev/null | grep "LOAD" | head -1 | awk '{print $NF}')
        echo "  arm64-v8a alignment: $ALIGN"
        if [[ "$ALIGN" == "0x4000" ]]; then
            echo "  ✅ 16KB aligned"
        else
            echo "  ⚠️ Not 16KB aligned ($ALIGN) — may crash on Android 16"
        fi
    fi
    rm -rf "$TMPDIR"
else
    echo "ERROR: rclone.aar not found after gomobile bind"
    exit 1
fi

echo ""
echo "=== Done ==="
echo "AAR location: $OUTPUT_DIR/librclone.aar"
echo "Add to app/build.gradle.kts:"
echo "  implementation(files(\"libs/librclone.aar\"))"
