# VortexDroid

VortexDroid runs the [Vortex](https://playvortex.io) Linux client on Android.
It is a fork of [PolyDroid 2](https://github.com/cetotos/PolyDroid2) dedicated to Vortex,
using the same Box64-based approach to translate the x86-64 client to ARM64.

> [!NOTE]
> This is **not** an official client. Expect bugs. Vortex itself is evolving quickly.

## How it works

1. The Vortex Linux client (an x86-64 AppImage) is bundled with the app and
   extracted on first launch.
2. [Box64](https://github.com/ptitSeb/box64) translates the x86-64 client to ARM64.
3. Graphics run through Vulkan with [Turnip/Freedreno](https://www.mesa3d.org/) (or the
   system driver), presented on an Android Surface. The windowing/input layer uses
   a bundled X11 server (Termux:X11/Lorie based) with touch -> mouse/keyboard emulation.
4. Audio is bridged from ALSA to Android audio.

## Entering a game

1. Open the app once so the rootfs and client finish extracting.
2. Tap **Vortex** to open https://playvortex.io, sign in, and press **Play** on a game.
3. The `vortex://` deep link opens VortexDroid, which starts the client with the
   launch URL. The client verifies the launch, authenticates, and joins the game server.

## Building

```sh
# 1. get large assets (rootfs + Vortex AppImage)
#    place Vortex-linux-x86_64.AppImage in the repo root or parent dir first
./scripts/fetch-assets.sh

# 2. build with Android Studio or
./gradlew assembleDebug
```

Requires the Android SDK; native parts build via CMake/NDK (arm64-v8a only).

## Credits

- [PolyDroid 2](https://github.com/cetotos/PolyDroid2) by cetotos — the base this fork is built on
- Box64, Mesa/Turnip, Termux:X11 — see the PolyDroid 2 README for the full list
- [Vortex](https://playvortex.io) — the game platform (this project is not affiliated with it)
