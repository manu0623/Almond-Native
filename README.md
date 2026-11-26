<p align="center"><img src="assets/logo/almond-native.png" alt="Almond Native" width="180"></p>

# Almond Native

Almond Native plays your 3DS games on Android. It's the companion app for
Arcoon Launcher, and it builds the games on the phone itself — no Windows PC in
the way.

It's a fork of [Pocket Native](https://github.com/whovian64/pocket-native),
inspired by Peanuts Native, and it keeps Pocket Native's engine, recompiler and
per-game profiles.

## Building a game on the phone

The build tools travel with the app: clang, lld and the recompiler are packed
inside the APK. Point Almond Native at a 3DS game you dumped yourself and it
recompiles that game's code into a native library on the device, then reads the
shaders out of the game's files so the start doesn't stutter while they compile.
When it finishes, Arcoon Launcher starts the game from what the phone built.

It takes several minutes for a big game, and nothing needs to be installed on a
computer.

## What you bring

An arm64 device on Android 11 or newer, and your own dumps. Almond Native ships
no games, no keys and no prebuilt shaders, and it doesn't download any.

## Building the app

`scripts/android/pack_toolchain.sh` puts the compiler into the APK, and
`scripts/android/make_apk.sh` packages the app around it. A ready APK is attached
to the [latest release](https://github.com/manu0623/Almond-Native/releases/latest).

## License

Pocket Native and Almond Native are under the MIT license, see
[LICENSE](LICENSE); [CREDITS.md](CREDITS.md) names everyone whose work is in here.

## AI use

Almond Native is developed with an AI coding agent, Freebuff's Codebuff: the
fork's code, this README and the release notes are written with it from the
maintainer's instructions, and built and checked on the device before release.
