#!/bin/bash
# inside.sh <out.apk> <keystore> [package]: the Android app, built in the
# container: /src the repository, read only, /work the scratch space kept
# between builds.
set -euo pipefail
out="$1" keystore="$2" package="${3:-io.github.almondnative}"
cd /src
cargo build --release --locked --target aarch64-linux-android -p almond-native-android
"$NDK_BIN/llvm-strip" -o /work/libalmondnative.so "$CARGO_TARGET_DIR/aarch64-linux-android/release/libalmondnative.so"
# the profiles, without the overrides beside some of them
rm -rf /work/assets && mkdir -p /work/assets/titles
for profile in titles/*/profile.toml; do
    mkdir -p "/work/assets/$(dirname "$profile")"
    cp "$profile" "/work/assets/$profile"
done
# the licenses, as the kit puts them beside the program
python3 tools/third_party.py /work/assets/THIRD_PARTY_LICENSES.txt almond-native-android aarch64-linux-android >/dev/null
cp LICENSE CREDITS.md assets/fonts/OFL.txt /work/assets/
label="Almond Native"
[ "$package" = io.github.almondnative ] || label="Almond Native (test)"
bash scripts/android/make_apk.sh "$package" "$label" /work/libalmondnative.so /work/assets "$out" "$keystore"
