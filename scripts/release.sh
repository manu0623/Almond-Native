#!/bin/bash
# release.sh <version> [--dump file] [--device serial] [--no-device] [--dry-run]:
# makes an Almond Native release from main, from a version number to a
# published GitHub release, and stops at the first thing that is not right.
#
#   1. checks: on main, nothing uncommitted, not behind origin, the version is
#      new (no tag, no release), release-notes/<version>.md exists, the
#      signing key, its password, a GitHub token that can publish, a packed
#      on-device compiler, and podman or docker are all there
#   2. sets the workspace version (Cargo.toml and Cargo.lock) and commits it
#   3. builds the whole app in the container (scripts/android/build.sh)
#   4. checks the APK (scripts/android/verify_apk.sh): version, signing
#      certificate, engine, recompiler, compiler, font, size
#   5. installs it on the handheld over adb and, with --dump, builds that dump
#      on the device and plays it (scripts/android/smoke_test.sh); --no-device
#      skips this, for when no handheld is connected
#   6. tags v<version>, pushes main and the tag, publishes the GitHub release
#      with the notes and the APK as almond-native-<version>.apk, and checks
#      the upload landed
#
# --dry-run does 1 to 5 and stops before anything leaves this machine: the
# version commit stays local (undo it with git reset --hard HEAD~1).
#
# Where things come from, each overridable by environment:
#   KEYSTORE          ~/keys/almond-native.keystore
#   KEYSTORE_PASS     the contents of $KEYSTORE.password
#   GITHUB_TOKEN      the contents of ~/.config/almond/gh-token
#   ALMOND_SMOKE_DUMP a default for --dump, else ~/.config/almond/smoke-test.cxi
# See docs/RELEASING.md.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
version="${1:-}"; shift || true
dump="${ALMOND_SMOKE_DUMP:-}" device=""
[ -z "$dump" ] && [ -f "$HOME/.config/almond/smoke-test.cxi" ] && dump="$HOME/.config/almond/smoke-test.cxi" no_device=0 dry_run=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dump) dump="$2"; shift 2 ;;
        --device) device="$2"; shift 2 ;;
        --no-device) no_device=1; shift ;;
        --dry-run) dry_run=1; shift ;;
        -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
        *) echo "unknown option $1"; exit 2 ;;
    esac
done

step() { echo; echo "=== $*"; }
fail() { echo "FAIL: $*"; exit 1; }
ok() { echo "ok:   $*"; }

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { sed -n '2,3p' "$0"; fail "give the version as x.y.z, e.g. 0.1.3"; }
tag="v$version"

# ---- 1. checks
step "1/6 checks for $tag"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || fail "not on main"
[ -z "$(git status --porcelain --untracked-files=no)" ] || { git status --short --untracked-files=no; fail "commit or stash these first"; }
git fetch -q origin main --tags || fail "could not reach origin"
[ "$(git rev-list --count HEAD..origin/main)" = 0 ] || fail "main is behind origin/main: pull first"
ok "main, clean, up to date with origin"

git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists"
notes="release-notes/$version.md"
[ -s "$notes" ] || fail "$notes is missing: write what changed, in plain words, first"
ok "$tag is new, notes in $notes"

export KEYSTORE="${KEYSTORE:-$HOME/keys/almond-native.keystore}"
[ -f "$KEYSTORE" ] || fail "no signing key at $KEYSTORE"
if [ -z "${KEYSTORE_PASS:-}" ]; then
    [ -f "$KEYSTORE.password" ] || fail "no KEYSTORE_PASS and no $KEYSTORE.password"
    KEYSTORE_PASS="$(tr -d '\r\n' < "$KEYSTORE.password")"
fi
export KEYSTORE_PASS
ok "signing key $KEYSTORE"

if [ -z "${GITHUB_TOKEN:-}" ]; then
    [ -f "$HOME/.config/almond/gh-token" ] || fail "no GITHUB_TOKEN and no ~/.config/almond/gh-token"
    GITHUB_TOKEN="$(tr -d '[:space:]' < "$HOME/.config/almond/gh-token")"
