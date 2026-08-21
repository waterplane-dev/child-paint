# Paint

A drawing app for a small child, built for a phone.

<p align="center"><img src="docs/screenshot.png" width="320" alt="The app with a filled triangle on an orange background"></p>

## What it does

- **Draw** — freehand brush with a size slider, and an eraser.
- **Shapes** — line, rectangle, oval, triangle, star, heart. Drag to size them;
  the outline/filled button switches between the two.
- **Fill** — a paint bucket that floods any enclosed area, including areas
  enclosed by a wobbly freehand line.
- **Text** — tap the paper, type a word, and it lands where you tapped in the
  current colour and size.
- 16 colours, unlimited undo/redo, and a save button that puts a PNG in
  `Pictures/Paint` in the phone's gallery.

The picture is kept between sessions automatically, so closing the app does not
lose the drawing.

## Parent mode and kid mode

The app starts in **parent mode** — the full toolbar above, plus a padlock button
that hands the phone over to the child.

**Kid mode** is for leaving a child alone with the phone:

- **One canvas, no saving.** The save button is hidden and nothing is written to the
  phone's gallery. There is one drawing, and it is kept automatically.
- **No way out.** The app is pinned to the screen, system bars are hidden, and
  Back and Home do nothing.
- **A usage timer.** The parent picks 5 minutes to 2 hours, a custom number of
  minutes, or no limit. A countdown badge turns orange at 5 minutes left and red
  at 1 minute. When it runs out, a full-screen overlay blocks the paper and the
  tools until a grown-up unlocks it — the drawing is kept, not thrown away.
- **A guarded way back.** Hold the padlock in the corner. Unlocking asks a
  multiplication question (a two-digit number times a single digit) and then a
  4-digit PIN. Either barrier can be switched off when kid mode is started.

The session is stored on disk, not in memory: force-stopping the app or rebooting
the phone comes straight back to kid mode with the same time already used up.

### What screen pinning can and cannot do

Pinning is Android's own feature and the app is not a device owner, so:

- The **first** time kid mode starts, Android asks the parent to confirm pinning.
  On some phones screen pinning must first be enabled under
  *Settings → Security → Screen pinning*.
- A child who knows to **swipe up and hold** can unpin. Android can require the
  lock-screen PIN for that ("Ask for PIN before unpinning" in the same settings
  screen) — turn it on if that matters to you.

Even then, escaping the pin only reaches the home screen: reopening the app lands
back in kid mode, with the timer still counting down from before.

## Building

```
./gradlew assembleDebug
```

Needs JDK 17+ and an Android SDK with platform 35. The resulting APK is at
`app/build/outputs/apk/debug/app-debug.apk`; `./gradlew installDebug` builds and
installs it on an attached device.

Minimum Android version is 7.0 (API 24).
