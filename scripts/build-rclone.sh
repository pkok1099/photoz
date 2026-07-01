#!/usr/bin/env bash
# =============================================================================
# Build rclone for Android with CGO enabled (DNS fix)
# =============================================================================
#
# WHY THIS EXISTS (read this before modifying):
#
# rclone's official GitHub release artifacts (v1.68.2 and earlier) are Linux
# builds with CGO_ENABLED=0 — statically linked, using Go's pure-Go DNS
# resolver. On Android, the pure-Go resolver does NOT work because:
#
#   1. Android does not have a conventional /etc/resolv.conf that reflects
#      real DNS servers (app processes don't have access to one).
#   2. Go's pure-Go resolver, finding no resolv.conf, falls back to a
#      hardcoded defaultNS = ["127.0.0.1:53", "[::1]:53"].
#   3. Nothing listens on [::1]:53 on an Android device, so every DNS query
#      fails with "connection refused".
#
# Symptom: "dial tcp: lookup app.koofr.net on [::1]:53: read udp
# [::1]:43090->[::1]:53: read: connection refused"
#
# This is Go issue #10714 (https://github.com/golang/go/issues/10714), a
# long-standing known Go-on-Android gotcha.
#
# FIX: build rclone from source with CGO_ENABLED=1 + the Android NDK clang
# toolchain. This dynamically links the binary to Android's Bionic libc,
# which makes Go use the cgo resolver → Bionic's getaddrinfo() → Android's
# netd → real DNS (handles VPNs, Private DNS, per-network DNS correctly).
#
# This is exactly how rclone's own official Android CI builds (added in
# v1.69.0+), RCX (Rclone for Android), and Syncthing-for-Android all do it.
#
# Sources:
#   - https://github.com/golang/go/issues/10714
#   - https://dave.engineer/blog/2025/11/cross-compiling-go-android
#   - https://github.com/rclone/rclone/blob/master/.github/workflows/build.yml
#   - https://github.com/x0b/rcx/blob/master/rclone/build.gradle
#
# PREREQUISITES:
#   - Go 1.22+ installed and on PATH (or set GO_BIN=/path/to/go)
#   - Android NDK installed (or set ANDROID_NDK_HOME=/path/to/ndk)
#     - Tested with NDK r27c (27.2.12479018). r22+ should work.
#     - The script needs $NDK/toolchains/llvm/prebuilt/<host>/bin/
#       containing aarch64-linux-android21-clang
#
# USAGE:
#   bash scripts/build-rclone.sh
#
#   # Override defaults via env vars:
#   RCLONE_VERSION=v1.68.2 ANDROID_NDK_HOME=/path/to/ndk GO_BIN=/path/to/go \
#     bash scripts/build-rclone.sh
#
set -euo pipefail

: "${RCLONE_VERSION:=v1.68.2}"
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_HOME:-}}"
: "${GO_BIN:=go}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
NDK_API_LEVEL="${RCLONE_NDK_API_LEVEL:-21}"  # API 21 = modern NDK minimum, what rclone/rcx/syncthing all use

# Only arm64-v8a is built — the project ships arm64-v8a only per the prior
# session's decision. Add armeabi-v7a here if needed (would require
# armv7a-linux-androideabi${NDK_API_LEVEL}-clang + GOARCH=arm + GOARM=7).
declare -A ABI_TO_GOARCH=(
    ["arm64-v8a"]="arm64"
)

# ─── Validate prerequisites ─────────────────────────────────────────────
if [[ -z "$ANDROID_NDK_HOME" ]]; then
    echo "ERROR: ANDROID_NDK_HOME is not set."
    echo "Install the NDK via sdkmanager 'ndk;27.2.12479018' or set ANDROID_NDK_HOME."
    exit 1
fi
if ! command -v "$GO_BIN" >/dev/null 2>&1; then
    echo "ERROR: Go not found on PATH (or GO_BIN=$GO_BIN not executable)."
    echo "Install Go 1.22+ from https://go.dev/dl/ or set GO_BIN=/path/to/go."
    exit 1
fi

NDK_HOST="linux-x86_64"
if [[ "$(uname -s)" == "Darwin" ]]; then
    NDK_HOST="darwin-x86_64"
fi
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST"
if [[ ! -d "$NDK_TOOLCHAIN" ]]; then
    echo "ERROR: NDK toolchain dir not found: $NDK_TOOLCHAIN"
    exit 1
fi

echo "================================================================"
echo "Building rclone $RCLONE_VERSION for Android (CGO enabled)"
echo "================================================================"
echo "  NDK:              $ANDROID_NDK_HOME"
echo "  NDK API level:    $NDK_API_LEVEL"
echo "  NDK toolchain:    $NDK_TOOLCHAIN"
echo "  Go:               $($GO_BIN version)"
echo "  Target ABIs:      ${!ABI_TO_GOARCH[@]}"
echo "  rclone version:   $RCLONE_VERSION (will set fs.Version=$RCLONE_VERSION-android-cgo)"
echo ""

