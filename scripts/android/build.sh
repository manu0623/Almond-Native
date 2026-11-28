#!/bin/bash
# build.sh [--out dir] [--test] [--toolchain dir] [--no-toolchain]: builds
# Almond Native's Android app, almond-native.apk in the output directory
# (build/ by default), inside a container (podman or docker). --test makes
# io.github.almondnative.test, signed with a test key, which installs beside
# the real app. The real app is signed with the keystore in KEYSTORE (its
# password in KEYSTORE_PASS), kept by whoever releases it.
#
# The on-device compiler comes from --toolchain, else packed-toolchain/ at the
# repository's root, else build/toolchain (a pack_toolchain.sh output). The
# real app refuses to build without one, since a release without it cannot
# build games on the phone; --no-toolchain builds one anyway.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
out="$repo/build" test=0 toolchain="" no_toolchain=0
while [ $# -gt 0 ]; do
    case "$1" in
        --out) out="$(realpath -m "$2")"; shift 2 ;;
        --test) test=1; shift ;;
        --toolchain) toolchain="$(realpath -m "$2")"; shift 2 ;;
        --no-toolchain) no_toolchain=1; shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "unknown option $1"; exit 2 ;;
    esac
done
if [ $no_toolchain = 0 ] && [ -z "$toolchain" ]; then
    for candidate in "$repo/packed-toolchain" "$repo/build/toolchain"; do
        [ -d "$candidate/jni/arm64-v8a" ] && [ -f "$candidate/assets/toolchain.zip" ] && { toolchain="$candidate"; break; }
    done
fi
if [ -n "$toolchain" ]; then
    [ -d "$toolchain/jni/arm64-v8a" ] && [ -f "$toolchain/assets/toolchain.zip" ] \
        || { echo "$toolchain is not a packed toolchain (jni/arm64-v8a and assets/toolchain.zip)"; exit 2; }
    echo "== on-device compiler from $toolchain"
elif [ $test = 0 ] && [ $no_toolchain = 0 ]; then
    echo "no packed toolchain: put one in packed-toolchain/ (see docs/RELEASING.md) or pass --no-toolchain"
    exit 2
fi
engine=$(command -v podman || command -v docker) || { echo "the build needs podman or docker"; exit 1; }
image=almond-native-android:$(sha256sum "$repo/scripts/android/Containerfile" | cut -c1-12)
if ! "$engine" image inspect "$image" >/dev/null 2>&1; then
    echo "== making the Android build container, once (the NDK is about 1 GB)"
    "$engine" build -f "$repo/scripts/android/Containerfile" -t "$image" "$repo/scripts/android"
fi
mkdir -p "$out/.work-android"
user=(--user "$(id -u):$(id -g)")
[ "$(basename "$engine")" = podman ] && user=(--userns=keep-id)
mounts=(-v "$repo:/src:ro" -v "$out/.work-android:/work" -v "$out:/out")
[ -n "$toolchain" ] && mounts+=(-v "$toolchain:/toolchain:ro")
if [ $test = 1 ]; then
    args=(/out/almond-native-test.apk /work/test.keystore io.github.almondnative.test)
else
    [ -n "${KEYSTORE:-}" ] && [ -f "$KEYSTORE" ] || { echo "KEYSTORE names the release keystore; --test builds without it"; exit 2; }
    mounts+=(-v "$(dirname "$(realpath "$KEYSTORE")"):/keys")
    args=(/out/almond-native.apk "/keys/$(basename "$KEYSTORE")")
fi
"$engine" run --rm "${user[@]}" "${mounts[@]}" -e CARGO_HOME=/work/cargo -e CARGO_TARGET_DIR=/work/target -e HOME=/work \
    -e KEYSTORE_PASS="${KEYSTORE_PASS:-android}" "$image" bash /src/scripts/android/inside.sh "${args[@]}"
echo "== $out/$(basename "${args[0]}")"