fi
slug="$(git remote get-url origin | sed -E 's#^(git@github.com:|https://github.com/)##; s#\.git$##')"
api() { curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
can_push="$(api "https://api.github.com/repos/$slug" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("permissions",{}).get("push", False))')"
[ "$can_push" = True ] || fail "the GitHub token can't publish to $slug"
[ "$(api -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$slug/releases/tags/$tag")" = 404 ] || fail "$slug already has a release $tag"
git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1 && fail "origin already has a tag $tag"
ok "GitHub token can publish to $slug"

[ -d packed-toolchain/jni/arm64-v8a ] || [ -d build/toolchain/jni/arm64-v8a ] \
    || fail "no packed on-device compiler (packed-toolchain/): see docs/RELEASING.md"
command -v podman >/dev/null || command -v docker >/dev/null || fail "the build needs podman or docker"
ok "packed compiler and container engine"

if [ $no_device = 0 ]; then
    [ -n "$dump" ] || fail "give --dump <small .3ds/.cxi> so the build on the device is tried, or --no-device"
    [ -f "$dump" ] || fail "$dump not found"
fi

# ---- 2. version
step "2/6 version $version"
current="$(sed -n '/^\[workspace.package\]/,/^\[/{s/^version = "\(.*\)"$/\1/p}' Cargo.toml)"
if [ "$current" != "$version" ]; then
    python3 - "$current" "$version" <<'PY'
import sys
old, new = sys.argv[1], sys.argv[2]
toml = open("Cargo.toml").read()
toml = toml.replace(f'[workspace.package]\nversion = "{old}"', f'[workspace.package]\nversion = "{new}"', 1)
open("Cargo.toml", "w").write(toml)
# the workspace's own crates: no source line, the old version
blocks = open("Cargo.lock").read().split("\n[[package]]\n")
for i, block in enumerate(blocks):
    if f'version = "{old}"' in block and "source = " not in block:
        blocks[i] = block.replace(f'version = "{old}"', f'version = "{new}"', 1)
open("Cargo.lock", "w").write("\n[[package]]\n".join(blocks))
PY
    git add Cargo.toml Cargo.lock
    git commit -q -m "the workspace is $version, so the APK built from this tree says $version"
    ok "$current -> $version, committed"
else
    ok "already $version"
fi

# ---- 3. build
step "3/6 building the app"
bash scripts/android/build.sh
apk="build/release/almond-native-$version.apk"
mkdir -p build/release
cp build/almond-native.apk "$apk"
ok "$apk"

# ---- 4. check the APK
step "4/6 checking the APK"
engine=$(command -v podman || command -v docker)
image=almond-native-android:$(sha256sum scripts/android/Containerfile | cut -c1-12)
"$engine" run --rm -v "$repo:/src:ro" "$image" bash /src/scripts/android/verify_apk.sh "/src/$apk" "$version"

# ---- 5. on the device
step "5/6 on the device"
if [ $no_device = 1 ]; then
    echo "skipped (--no-device): this build has not been tried on a handheld"
else
    args=("$apk" --version "$version" --dump "$dump")
    [ -n "$device" ] && args+=(--device "$device")
    bash scripts/android/smoke_test.sh "${args[@]}"
fi

if [ $dry_run = 1 ]; then
    echo
    echo "=== dry run: $apk is ready, nothing was tagged, pushed or published"
    exit 0
fi

# ---- 6. publish
step "6/6 publishing $tag"
git tag -a "$tag" -m "Almond Native $version"
git push -q origin main
git push -q origin "$tag"
ok "pushed main and $tag"

body="$(python3 -c 'import json,sys; print(json.dumps({"tag_name": sys.argv[1], "name": "Almond Native " + sys.argv[2], "body": open(sys.argv[3]).read().strip(), "draft": False, "prerelease": False, "make_latest": "true"}))' "$tag" "$version" "$notes")"
release="$(api -X POST -d "$body" "https://api.github.com/repos/$slug/releases")"
id="$(echo "$release" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')"
[ -n "$id" ] || { echo "$release" | head -c 400; fail "GitHub refused the release"; }
asset="almond-native-$version.apk"
uploaded="$(api -X POST -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$apk" \
    "https://uploads.github.com/repos/$slug/releases/$id/assets?name=$asset")"
echo "$uploaded" | python3 -c 'import json,sys,os; d=json.load(sys.stdin); assert d.get("state")=="uploaded" and d.get("size")==os.path.getsize(sys.argv[1]), d' "$apk" \
    || fail "the APK upload did not land: delete the release on GitHub and run step 6 again"
ok "published https://github.com/$slug/releases/tag/$tag with $asset"
echo
echo "=== Almond Native $version is out"
