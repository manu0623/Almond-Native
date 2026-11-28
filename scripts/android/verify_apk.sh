#!/bin/bash
# verify_apk.sh <apk> <version> [cert.sha256]: checks an Almond Native APK is
# the whole app a release ships, run in the Android build container (it needs
# aapt2, apksigner and python3, which the container carries):
#
#   - package io.github.almondnative, versionName <version>, and the version
#     code make_apk.sh derives from it (0.1.2 is 1002)
#   - signed with the certificate whose SHA-256 the file names
#     (scripts/android/release-cert.sha256 by default), so an update installs
#     over the app people have instead of being refused
#   - the engine, the recompiler and the on-device compiler in lib/arm64-v8a,
#     toolchain.zip and the system font in assets: without them games play but
#     "Build it on this device" fails at once
#   - a recompiler that answers `3dsrecomp id`, which BuildService calls
#
# Prints every check and exits 1 on the first one that fails.
set -euo pipefail
apk="$1" version="$2"
here="$(cd "$(dirname "$0")" && pwd)"
cert_file="${3:-$here/release-cert.sha256}"
fail() { echo "FAIL: $*"; exit 1; }
ok() { echo "ok:   $*"; }

[ -f "$apk" ] || fail "$apk not found"
IFS=. read -r major minor patch <<< "$version"
code=$((major * 1000000 + minor * 1000 + patch))

# read whole, then cut: `| head` would close the pipe on aapt2, and pipefail
# would take its SIGPIPE for a failure
badging="$("$BUILD_TOOLS_BIN/aapt2" dump badging "$apk")"
badging="${badging%%$'\n'*}"
echo "$badging" | grep -q "name='io.github.almondnative'" || fail "package is not io.github.almondnative: $badging"
echo "$badging" | grep -q "versionName='$version'" || fail "versionName is not $version: $badging"
echo "$badging" | grep -q "versionCode='$code'" || fail "versionCode is not $code: $badging"
ok "io.github.almondnative $version (version code $code)"

want="$(tr -d '[:space:]' < "$cert_file")"
certs="$("$BUILD_TOOLS_BIN/apksigner" verify --print-certs "$apk")"
have="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ -n "$have" ] || fail "apksigner could not verify the signature"
[ "$have" = "$want" ] || fail "signed with $have, the release certificate is $want"
ok "signed with the release certificate"

python3 - "$apk" <<'PY'
import sys, zipfile
apk = zipfile.ZipFile(sys.argv[1])
names = set(apk.namelist())
need = [
    "classes.dex",
    "lib/arm64-v8a/libalmondnative.so",
    "lib/arm64-v8a/lib3dsrecomp.so",
    "lib/arm64-v8a/libccwrap.so",
    "lib/arm64-v8a/libclang.so",
    "lib/arm64-v8a/libLLVM.so",
    "lib/arm64-v8a/libld.lld.so",
    "assets/toolchain.zip",
    "assets/shared_font.bin",
    "assets/THIRD_PARTY_LICENSES.txt",
]
missing = [n for n in need if n not in names]
if missing:
    print("FAIL: missing from the APK: " + ", ".join(missing))
    sys.exit(1)
print("ok:   engine, recompiler, on-device compiler, toolchain.zip and system font are in")
profiles = sum(1 for n in names if n.startswith("assets/titles/") and n.endswith("/profile.toml"))
if profiles == 0:
    print("FAIL: no game profiles in assets/titles")
    sys.exit(1)
print(f"ok:   {profiles} game profiles")
recomp = apk.read("lib/arm64-v8a/lib3dsrecomp.so")
if b"3dsrecomp id <rom>" not in recomp:
    print("FAIL: the packed recompiler has no `id` subcommand, BuildService needs it")
    sys.exit(1)
print("ok:   the recompiler answers `3dsrecomp id`")
PY

size=$(stat -c %s "$apk")
[ "$size" -gt 150000000 ] || fail "only $((size / 1000000)) MB: a whole app is over 150 MB"
ok "$((size / 1000000)) MB"
echo "== $apk is a complete Almond Native $version"
