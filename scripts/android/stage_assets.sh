#!/bin/bash
# stage_assets.sh <out dir> [<packed toolchain dir>]: the assets directory
# make_apk.sh takes, put together from the checkout alone: the profiles
# (without the overrides beside some of them), the licences as the kit puts
# them beside the program, and the system font every game gets. With a
# directory from pack_toolchain.sh named as the second argument it also holds
# the compiler the app carries, so that a game builds on the phone; without
# it the app is the same but "Build it on this device" has nothing to run.
#
#   bash scripts/android/stage_assets.sh build/assets build/toolchain
#   bash scripts/android/make_apk.sh io.github.almondnative "Almond Native" \
#       build/libalmondnative.so build/assets build/almond-native.apk \
#       build/release.keystore build/toolchain/jni/arm64-v8a
#
# libalmondnative.so is the engine, built for arm64 (the container's build.sh
# does that from this checkout): the assets are all that is staged here.
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    sed -n '2,16p' "$0"
    exit 2
fi
repo="$(cd "$(dirname "$0")/../.." && pwd)"
out="$1" toolchain="${2:-}"
cd "$repo"

rm -rf "$out"
mkdir -p "$out/titles"
# the profiles, without the overrides beside some of them
for profile in titles/*/profile.toml; do
    mkdir -p "$out/$(dirname "$profile")"
    cp "$profile" "$out/$profile"
done

# the licences, as the kit puts them beside the program
python3 tools/third_party.py "$out/THIRD_PARTY_LICENSES.txt" almond-native-android aarch64-linux-android >/dev/null
cp LICENSE CREDITS.md assets/fonts/OFL.txt "$out/"

# the system font, committed and remade from its pinned source by
# shared_font.sh: the build puts it beside every game it makes
if [ ! -f assets/shared_font.bin ]; then
    echo "== assets/shared_font.bin is missing, making it"
    bash scripts/android/shared_font.sh
fi
cp assets/shared_font.bin "$out/"

if [ -n "$toolchain" ]; then
    for file in "$toolchain"/assets/*; do
        [ -e "$file" ] && cp "$file" "$out/"
    done
else
    echo "== no packed toolchain named: this APK will not build games on the phone"
    echo "   make one with scripts/android/pack_toolchain.sh and name its directory"
fi

echo "== $(find "$out" -type f | wc -l) files, $(du -sm "$out" | cut -f1) MB in $out"
