# Matrix Code

A Matrix-style falling-code screensaver for Android, with live controls for colour,
speed, density and a pile of other knobs.

<p align="center">
  <img src="docs/preview-green.png" width="30%" alt="Matrix Green" />
  <img src="docs/preview-amber.png" width="30%" alt="Amber CRT, clock on" />
  <img src="docs/preview-rainbow.png" width="30%" alt="Rainbow" />
</p>

## Install

Grab **`dist/MatrixCode.apk`** from this repo, copy it to your phone and tap it.

Android will ask you to allow installing from unknown sources the first time —
that's expected for an APK that didn't come from the Play Store. Or install over
USB:

```
adb install -r dist/MatrixCode.apk
```

The APK is signed with the standard Android debug key, so it installs on any
device without further setup. Minimum Android 5.0 (API 21).

## Using it

Open **Matrix Code**. The top of the screen is a live preview — every control
updates it instantly, so you can dial in the look before committing.

`▶ START SCREENSAVER` takes it fullscreen (immersive, no status bar). Tap
anywhere or press back to come out.

There are two other ways to run it:

- **SET AS SYSTEM SCREENSAVER** registers it as an Android *daydream*, so the
  system starts it on its own while the device is docked or charging. The button
  opens the right settings page; pick "Matrix Code Rain" there.
- **SET AS LIVE WALLPAPER** puts the rain behind your home screen. It runs at
  40fps instead of 60 to stay easy on the battery, and picks up settings changes
  the moment you make them.

## Options

**Appearance**

| Option | Range | What it does |
| --- | --- | --- |
| Color | 8 themes | Matrix Green, Amber CRT, Ice Cyan, Red Pill, Neon Violet, Gold, Ghost White, Rainbow (hue sweeps across the columns and drifts over time) |
| Character set | 6 sets | Katakana, Binary, Hexadecimal, ASCII, Symbols, Mixed |
| Glyph size | 10–36 dp | Bigger glyphs mean fewer, wider columns |
| Column density | 20–100% | How many columns are raining at once |

**Motion**

| Option | Range | What it does |
| --- | --- | --- |
| Fall speed | 0.1x–4.0x | Multiplier on the fall rate |
| Trail length | 0.2x–2.0x | How far the fading tail stretches behind each head |
| Glyph churn | 0–100% | How often glyphs mutate in place while falling |

**Effects**

| Option | Range | What it does |
| --- | --- | --- |
| Glow | 0–100% | Bloom halo around the leading glyph |
| CRT scanlines | 0–100% | Horizontal scanline overlay |
| Glitch | 0–100% | Chance of a column blinking out for a beat |

**Screensaver**

Show clock (with a 12/24-hour toggle) and keep-screen-awake.

`RANDOMIZE` rolls every visual setting at once — good for finding looks you
wouldn't have dialled in by hand. `RESET` returns the visuals to defaults but
leaves your clock and screen-awake preferences alone.

Settings are saved as you change them and shared by all three modes.

## How it works

`MatrixRenderer` owns the whole simulation and knows nothing about Android's
view or service plumbing, which is why the fullscreen activity, the daydream,
the live wallpaper and the settings preview can all share one engine.

Each column tracks a fractional head position, its own fall speed, a trail
length and a depth plane. Columns are assigned one of three depths; far ones are
slower and dimmer, which reads as parallax. Rather than fading the whole frame
with a translucent black rect (the usual trick, which smears), every glyph in a
trail is drawn explicitly with its own alpha — that keeps the trail crisp and
makes trail length a real, controllable parameter.

`RenderLoop` is a plain render thread that drives the renderer onto a
`SurfaceHolder`, using `lockHardwareCanvas` where available.

## Building

Needs JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # renderer + UI tests
```

The tests render real frames off-device through Robolectric's native graphics
mode and check them for blank output, rain reaching the full height, and the
right colour dominating per theme. Frames are written to `app/build/frames/` so
you can eyeball changes to the look without deploying to a phone.
