#!/bin/bash
# shared_font.sh [out]: the system font the app carries, made the way the kit
# makes every game's sysdata/shared_font.bin, from the same pinned source, so
# that a fresh checkout's APK has the same font every build.
#
# The font is Azahar's open-source replacement for the 3DS's system font:
# B3n30's citra_system_archives (GPL-2.0) generated it from Montserrat (SIL
# Open Font License 1.1) and GNU Unifont 10.0.05 (GPL-2.0-or-later, with the
# GNU font embedding exception). The OFL text it carries is assets/fonts/OFL.txt,
# and tools/third_party.py says the same of it in the app's
# THIRD_PARTY_LICENSES.txt. It is not Nintendo's font, and no key or console
# dump is needed to make it.
#
# The URL is the commit scripts/container/inside.sh downloads, so the kit, the
# phone's builds and this file are all one source.
set -euo pipefail

here="$(cd "$(dirname "$0")/../.." && pwd)"
out="${1:-$here/assets/shared_font.bin}"
url="https://raw.githubusercontent.com/azahar-emu/azahar/c9e1e946a0b5d30a58ae14dee83c1f203a49622d/externals/open_source_archives/include/shared_font.app.romfs.h"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "== downloading the font's source from $url"
curl -sSfL "$url" -o "$work/shared_font.app.romfs.h"

echo "== making $(basename "$out")"
python3 "$here/tools/make_shared_font.py" "$work/shared_font.app.romfs.h" "$out"

# the shape the engine reads: a status word saying the system has loaded it,
# then a CFNU font at 0x80 (engine/core/src/services/apt.rs)
python3 - "$out" <<'PY'
import struct
import sys

path = sys.argv[1]
data = open(path, "rb").read()
status = struct.unpack("<I", data[:4])[0]
if status != 2:
    sys.exit(f"{path}: the status word says {status}, not loaded (2)")
if data[0x80:0x84] != b"CFNU":
    sys.exit(f"{path}: no CFNU at 0x80, found {data[0x80:0x84]!r}")
print(f"   {path}: {len(data)} bytes, CFNU at 0x80")
PY
