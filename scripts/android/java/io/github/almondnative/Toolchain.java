package io.github.almondnative;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The compiler the app carries, in the two places Android allows each part of
 * it to live.
 *
 * The programs run from the APK's lib/arm64-v8a, the only place an app may
 * execute code from: clang, lld and the recompiler, with the libraries they
 * load beside them (libLLVM, libclang-cpp, libc++_shared, libz, libzstd,
 * libffi, libxml2, libiconv). They are named lib<something>.so, which is only
 * a name, but lld keeps its own: it picks its driver from the name it runs as.
 *
 * Android unpacks only lib*.so from an APK, so the four whose name ends in a
 * version are carried there under a name it keeps and linked back here under
 * the name their programs ask for (NAMES), lld's own name among them: without
 * the links there is no libz for libLLVM, and the compiler never runs.
 *
 * Everything clang reads and links with is an asset, unpacked into
 * files/toolchain/sysroot on the first build: the C headers, its own
 * lib/clang/21 (the builtin headers and the relaxations it links in), the crt
 * objects and the few libc stubs a shared object links against. ccwrap says
 * where all of it is when 3dsrecomp calls the compiler.
 */
final class Toolchain {
    /** bumped when the bundled compiler changes, and the app unpacks it again */
    static final String VERSION = "clang-21.1.8-lld-21.1.8-h4";

    private Toolchain() {
    }

    static File libDir(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    static File prefix(Context context) {
        return new File(context.getFilesDir(), "toolchain");
    }

    static File tmp(Context context) {
        return new File(context.getFilesDir(), "tmp");
    }

    /** where the names Android cannot unpack are linked, beside the sysroot */
    static File bin(Context context) {
        return new File(prefix(context), "bin");
    }

    /** what a file is called in lib/, and the name the toolchain runs it as or
     *  loads it under: Android unpacks only lib*.so, so the rest are links */
    private static final String[][] NAMES = {
            {"libld.lld.so", "ld.lld"},
            {"libz.1.so", "libz.so.1"},
            {"libzstd.1.so", "libzstd.so.1"},
            {"libxml2.16.so", "libxml2.so.16"},
    };

    /** the links a build runs the toolchain through, made once and left */
    static void links(Context context) throws IOException {
        File bin = bin(context);
        bin.mkdirs();
        File lib = libDir(context);
        for (String[] names : NAMES) {
            File file = new File(lib, names[0]);
            if (!file.isFile()) {
                throw new IOException("this build of the app carries no " + names[0] + ": " + file + " is not there");
            }
            File link = new File(bin, names[1]);
            if (link.exists()) {
                continue;
            }
            // an old link left by another install points at nothing, and the
            // one made now takes its place
            link.delete();
            try {
                Os.symlink(file.getAbsolutePath(), link.getAbsolutePath());
            } catch (ErrnoException error) {
                throw new IOException("could not link " + names[1] + " to " + names[0] + ": " + error);
            }
        }
    }

    static File recompiler(Context context) {
        return new File(libDir(context), "lib3dsrecomp.so");
    }

    /** the sysroot in the app's storage, from the asset the APK carries */
    static void unpack(Context context) throws IOException {
        File root = new File(prefix(context), "sysroot");
        File marker = new File(prefix(context), VERSION);
        if (marker.isFile()) {
            return;
        }
        deleteAll(root);
        root.mkdirs();
        tmp(context).mkdirs();
        String base = root.getCanonicalPath() + File.separator;
        AssetManager assets = context.getAssets();
        byte[] buffer = new byte[1 << 16];
        try (ZipInputStream zip = new ZipInputStream(assets.open("toolchain.zip"))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File to = new File(root, entry.getName());
                // an archive may not write outside where it is unpacked
                if (!to.getCanonicalPath().startsWith(base)) {
                    throw new IOException("toolchain.zip has a path outside it: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    to.mkdirs();
                    continue;
                }
                File parent = to.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(to)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(VERSION.getBytes("UTF-8"));
        }
    }

    /** the environment a build's compiler runs in */
    static void applyTo(ProcessBuilder builder, Context context) {
        Map<String, String> env = builder.environment();
        File lib = libDir(context);
        File bin = bin(context);
        // the names LLVM asks for first, then the files themselves
        env.put("LD_LIBRARY_PATH", bin.getAbsolutePath() + ":" + lib.getAbsolutePath());
        // what ccwrap links with and looks in, and what 3dsrecomp compiles with
        env.put("ALMOND_LIBDIR", bin.getAbsolutePath());
        env.put("ALMOND_CLANG", new File(lib, "libclang.so").getAbsolutePath());
        env.put("ALMOND_SYSROOT", new File(prefix(context), "sysroot").getAbsolutePath());
        env.put("CC", new File(lib, "libccwrap.so").getAbsolutePath());
        // an app may not write the system's temporary directory
        env.put("TMPDIR", tmp(context).getAbsolutePath());
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        String path = env.get("PATH");
        env.put("PATH", lib.getAbsolutePath() + (path == null ? ":/system/bin" : ":" + path));
    }

    static void deleteAll(File file) {
        File[] inside = file.listFiles();
        if (inside != null) {
            for (File child : inside) {
                deleteAll(child);
            }
        }
        file.delete();
    }
}
