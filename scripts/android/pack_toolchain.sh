#!/bin/bash
# pack_toolchain.sh <termux prefix> <3dsrecomp> <out dir> [ndk]: everything
# the app carries to build a game on the phone, from a Termux prefix (the one
# scripts/android/ondevice-toolchain.sh fetches) and a 3dsrecomp built for
# arm64. Out is what make_apk.sh takes as its optional jni dir, its assets
# beside it:
#
#   <out>/jni/arm64-v8a/*.so        copied into lib/arm64-v8a
#   <out>/assets/toolchain.zip      copied into the APK's assets
#
# ccwrap is built here rather than unpacked: it is the app's own C wrapper,
# and it must be a PIE executable like the compilers beside it, not a library.
set -euo pipefail

if [ $# -lt 3 ] || [ $# -gt 4 ]; then
    sed -n '2,12p' "$0"
    exit 2
fi
prefix="$1" recompiler="$2" out="$3"
ndk="${4:-$({ ls -d "$HOME"/Android/Sdk/ndk/* /opt/android-sdk/ndk/* 2>/dev/null || true; } | sort -V | tail -1)}"
[ -n "$ndk" ] || { echo "no NDK found: name one as the fourth argument"; exit 1; }
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
here="$(cd "$(dirname "$0")" && pwd)"

python3 "$here/toolchain.py" "$prefix" "$recompiler" "$out"

echo "== building ccwrap"
"$bin/aarch64-linux-android30-clang" -O2 -fPIE -pie -o "$out/jni/arm64-v8a/libccwrap.so" "$here/toolchain/ccwrap.c"
echo "   $(file -b "$out/jni/arm64-v8a/libccwrap.so")"
