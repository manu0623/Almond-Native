#!/bin/bash
# ondevice-toolchain.sh [--device serial] [--work dir]: puts on an Android
# device the two pieces a game's build needs that no phone ships: a C
# compiler, which 3dsrecomp hands its generated code to (recompiler/src/
# compile.rs takes CC and AR from the environment), and 3dsrecomp itself,
# built for the device's own CPU. Nothing here is used by the app yet: this is
# what an in-app build service would carry, unpacked into its own storage.
#
# The compiler is Termux's, built to run on Android's Bionic: clang and lld
# with the LLVM library they load and the NDK's sysroot, about 460 MB
# unpacked on the device. Its binaries point at Termux's own prefix, so they
# are run with LD_LIBRARY_PATH naming where they were unpacked here.
#
# 3dsrecomp itself is cross-compiled on the host, which needs Rust and the
# NDK (both outside this script):
#
#   export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"
#   export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
#   export AR_aarch64_linux_android="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
#   cargo build --release --locked -p recomp3ds --target aarch64-linux-android
#
# then --recompiler <that 3dsrecomp> has it pushed too, to
# /data/local/tmp/3dsrecomp.
set -euo pipefail

work="${TMPDIR:-/tmp}/almond-ondevice-toolchain"
device="" recompiler=""
while [ $# -gt 0 ]; do
    case "$1" in
        --device) device="$2"; shift 2 ;;
        --work) work="$2"; shift 2 ;;
        --recompiler) recompiler="$2"; shift 2 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) echo "unknown option $1"; exit 2 ;;
    esac
done

# adb, from PATH or the SDK it usually sits in
adb_bin="$(command -v adb || true)"
for candidate in "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -n "$adb_bin" ] && break
    [ -x "$candidate" ] && adb_bin="$candidate"
done
[ -n "$adb_bin" ] || { echo "adb is not installed (Android platform-tools)"; exit 1; }
adb=("$adb_bin")
[ -n "$device" ] && adb=("$adb_bin" -s "$device")

prefix="$work/prefix"
debs="$work/debs"
mkdir -p "$debs"

if [ ! -d "$prefix/data/data/com.termux/files/usr/lib" ]; then
    echo "== fetching Termux's clang, lld, LLVM and sysroot into $prefix"
    python3 - "$debs" "$prefix" <<'PY'
"""clang for Android, and everything it loads, from the Termux pool."""
import gzip, io, re, sys, tarfile, urllib.request, pathlib

REPO = "https://packages.termux.dev/apt/termux-main"
debs, prefix = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])


def fetch(url, timeout=900):
    return urllib.request.urlopen(url, timeout=timeout).read()


index = gzip.decompress(fetch(f"{REPO}/dists/stable/main/binary-aarch64/Packages.gz", 300)).decode("utf-8", "replace")
packages = {}
for block in index.split("\n\n"):
    fields = {}
    for line in block.splitlines():
        if ": " in line and not line.startswith(" "):
            key, value = line.split(": ", 1)
            fields[key] = value
    if "Package" in fields:
        packages[fields["Package"]] = fields


def closure(root):
    """the package and everything its Depends names, by the index's own Filename"""
    queue, seen, chosen = [root], set(), []
    while queue:
        name = queue.pop(0)
        if name in seen or name not in packages:
            continue
        seen.add(name)
        chosen.append(name)
        for part in packages[name].get("Depends", "").split(","):
            dependency = part.strip().split(" ")[0].split("(")[0].split(":")[0].strip()
            if dependency and dependency != "|":
                queue.append(dependency)
    return chosen


def members(data):
    """the ar members of a .deb"""
    at = 8  # past "!<arch>\n"
    while at + 60 <= len(data):
        header = data[at:at + 60]
        name = header[0:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        yield name, data[at + 60:at + 60 + size]
        at += 60 + size + (size % 2)


wanted = closure("clang")
print(f"   {len(wanted)} packages: {' '.join(wanted)}")
for name in wanted:
    filename = packages[name]["Filename"]
    cached = debs / pathlib.Path(filename).name
    if not cached.exists():
        print(f"   {filename}")
        cached.write_bytes(fetch(f"{REPO}/{filename}"))
    for member, body in members(cached.read_bytes()):
        if member.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body), mode="r:*") as tar:
                tar.extractall(prefix, filter="data")
print(f"   {sum(f.stat().st_size for f in prefix.rglob('*') if f.is_file()) // (1024 * 1024)} MB unpacked")
PY
fi

usr="$prefix/data/data/com.termux/files/usr"
# tar, not adb push: the toolchain is a tree of symlinks, which tar keeps
echo "== copying the toolchain to the device"
"${adb[@]}" shell "rm -rf /data/local/tmp/termux; mkdir -p /data/local/tmp/termux/usr"
tar -C "$usr" -cf - . | "${adb[@]}" shell "tar -xf - -C /data/local/tmp/termux/usr"
"${adb[@]}" shell "chmod -R 755 /data/local/tmp/termux/usr/bin /data/local/tmp/termux/usr/lib /data/local/tmp/termux/usr/libexec 2>/dev/null || true"

if [ -n "$recompiler" ]; then
    echo "== copying 3dsrecomp to the device"
    "${adb[@]}" push "$recompiler" /data/local/tmp/3dsrecomp >/dev/null
    "${adb[@]}" shell chmod 755 /data/local/tmp/3dsrecomp
fi

echo "== the compiler, running on the device"
"${adb[@]}" shell "export LD_LIBRARY_PATH=/data/local/tmp/termux/usr/lib; /data/local/tmp/termux/usr/bin/clang --version" | head -3
echo "== and a library built there"
"${adb[@]}" shell "export LD_LIBRARY_PATH=/data/local/tmp/termux/usr/lib
printf 'int add(int a, int b) { return a + b; }\n' > /data/local/tmp/ondevice-check.c
cd /data/local/tmp && /data/local/tmp/termux/usr/bin/clang -O2 -ffp-contract=off -fno-math-errno -w -fPIC -fvisibility=hidden -fno-stack-protector -shared -o ondevice-check.so ondevice-check.c
ls -l /data/local/tmp/ondevice-check.so"
echo "== done: the device compiles for its own CPU. CC and AR for a build are"
echo "   /data/local/tmp/termux/usr/bin/clang and /data/local/tmp/termux/usr/bin/llvm-ar"
echo "   with LD_LIBRARY_PATH=/data/local/tmp/termux/usr/lib"
