#!/bin/bash
# inside.sh <out.apk> <keystore> [package]: the Android app, built in the
# container: /src the repository, read only, /work the scratch space kept
# between builds, and /toolchain, when mounted, the on-device compiler packed
# by pack_toolchain.sh.
#
# With /toolchain the APK is the whole app, the one a release ships: the
# engine, the 3dsrecomp built from this checkout (as lib3dsrecomp.so, the file
# BuildService runs), the compiler beside it, and toolchain.zip and the system
# font in its assets. Without it the app plays games built elsewhere, but
# "Build it on this device" has nothing to run.
set -euo pipefail
out="$1" keystore="$2" package="${3:-io.github.almondnative}"
cd /src
cargo build --release --locked --target aarch64-linux-android -p almond-native-android
"$NDK_BIN/llvm-strip" -o /work/libalmondnative.so "$CARGO_TARGET_DIR/aarch64-linux-android/release/libalmondnative.so"

jni=()
if [ -d /toolchain/jni/arm64-v8a ]; then
    # the recompiler is always this checkout's: BuildService calls it by
    # subcommand, and one packed from an older tree lacks the newer ones
    cargo build --release --locked --target aarch64-linux-android -p recomp3ds --bin 3dsrecomp
    rm -rf /work/jni && mkdir -p /work/jni
    cp /toolchain/jni/arm64-v8a/* /work/jni/
    "$NDK_BIN/llvm-strip" -o /work/jni/lib3dsrecomp.so "$CARGO_TARGET_DIR/aarch64-linux-android/release/3dsrecomp"
    bash scripts/android/stage_assets.sh /work/assets /toolchain
    jni=(/work/jni)
else
    bash scripts/android/stage_assets.sh /work/assets
fi

label="Almond Native"
[ "$package" = io.github.almondnative ] || label="Almond Native (test)"
bash scripts/android/make_apk.sh "$package" "$label" /work/libalmondnative.so /work/assets "$out" "$keystore" "${jni[@]}"
