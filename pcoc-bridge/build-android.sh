#!/bin/sh
# Android cdylibs for arm64-v8a and x86_64. Release, so a leg is usable.
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/26.3.11579264}}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
OUT="${1:-$ROOT/../app/build/pcoc-jni}"
case "$OUT" in
    /*) ;;
    *) OUT="$(pwd)/$OUT" ;;
esac
if [ ! -x "$TOOLCHAIN/clang" ] && [ ! -x "$TOOLCHAIN/llvm-ar" ]; then
    echo "Android NDK toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi
rustup target add aarch64-linux-android x86_64-linux-android

build_one() {
    target="$1"
    abi="$2"
    clang="$3"
    key=$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')
    # sha2 `asm` builds sha2-asm through the cc crate; give it the NDK clang/ar
    # under both env spellings cc accepts.
    key_lc=$(printf '%s' "$target" | tr '-' '_')
    mkdir -p "$OUT/$abi"
    env \
        "CARGO_TARGET_${key}_LINKER=$TOOLCHAIN/$clang" \
        "CC_$target=$TOOLCHAIN/$clang" \
        "CC_$key_lc=$TOOLCHAIN/$clang" \
        "AR_$target=$TOOLCHAIN/llvm-ar" \
        "AR_$key_lc=$TOOLCHAIN/llvm-ar" \
        "RANLIB_$target=$TOOLCHAIN/llvm-ranlib" \
        RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --release -p pcoc-bridge --target "$target"
    cp -f "target/$target/release/libpcoc_bridge.so" "$OUT/$abi/libpcoc_bridge.so"
    if [ -x "$TOOLCHAIN/llvm-strip" ]; then
        "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT/$abi/libpcoc_bridge.so"
    fi
}

build_one aarch64-linux-android arm64-v8a "aarch64-linux-android${API}-clang"
build_one x86_64-linux-android x86_64 "x86_64-linux-android${API}-clang"
