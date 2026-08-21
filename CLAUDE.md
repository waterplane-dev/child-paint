# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in **child-paint**.

## What this is

An Android drawing app for a small child on a phone: freehand brush, shape
stamps (line, rectangle, oval, triangle, star, heart), a paint-bucket fill, and
a text tool. The one design choice that explains the rest: **everything is
composited into a single opaque `Bitmap`, and history is a replayable list of
`DrawOp`s layered on top of it.** The bitmap exists because the paint bucket
needs real pixels to flood into; the op list exists because keeping bitmap
snapshots for undo would cost ~8 MB per step. Undo therefore means "clear the
paper, replay every op", which costs a few milliseconds and no memory.

The UI is deliberately flat and scroll-free — every colour, tool, and action is
on screen at once, because a child should never have to discover a hidden
control.

## Commands

```
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK, signed with the debug key
./gradlew installDebug           # build + install on the attached device
```

Builds need JDK 17+ (`JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21-full.jdk/Contents/Home`
works here; the Homebrew JDK 25 on PATH is too new for AGP 8.7).

## Layout

- `PaintCanvasView.kt` — the paper: bitmap, op list, touch handling, live preview
  of the gesture in flight.
- `DrawOps.kt` — `Tool`/`ShapeKind` enums and the `DrawOp` types, plus the shape
  path maths (star, heart, triangle).
- `FloodFill.kt` — scanline paint bucket. Matches within a colour tolerance on
  purpose: strokes are anti-aliased, so an exact-match fill would stop at the
  first blended pixel and leave a halo.
- `MainActivity.kt` — builds the toolbar in code (weighted grid rows, so it fits
  any screen width), owns colour/tool selection, the text dialog, autosave, and
  save-to-gallery.

## Things that will bite you

- The activity is **locked to portrait**. Op coordinates are stored in canvas
  pixels, so a size change would misplace every replayed op. Unlocking rotation
  means normalising those coordinates first.
- `restorePicture()` installs a `basePicture` that replays *underneath* the op
  list. Undo walks back to that picture, not to a blank page — that is intended.
- Autosave writes `filesDir/canvas.png` on every `onStop`. It is separate from
  the 💾 button, which exports to the gallery via MediaStore.

## Working from a phone (USMC)

This repo is driven remotely from a phone, so three paths are load-bearing:

- **`.claude/outbox/`** — put anything a human should LOOK at here: screenshots,
  reports, diffs, build artifacts. It is the only place the phone gallery's
  "outbox" tab scans, and a new file there notifies the phone with a download
  link. Prefer writing a file over printing a wall of terminal output — long
  output is unreadable on a phone.
- **`.claude/inbox/`** — images uploaded from the phone land here; prompts
  reference them by path.
- **`.usmc/`** — per-repo state (backlog items, build commands, stars/pins),
  written by the daemon. Don't hand-edit.

All three are gitignored.
