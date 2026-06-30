#!/usr/bin/env bash
set -euo pipefail
: "${RCLONE_VERSION:=v1.68.2}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
declare -A ABI_TO_RCLONE_ARCH=(
    ["arm64-v8a"]="arm64"
    ["armeabi-v7a"]="arm"
)
echo "Building rclone $RCLONE_VERSION for ABIs: ${!ABI_TO_RCLONE_ARCH[@]}"
for abi in "${!ABI_TO_RCLONE_ARCH[@]}"; do
    arch="${ABI_TO_RCLONE_ARCH[$abi]}"
    target_dir="$JNILIBS_DIR/$abi"
    target_file="$target_dir/librclone.so"
    mkdir -p "$target_dir"
    url="https://github.com/rclone/rclone/releases/download/$RCLONE_VERSION/rclone-$RCLONE_VERSION-linux-$arch.zip"
    tmp_zip="$(mktemp --suffix=.zip)"
    echo "  [$abi] downloading $url"
    curl --fail --location --silent --show-error -o "$tmp_zip" "$url"
    extract_dir="$(mktemp -d)"
    unzip -q -o "$tmp_zip" -d "$extract_dir"
    src_binary="$(find "$extract_dir" -type f -name rclone | head -1)"
    cp "$src_binary" "$target_file"
    chmod 0755 "$target_file"
    rm -f "$tmp_zip"
    rm -rf "$extract_dir"
    echo "  [$abi] wrote $target_file"
done
echo "Done. Rebuild the APK to package the binaries."
