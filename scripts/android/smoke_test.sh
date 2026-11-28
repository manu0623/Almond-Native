#!/bin/bash
# smoke_test.sh <apk> [--device serial] [--dump file] [--version x.y.z]:
# installs an Almond Native APK on a connected handheld over adb and checks it
# does what a release has to:
#
#   1. installs over the app already there (same key, newer or same version),
#      keeping its games and saves, and reports the expected version
#   2. the launcher starts and stays up
#   3. with --dump, a game is built on the device from that dump through the
#      BUILD action Arcoon uses, the build reports success, and the built game
#      starts through the PLAY action Arcoon uses and is still running after
#      ten seconds
#
# The dump should be small (a homebrew .cxi or .3ds builds in a few minutes);
# it is copied into the app's own folder and removed again afterwards. Its
# built game stays in the app's list.
set -euo pipefail
apk="$1"; shift
device="" dump="" version=""
while [ $# -gt 0 ]; do
    case "$1" in
        --device) device="$2"; shift 2 ;;
        --dump) dump="$2"; shift 2 ;;
        --version) version="$2"; shift 2 ;;
        *) echo "unknown option $1"; exit 2 ;;
    esac
done
pkg=io.github.almondnative
fail() { echo "FAIL: $*"; exit 1; }
ok() { echo "ok:   $*"; }

adb_bin="$(command -v adb || true)"
for candidate in "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -n "$adb_bin" ] && break
    [ -x "$candidate" ] && adb_bin="$candidate"
done
[ -n "$adb_bin" ] || fail "adb is not installed (Android platform-tools)"
adb=("$adb_bin")
[ -n "$device" ] && adb=("$adb_bin" -s "$device")
"${adb[@]}" get-state >/dev/null 2>&1 || fail "no device connected over adb"
on_device() { "${adb[@]}" shell "$@" | tr -d '\r'; }

echo "== installing $(basename "$apk") on $(on_device getprop ro.product.model)"
"${adb[@]}" install -r "$apk" | tail -1 | grep Success >/dev/null || fail "install refused (a different signing key, or an older version than the one installed)"
installed="$(on_device dumpsys package "$pkg" | sed -n 's/^ *versionName=//p' | head -1)"
[ -z "$version" ] || [ "$installed" = "$version" ] || fail "the device reports $installed, not $version"
ok "installed, version $installed"

"${adb[@]}" logcat -c
on_device am start -W -n "$pkg/.Launcher" >/dev/null
sleep 4
on_device pidof "$pkg" >/dev/null || fail "the launcher is not running four seconds after it started"
if "${adb[@]}" logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep "$pkg" >/dev/null; then
    "${adb[@]}" logcat -d | grep -A15 "FATAL EXCEPTION" | head -30
    fail "the launcher crashed"
fi
ok "the launcher starts and stays up"

if [ -z "$dump" ]; then
    echo "== no --dump: the on-device build was not tried"
    exit 0
fi

[ -f "$dump" ] || fail "$dump not found"
files="/storage/emulated/0/Android/data/$pkg/files"
staged="$files/smoke-test-$(date +%s).${dump##*.}"
echo "== building $(basename "$dump") on the device"
"${adb[@]}" push "$dump" "$staged" >/dev/null || fail "could not copy the dump to $staged"
cleanup() { on_device rm -f "$staged" >/dev/null 2>&1 || true; }
trap cleanup EXIT
"${adb[@]}" logcat -c
on_device am start -a "$pkg.BUILD" -n "$pkg/.Launcher" -d "file://$staged" >/dev/null

# the build service runs while the build does; wait for it to start, then end
started=0
for _ in $(seq 1 30); do
    on_device dumpsys activity services "$pkg/.BuildService" | grep ServiceRecord >/dev/null && { started=1; break; }
    sleep 1
done
[ $started = 1 ] || fail "the build never started"
# a small homebrew takes about a quarter of an hour on an AYN Thor: most of it
# is the phone's clang compiling the recompiled code
began=$(date +%s)
while on_device dumpsys activity services "$pkg/.BuildService" | grep ServiceRecord >/dev/null; do
    minutes=$(( ($(date +%s) - began) / 60 ))
    [ $minutes -lt 40 ] || fail "the build was still running after 40 minutes"
    sleep 60
    echo "   building... $(( ($(date +%s) - began) / 60 )) min"
done

# the launcher reads the result back when it next shows, and logs it
on_device am start -W -n "$pkg/.Launcher" >/dev/null
sleep 3
result="$("${adb[@]}" logcat -d | sed -n 's/.*build-result\.txt: //p' | tail -1 | tr -d '\r')"
[ -n "$result" ] || fail "the build left no result"
case "$result" in
    *"compiled successfully"*) ok "built: $result" ;;
    *) fail "the build failed: $result" ;;
esac
id="$(echo "$result" | grep -oE '[0-9A-F]{16}' | head -1)"
[ -n "$id" ] || fail "no title id in the result"

"${adb[@]}" logcat -c
on_device am start -a "$pkg.PLAY" -n "$pkg/.Launcher" --es game "$files/games/$id" >/dev/null
sleep 10
"${adb[@]}" logcat -d | grep "Displayed $pkg/.Game" >/dev/null || fail "the built game did not open"
on_device pidof "$pkg:game" >/dev/null || fail "the game was not running ten seconds after it opened"
if "${adb[@]}" logcat -d | grep -E "FATAL EXCEPTION|panic" | grep -i "almond" >/dev/null; then
    fail "the game crashed"
fi
ok "the built game opens through PLAY and keeps running"
on_device am force-stop "$pkg"
echo "== Almond Native $installed works on the device"
