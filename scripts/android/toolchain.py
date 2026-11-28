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

Only what a build was measured to need is taken (Toolchain.java says where
each part goes in the app): a prefix of 673 MB makes a 49 MB sysroot and about
190 MB of libraries, most of it libLLVM.
"""
import os
import pathlib
import sys
import zipfile

LIBS = [
    ("bin/clang", "libclang.so"),
    # lld picks its driver from the name it was run as, and libld.lld.so is
    # the generic driver, which refuses to link: the app makes it a link
    # called ld.lld beside the sysroot, and ccwrap names that one
    ("bin/ld.lld", "libld.lld.so"),
    ("lib/libLLVM.so", "libLLVM.so"),
    ("lib/libclang-cpp.so", "libclang-cpp.so"),
    ("lib/libc++_shared.so", "libc++_shared.so"),
    # Android unpacks only lib*.so out of an APK's lib/<abi>/, and leaves
    # libz.so.1, libzstd.so.1 and libxml2.so.16 in the archive, so there is no
    # libz for libLLVM to load and the compiler never runs. They are carried
    # under a name Android keeps, and the app links the name LLVM asks for
    # to it on the first build (Toolchain.NAMES), as it does for ld.lld
    ("lib/libz.so.1", "libz.1.so"),
    ("lib/libzstd.so.1", "libzstd.1.so"),
    # what libLLVM itself loads: readelf -d on it, not just on the programs
    ("lib/libffi.so", "libffi.so"),
    ("lib/libxml2.so.16", "libxml2.16.so"),
    ("lib/libiconv.so", "libiconv.so"),
]
# the sysroot: what clang includes from, what it links a shared object with
HEADERS = "include"
# what a game's C never reads: the compiler's own C++ and LLVM headers, its
# sanitizers, and the other targets' headers (asm/ and friends are wanted,
# under this target's own name)
SKIP = {"c++", "llvm", "llvm-c", "clang", "clang-tidy", "sanitizer", "libxml2", "camera", "drm"}
OTHER_TARGETS = {"x86_64-linux-android", "i686-linux-android", "arm-linux-androideabi", "riscv64-linux-android"}
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
        if under.parts and under.parts[0] in SKIP | OTHER_TARGETS:
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
    # clang's own resources: its builtin headers, and the one builtins library
    # it links with. The sanitizers', fuzzers' and profiles' are a quarter of
    # the sysroot and a game's build never names one
    resources = prefix / RESOURCES
    builtins = "lib/linux/libclang_rt.builtins-aarch64-android.a"
    for file in resources.rglob("*"):
        under = file.relative_to(resources).as_posix()
        if not file.is_file() or not (under.startswith("include/") or under == builtins):
            continue
        target = sysroot / RESOURCES / under
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
