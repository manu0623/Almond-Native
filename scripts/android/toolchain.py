#!/usr/bin/env python3
"""toolchain.py <termux prefix> <3dsrecomp> <out dir>: what the app carries so
that it can build a game on the phone, split the way Android needs it.

    <out>/jni/arm64-v8a/    programs and the libraries they load, which only
                            run from the APK's lib/<abi>/: clang, lld, the
                            recompiler, ccwrap, and libLLVM, libclang-cpp,
                            libc++_shared, libz, libzstd they need
    <out>/assets/toolchain.zip
                            the rest of the compiler, unpacked into the app's
                            storage on the first build: the C headers, the
                            relaxations clang links with (its
                            lib/clang/21), the crt objects and the few libc
                            stubs the sysroot links against

Only what a build was measured to need is taken (scripts/android/README.md in
the app's own words): a prefix of 673 MB makes a 49 MB sysroot and about
190 MB of libraries, most of it libLLVM.
"""
import os
import pathlib
import sys
import zipfile

LIBS = [
    ("bin/clang", "libclang.so"),
    # lld picks its driver from the name it was run as, so this one keeps
    # its own: libld.lld.so is the generic driver and refuses to link
    ("bin/ld.lld", "ld.lld"),
    ("lib/libLLVM.so", "libLLVM.so"),
    ("lib/libclang-cpp.so", "libclang-cpp.so"),
    ("lib/libc++_shared.so", "libc++_shared.so"),
    ("lib/libz.so.1", "libz.so.1"),
    ("lib/libzstd.so.1", "libzstd.so.1"),
    # what libLLVM itself loads: readelf -d on it, not just on the programs
    ("lib/libffi.so", "libffi.so"),
    ("lib/libxml2.so.16", "libxml2.so.16"),
    ("lib/libiconv.so", "libiconv.so"),
]
# the sysroot: what clang includes from, what it links a shared object with
HEADERS = "include"
CRT = ("crtbegin_dynamic.o", "crtbegin_so.o", "crtend_android.o", "crtend_so.o", "crt_pad_segment.o", "libunwind.a")
STUBS = ("libc.so", "libm.so", "libdl.so", "liblog.so")
RESOURCES = "lib/clang/21"


def main(prefix, recompiler, out):
    prefix, recompiler, out = pathlib.Path(prefix), pathlib.Path(recompiler), pathlib.Path(out)
    jni = out / "jni" / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    for source, name in LIBS:
        path = prefix / source
        if not path.exists():
            sys.exit(f"{path} is not there: is this a Termux prefix?")
        (jni / name).write_bytes(path.read_bytes())
    (jni / "lib3dsrecomp.so").write_bytes(recompiler.read_bytes())
    print(f"== {len(list(jni.iterdir()))} programs and libraries, {sum(f.stat().st_size for f in jni.iterdir()) // (1024 * 1024)} MB")

    # the sysroot, dereferenced: a zip the app unpacks with nothing but its
    # own java.util.zip, so no symlinks and no permissions to carry
    sysroot = out / "sysroot"
    if sysroot.exists():
        import shutil
        shutil.rmtree(sysroot)
    (sysroot / "include").mkdir(parents=True)
    (sysroot / "lib").mkdir(parents=True)
    # every header under include, its subdirectories too: math.h includes
    # <sys/cdefs.h>, and a flat copy of the *.h leaves the compiler without a
    # single one of them
    # every header under include, its subdirectories too, the ones that are
    # symlinks to a directory among them: math.h includes <sys/cdefs.h> and
    # linux/types.h includes <asm/types.h>, one a directory and one a symlink
    # to the other, and a plain walk of the *.h leaves the compiler without
    # either
    include = prefix / HEADERS
    copied = 0
    for root, dirs, files in os.walk(include, followlinks=True):
        root = pathlib.Path(root)
        under = root.relative_to(include)
        if under.parts and under.parts[0] == "c++":
            dirs[:] = []
            continue
        for name in files:
            target = sysroot / "include" / under / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((root / name).read_bytes())
            copied += 1
    if not copied:
        sys.exit(f"{include} has no C headers")
    for name in CRT:
        source = prefix / "lib" / name
        if not source.exists():
            sys.exit(f"{source} is not there")
        (sysroot / "lib" / name).write_bytes(source.read_bytes())
    for name in STUBS:
        source = prefix / "lib" / name
        if source.exists():
            (sysroot / "lib" / name).write_bytes(source.read_bytes())
    resources = prefix / RESOURCES
    for file in resources.rglob("*"):
        if file.is_file():
            target = sysroot / RESOURCES / file.relative_to(resources)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(file.read_bytes())

    archive = out / "assets" / "toolchain.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zip_file:
        for file in sorted(sysroot.rglob("*")):
            if file.is_file():
                zip_file.write(file, str(file.relative_to(sysroot)))
    print(f"== {archive} {archive.stat().st_size // (1024 * 1024)} MB, "
          f"{len(list(sysroot.rglob('*')))} entries unpacked")


if __name__ == "__main__":
    main(*sys.argv[1:4])
