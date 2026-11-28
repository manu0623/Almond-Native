# Start here

Almond Native is an Android app that builds your 3DS games **on the phone
itself** and then plays them. Games don't come with it, and it doesn't play
your game files directly. The first time you add a game, the app recompiles
its code into a program for your phone, using the compiler it carries. That
takes a few minutes. After that the game starts like any other.

No PC is needed. If you already use Pocket Native's PC kit, the games it
builds can still be added (see [Games built on a PC](#games-built-on-a-pc)).

Before you start, check [which games work](docs/compatibility.md).

## What you need

- **An Android phone or handheld** with Android 11 or newer, 64-bit (almost
  any from the last few years).
- **Free space:** about 1 GB for the app and its compiler, plus each game's
  own size and a few hundred MB more for its build. The app checks there is
  room before it copies a game.
- **Your own games, dumped from your own console and decrypted:** a `.3ds`
  or `.cci` cartridge image, a `.cia` or a `.cxi`. To decrypt a dump, use
  GodMode9 on your console (**Decrypt file**). The app tells you when a file
  is encrypted, an update or DLC rather than a game, or cut short.

## 1. Install the app

Download the `.apk` under Assets on the
[latest release](https://github.com/manu0623/Almond-Native/releases/latest)
onto the device and open it. The first time, Android asks you to allow
installing apps from your browser or file manager.

Uninstalling the app deletes the games and saves it holds, because Android
removes an app's folder along with it. To update, install a newer `.apk` over
the old one, or use **Update** in the app.

## 2. Add and build a game

Tap **Add game**, then either:

- **Build it on this device:** pick one dump. The app copies it into its
  own folder, works out which game it is, and builds it.
- **A games folder on this device:** pick the folder you keep your dumps in.
  Every dump in it (and up to four folders down) is listed as **Build
  needed**, and you build each one from its page. **Scan now** on the Device
  page finds dumps you add later.

A build runs in the background with a notification, so you can leave the app
while it works. It goes through these steps:

1. copying the game,
2. collecting the shaders the game's 3D models need, so they can be built
   before you play instead of during,
3. recompiling the game's code into C and compiling it (most of the time),
4. installing it.

When it's done, the game's page opens. If a build fails, the message says
why, and the full log is in
`Android/data/io.github.almondnative/files/build-log.txt`.

## 3. Play

Tap a game to play it. Touch controls cover everything, and a gamepad works
too: the touch controls hide while you use one and come back when you touch
the screen.

- The on-screen **Menu** button, or **Back**, opens the menu.
- **Controls > Edit touch controls** in the menu lets you drag the buttons
  and sticks where you want them and make them bigger or smaller, separately
  for holding the device upright and sideways.
- **Device**, at the top right of the app, sets the orientation, touch
  controls, aspect ratio, resolution and smooth scaling for every game, and
  on a handheld with two screens (AYN Thor, RG DS) whether the bottom screen
  goes on the second one.
- Long-press a game to put it on your home screen, with launchers that take
  shortcuts, or to start it with its menu open.

**The first start of each game is slower.** It builds the GPU programs
(shaders) the game needs before it starts. The next start reuses them. A
shader first used mid-play is built then, and what it draws can flicker in
for a moment.

**Quit from the menu** rather than force-stopping the app. That way the app
learns which shaders that run used, and the next start builds them before
the game wants them. On a game's page:

- **Rebuild shaders** clears the stored copies and builds every shader the
  game is known to use, then says how many it built.
- **Remove shaders** forgets what was learned, for a fresh first start.

### The menu

The menu has a line of help for each setting at the bottom. The ones worth
knowing first:

- **Performance > Frame cap:** 30 fps by default, which is what most 3DS
  games run at. 60 is there for the games made for 60.
- **Display > Layout:** both screens, one big screen, side by side and more.
- **This game:** options for that game, where it has any.
- **Advanced settings > Most compatible:** for a game that crashes or looks
  wrong, then **Restart game**. If that fixes it, the setting that broke it
  is one of the ones it turned off.

The menu shows which version you're running, at the top.

## A different GPU driver (advanced)

On a phone with an Adreno GPU, the app can draw with Mesa's Zink on Turnip
instead of the phone's own driver. Name the libraries in
`files/config/driver.txt` in the app's own storage. When that file names
none, or they won't load, the phone's own driver is used.

## Games built on a PC

Pocket Native's PC kit (in `kit/` and `scripts/`, see
[the upstream guide](https://github.com/whovian64/pocket-native/blob/main/START_HERE.md))
builds games in a container on Windows or Linux, and also handles encrypted
dumps with your console's `aes_keys.txt`. Copy the folder it makes onto the
device, then in the app tap **Add game > A games folder on this device**
and pick it. Built games in that folder are added as they are.

## If something goes wrong

**"That file is not a game this app can build: the dump is encrypted".**
Decrypt it on your console with GodMode9 (**Decrypt file**), or build it
with the PC kit and your `aes_keys.txt`.

**"There is not enough free space for this game".** Free some space, or put
in a memory card: the app keeps games on the storage with the most room.

**The build stops partway.** Look at the end of `build-log.txt` (path
above). Android may stop a long build on a phone low on memory. Close other
apps and build again.

**A black screen for minutes on the first start.** Usually that's the
shaders being built, see step 3. If it never comes up, look in the game's
`data/log.txt`, in
`Android/data/io.github.almondnative/files/games/<title id>/`.

**A game crashes, freezes or looks wrong.** Try **Advanced settings > Most
compatible** and **Restart game** first. The
[compatibility list](docs/compatibility.md) says what's known for each game.

**A game crashes and leaves a `panic.txt`.** It's in the game's `data/`
folder. Please send it with your report.

## Reporting a problem

Open an [issue](https://github.com/manu0623/Almond-Native/issues) and say:

- the device and its Android version
- the game, and which region
- the Almond Native version, from the top of the menu
- the game's `data/log.txt`, and `build-log.txt` if the build failed