# ─── Clone rclone source (if not already present) ───────────────────────
RCLONE_SRC_DIR="${RCLONE_SRC_DIR:-$REPO_ROOT/.rclone-src}"
if [[ ! -d "$RCLONE_SRC_DIR/.git" ]]; then
    echo "--- Cloning rclone $RCLONE_VERSION source → $RCLONE_SRC_DIR"
    git clone --depth=1 --branch "$RCLONE_VERSION" \
        https://github.com/rclone/rclone.git "$RCLONE_SRC_DIR"
else
    echo "--- Reusing existing rclone source at $RCLONE_SRC_DIR"
    (cd "$RCLONE_SRC_DIR" && git fetch --depth=1 origin "$RCLONE_VERSION" && git checkout "$RCLONE_VERSION")
fi

# ─── Build for each ABI ──────────────────────────────────────────────────
for abi in "${!ABI_TO_GOARCH[@]}"; do
    goarch="${ABI_TO_GOARCH[$abi]}"
    echo ""
    echo "=== Building $abi (GOARCH=$goarch) ==="

    if [[ "$goarch" == "arm64" ]]; then
        cc_name="aarch64-linux-android${NDK_API_LEVEL}-clang"
    elif [[ "$goarch" == "arm" ]]; then
        cc_name="armv7a-linux-androideabi${NDK_API_LEVEL}-clang"
    else
        echo "ERROR: don't know which NDK clang to use for GOARCH=$goarch"
        exit 1
    fi

    CC="$NDK_TOOLCHAIN/bin/$cc_name"
    if [[ ! -x "$CC" ]]; then
        echo "ERROR: NDK clang not found at $CC"
        exit 1
    fi

    target_dir="$JNILIBS_DIR/$abi"
    target_file="$target_dir/librclone.so"
    mkdir -p "$target_dir"

    # Clean any prior static binary so we don't accidentally ship the old one
    # if the build fails.
    rm -f "$target_file"

    (
        cd "$RCLONE_SRC_DIR"
        export CC="$CC"
        export CC_FOR_TARGET="$CC"
        export GOOS=android
        export GOARCH="$goarch"
        [[ "$goarch" == "arm" ]] && export GOARM=7
        export CGO_ENABLED=1
        export CGO_LDFLAGS="-fuse-ld=lld -s -w"
        # Use a project-local GOCACHE/GOPATH so we don't pollute the user's
        # default and so the build is reproducible across machines.
        export GOCACHE="${GOCACHE:-$REPO_ROOT/.gocache}"
        export GOPATH="${GOPATH:-$REPO_ROOT/.gopath}"

        echo "  CC=$CC"
        echo "  GOOS=$GOOS GOARCH=$GOARCH CGO_ENABLED=$CGO_ENABLED"

        # -tags android: disables rclone's systemd socket-activation path
        #   (wrong on Android) and includes Android in disk-usage statfs.
        # -trimpath: removes absolute paths from the binary for reproducibility.
        # -ldflags -s: strips debug symbols (smaller binary).
        # -ldflags -X fs.Version: sets the version string rclone reports.
        "$GO_BIN" build -v -tags android -trimpath \
            -ldflags "-s -X github.com/rclone/rclone/fs.Version=$RCLONE_VERSION-android-cgo" \
            -o "$target_file" .
    )

    chmod 0755 "$target_file"

    # ─── Verify the build actually produced a cgo/dynamically-linked binary ─
    # If this check fails, the binary is still static (CGO_ENABLED=0 leaked
    # in somehow) and DNS will be broken on Android. Catch it now.
    echo ""
    echo "  Verifying binary is dynamically linked (CGO actually linked in):"
    if ! file "$target_file" | grep -q "dynamically linked"; then
        echo "ERROR: $target_file is NOT dynamically linked — cgo did not link."
        echo "File says: $(file "$target_file")"
        echo "DNS will be broken on Android. Aborting."
        exit 1
    fi
    if ! file "$target_file" | grep -q "for Android"; then
        echo "ERROR: $target_file is not built for Android."
        echo "File says: $(file "$target_file")"
        exit 1
    fi
    echo "  ✓ $(file "$target_file" | sed 's/,/ /g')"

    echo "  Wrote $target_file ($(stat -c%s "$target_file") bytes)"
done

echo ""
echo "================================================================"
echo "Done. Rebuild the APK to package the new binaries."
echo "================================================================"
echo ""
echo "Quick verification commands:"
echo "  file app/src/main/jniLibs/arm64-v8a/librclone.so"
echo "  # Should show: 'dynamically linked, interpreter /system/bin/linker64, for Android 21'"
echo ""
echo "  llvm-readelf -d app/src/main/jniLibs/arm64-v8a/librclone.so  # from NDK"
echo "  # Should show NEEDED entries: libdl.so, liblog.so, libc.so (Bionic)"
