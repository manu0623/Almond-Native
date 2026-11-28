# Releasing Almond Native

A release is one command. It refuses to publish anything that isn't the
whole, working app.

```bash
# 1. make your changes on main and commit them
# 2. write what changed, in plain words, for people who use the app
$EDITOR release-notes/0.1.3.md
git add release-notes/0.1.3.md && git commit -m "release notes for 0.1.3"
# 3. try it without publishing (builds, checks, installs and tests on the handheld)
scripts/release.sh 0.1.3 --dry-run
#    a dry run leaves the version commit behind; drop it before the real run
git reset --hard HEAD~1
# 4. publish
scripts/release.sh 0.1.3
```

`scripts/release.sh` stops at the first problem and says what to fix. In order,
it:

1. **Checks** you're on `main` with nothing uncommitted and not behind
   `origin`, the version is new, `release-notes/<version>.md` exists, and the
   signing key, GitHub token, packed compiler and Docker (or Podman) are all
   there.
2. **Sets the version** in `Cargo.toml` and `Cargo.lock` and commits it. The
   APK's version code comes from it (0.1.3 is 1003), so it always installs over
   the one before.
3. **Builds the whole app** in the container (`scripts/android/build.sh`):
   the engine, the recompiler from this checkout, the on-device compiler, the
   system font and the game profiles.
4. **Checks the APK** (`scripts/android/verify_apk.sh`): version, signed with
   the release certificate (`scripts/android/release-cert.sha256`), every part
   present, and a recompiler that answers `3dsrecomp id`.
5. **Tests it on the handheld** (`scripts/android/smoke_test.sh`) over adb: it
   installs over the app you have (keeping your games and saves), starts the
   launcher, builds a small dump on the device the way Arcoon asks for it, and
   plays the result the way Arcoon starts it.
6. **Publishes**: tags `v<version>`, pushes `main` and the tag, creates the
   GitHub release with your notes, uploads `almond-native-<version>.apk`, and
   checks the upload landed.

With `--dry-run` it stops after step 5, and nothing leaves your computer.

## What it needs on this computer

| What | Where | Notes |
| --- | --- | --- |
| Signing key | `~/keys/almond-native.keystore` | Every release must use this key, or updates won't install. Back it up. |
| Key password | `~/keys/almond-native.keystore.password` | Or set `KEYSTORE_PASS`. |
| GitHub token | `~/.config/almond/gh-token` | Or set `GITHUB_TOKEN`. Needs permission to push to the repository. |
| Packed on-device compiler | `packed-toolchain/` in this folder | Not in git (about 230 MB). See below. |
| Docker or Podman | | The first build makes the build container (about 1 GB). |
| A handheld over adb | | For step 5. `--no-device` skips it, but then the release was never tried on a device. |
| A small dump to test with | `~/.config/almond/smoke-test.cxi` | Any small homebrew .cxi or .3ds. Or pass `--dump`. |

`--device <serial>` picks the handheld when more than one is connected.

## The packed on-device compiler

The app builds games on the phone with a C compiler it carries: Termux's clang,
lld and LLVM, packed into `packed-toolchain/` (`jni/arm64-v8a/*.so` and
`assets/toolchain.zip`). It's too big for git, so it lives only on the
computer that makes releases. `build.sh` uses it automatically, and refuses to
build the real app without it. The recompiler is always rebuilt from the
checkout, so the pack only has to be made again when the compiler itself
should change.

To make it again (for example on a new computer):

```bash
scripts/android/ondevice-toolchain.sh --work /tmp/almond-toolchain   # fetches and checks Termux's packages
cargo build --release --locked -p recomp3ds --target aarch64-linux-android   # with the NDK, see the script's header
scripts/android/pack_toolchain.sh /tmp/almond-toolchain/prefix <that 3dsrecomp> packed-toolchain
```

Keep a copy of `packed-toolchain/` with your keystore backup.

## When something goes wrong

- **Failed before step 6:** nothing was published. Fix it and run again. If the
  version commit from step 2 is in the way, drop it with
  `git reset --hard HEAD~1`.
- **The upload failed in step 6:** the tag is pushed but the release has no APK.
  Delete the release on GitHub and upload `build/release/almond-native-<version>.apk`
  to a new release for the same tag.
- **"install refused" on the handheld:** the installed app was signed with a
  different key, or is a newer version. Don't change the key; uninstalling
  would delete the games and saves on the device.

## Building without releasing

```bash
KEYSTORE=~/keys/almond-native.keystore KEYSTORE_PASS=... scripts/android/build.sh   # the real app, build/almond-native.apk
scripts/android/build.sh --test   # io.github.almondnative.test, installs beside the real app
```
