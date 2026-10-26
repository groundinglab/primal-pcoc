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
cargo fetch --manifest-path "$ROOT/Cargo.toml"

# The pcoc Rust SDK (sdk-rust-v2.3.1) probes SHA-NI with x86 feature names. cpufeatures rejects
# those names on aarch64. Copy the locked crate and treat an unknown name as
# absent, so the probe is false and hashing stays in the sha2 crate.
# The SDK tag is unchanged.
patch_cpufeatures() {
    src=$(find "$HOME/.cargo/registry/src" -type d -name 'cpufeatures-0.2.17' | head -n 1)
    if [ -z "$src" ]; then
        echo "cpufeatures 0.2.17 is not in the cargo registry" >&2
        exit 1
    fi
    dest="$ROOT/target/cpufeatures-android"
    mkdir -p "$ROOT/target"
    rm -rf "$dest"
    cp -a "$src" "$dest"
    python3 - "$dest/src/aarch64.rs" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
old = """                ($hwcaps:expr, $name) => {
                    (($hwcaps & $crate::aarch64::hwcaps::$hwcap) != 0)
                };
            )*
        }"""
new = """                ($hwcaps:expr, $name) => {
                    (($hwcaps & $crate::aarch64::hwcaps::$hwcap) != 0)
                };
            )*
            ($hwcaps:expr, $unknown:tt) => {
                false
            };
        }"""
if old not in text:
    raise SystemExit("cpufeatures aarch64 probe macro was not the expected text")
path.write_text(text.replace(old, new, 1))
PY
    printf '%s' "$dest"
}

CPUFEATURES_ANDROID=$(patch_cpufeatures)

# The aarch64 cpufeatures patch rewrites Cargo.lock. Put the host lock back.
LOCK_COPY="$ROOT/target/Cargo.lock.host"
cp "$ROOT/Cargo.lock" "$LOCK_COPY"
restore_lock() { cp "$LOCK_COPY" "$ROOT/Cargo.lock"; }
trap restore_lock EXIT

build_one() {
    target="$1"
    abi="$2"
    clang="$3"
    key=$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')
    mkdir -p "$OUT/$abi"
    env \
        "CARGO_TARGET_${key}_LINKER=$TOOLCHAIN/$clang" \
        "CC_$target=$TOOLCHAIN/$clang" \
        "AR_$target=$TOOLCHAIN/llvm-ar" \
        "RANLIB_$target=$TOOLCHAIN/llvm-ranlib" \
        RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
        CARGO_BUILD_JOBS=4 \
        cargo build --release -j4 -p pcoc-bridge --target "$target" \
        --config "patch.crates-io.cpufeatures.path=\"$CPUFEATURES_ANDROID\""
    cp -f "target/$target/release/libpcoc_bridge.so" "$OUT/$abi/libpcoc_bridge.so"
    if [ -x "$TOOLCHAIN/llvm-strip" ]; then
        "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT/$abi/libpcoc_bridge.so"
    fi
}

build_one aarch64-linux-android arm64-v8a "aarch64-linux-android${API}-clang"
build_one x86_64-linux-android x86_64 "x86_64-linux-android${API}-clang"
