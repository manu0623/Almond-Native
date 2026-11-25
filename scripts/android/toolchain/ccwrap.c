/* ccwrap: the C compiler a game's recompiled code is built with, named in CC
 * (recompiler/src/compile.rs takes it from the environment).
 *
 * clang finds its sysroot and its resource dir by where it sits, and the app
 * can only run a program from the APK's lib/<abi>/, where nothing may be
 * written beside it. So the toolchain unpacks to the app's own storage and
 * this wrapper, which lives beside clang, says where everything is and then
 * becomes clang: ALMOND_CLANG names it, ALMOND_SYSROOT the unpacked sysroot
 * (its lib/clang/21 its resource dir), and the linker is the libld.lld.so
 * beside this one.
 */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define MAX_ARGUMENTS 10240

int main(int argc, char **argv) {
    const char *clang = getenv("ALMOND_CLANG");
    const char *sysroot = getenv("ALMOND_SYSROOT");
    const char *libdir = getenv("ALMOND_LIBDIR");
    if (clang == NULL || sysroot == NULL || libdir == NULL) {
        fprintf(stderr, "ccwrap: ALMOND_CLANG, ALMOND_SYSROOT and ALMOND_LIBDIR must name the toolchain\n");
        return 2;
    }
    if (argc + 7 >= MAX_ARGUMENTS) {
        fprintf(stderr, "ccwrap: too many arguments\n");
        return 2;
    }
    /* the objects clang writes per file are its own business, but it needs
     * somewhere to write them that the app may use */
    static char banner[4096], resources[4096], linker[4096], tree[4096], arch[4096];
    snprintf(tree, sizeof tree, "--sysroot=%s", sysroot);
    /* the arch's own headers, asm/ among them, which linux/types.h asks for
     * and the sysroot keeps under the target's name */
    snprintf(arch, sizeof arch, "%s/include/aarch64-linux-android", sysroot);
    snprintf(resources, sizeof resources, "-resource-dir=%s/lib/clang/21", sysroot);
    /* lld chooses its driver by the name it is run under, so it keeps ld.lld */
    snprintf(linker, sizeof linker, "--ld-path=%s/ld.lld", libdir);
    (void)banner;

    char *mine[MAX_ARGUMENTS];
    int n = 0;
    mine[n++] = (char *)clang;
    mine[n++] = tree;
    mine[n++] = resources;
    mine[n++] = linker;
    mine[n++] = "-isystem";
    mine[n++] = arch;
    for (int i = 1; i < argc && n < MAX_ARGUMENTS - 1; i++) {
        mine[n++] = argv[i];
    }
    mine[n] = NULL;

    execv(clang, mine);
    perror("ccwrap: execv");
    return 1;
}
