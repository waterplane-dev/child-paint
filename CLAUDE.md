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
./gradlew testDebugUnitTest      # JVM unit tests (FloodFill)
./gradlew installDebug           # build + install on the attached device
./gradlew bundleRelease          # AAB for Play -> app/build/outputs/bundle/release/
```

Builds need JDK 17+ and Android SDK platform 36. A JDK that is *too new* for the
Android Gradle Plugin fails the build, so pin one explicitly if the JDK on PATH
is a recent release.

Release builds are signed from an untracked `keystore.properties` (template:
`keystore.properties.example`). Without it the release build is left **unsigned**
on purpose — falling back to the debug key would produce an artifact that looks
shippable and that Play rejects. See `docs/release-checklist.md`.

## Layout

- `PaintCanvasView.kt` — the paper: bitmap, op list, touch handling, live preview
  of the gesture in flight.
- `DrawOps.kt` — `Tool`/`ShapeKind`/`ShapeStyle` enums and the `DrawOp` types,
  plus the shape path maths (star, heart, triangle, and the opt-in dress-up
  shapes).
- `FloodFill.kt` — scanline paint bucket. Matches within a colour tolerance on
  purpose: strokes are anti-aliased, so an exact-match fill would stop at the
  first blended pixel and leave a halo.
- `KidModeState.kt` — the unattended-child session, persisted in SharedPreferences.
- `ParentSettings.kt` — the two opt-in extras (photo paper, dress-up shapes),
  persisted the same way and off by default.
- `PhotoPaper.kt` — decodes a picked photo and letterboxes it into a
  canvas-sized bitmap.
- `KidModeDialogs.kt` — the parent's setup dialog and the guarded unlock flow.
- `SquareView.kt` — a view that is as tall as it is wide. The colour swatches are
  weight-laid-out, so a fixed height made them ellipses on most screen widths.
- `MainActivity.kt` — builds the toolbar in code (weighted grid rows, so it fits
  any screen width), owns colour/tool selection, the text dialog, autosave, and
  save-to-gallery.

Every toolbar glyph is a vector drawable in `res/drawable/ic_*.xml`, tinted with
one colour. They used to be emoji, which rendered as a mix of full-colour glyphs
(bucket, sponge, floppy) and monochrome symbols (□ ○ △) — the set never read as
one toolbar. Do not reintroduce emoji here.

## Things that will bite you

- The activity is **locked to portrait**. Op coordinates are stored in canvas
  pixels, so a size change would misplace every replayed op. Unlocking rotation
  means normalising those coordinates first.
- `restorePicture()` installs a `basePicture` that replays *underneath* the op
  list. Undo walks back to that picture, not to a blank page — that is intended.
  `setPaperPicture()` (the photo extra) does the same but clears the op list
  first: a new page is a new picture, and replaying the old ops would stamp a
  child's drawing onto a photo they have never seen.
- Clearing does **not** get rid of a photo: `ClearOp` paints the paper colour
  over it, but `basePicture` stays installed and undo walks back onto it. That is
  right for the clear button and wrong for "done with this photo", which is what
  `useBlankPaper()` is for — it drops `basePicture` and the history with it. The
  photo button offers it as "Plain paper"; without that there was no way out of a
  photo at all, which is exactly how it shipped the first time.
- `PhotoPaper` returns a bitmap that is exactly canvas-sized, letterboxed on the
  paper colour. It has to: `replayAll()` stretches `basePicture` across the whole
  view, so a photo of any other aspect ratio arrives squashed.
- The extras are reached by **holding** the lock in parent mode, where a tap
  starts kid mode. In kid mode that hold is the unlock instead, which is what
  keeps a child out of the photo picker — the gate is the mode, not a barrier.
- A finished shape does **not** go straight into the bitmap. It becomes a
  `PaintCanvasView.Live` and stays adjustable until the child touches somewhere
  else, changes tool, or undoes. Anything that reads or rewrites history has to
  settle it first — `commitLive()` is called from `undo`, `redo`, `clearPaper`,
  `onSizeChanged` and the `tool` setter. `Live` has two subclasses, `LiveShape`
  and `LiveSelection`, sharing one drag box so one finger drags and two pinch
  and twist either of them.
- The selection tool (`Tool.SELECT`) lifts a rectangle of pixels but **cuts
  nothing** until the selection settles: the pixels stay in the bitmap and the
  floating copy is painted over a paper-coloured hole at draw time. That is why
  undoing a selection is free — `discardLive()` drops it and the picture is
  already correct — and why `drawLive()` has to paint the hole in the export
  copy too, or an autosave taken mid-drag stores the piece twice.
- `MoveOp` stores no pixels. It re-reads them out of the bitmap it is handed,
  which during a replay is exactly the state it saw when committed, so the move
  reproduces itself; keeping a copy would hang a bitmap off every drag. It also
  guards `Bitmap.createBitmap` returning the *source* for a whole-bitmap subset,
  which would mean erasing and recycling the live paper.
- `exportBitmap()` is the exception: it **draws** the live shape into the
  exported copy instead of committing it. Autosave runs off `exportBitmap` every
  1.2 s, so committing there settled every shape a second after it was drawn and
  the adjustable window never opened at all. It also has to copy the bitmap
  *mutable* in that case — `Canvas` rejects an immutable one, which is a crash,
  not a warning.
- The second pointer is still ignored everywhere except on a live shape. That
  rejection is deliberate (it is the palm of the hand holding the phone); pinch
  and twist are the one place it is wanted, gated on `adjusting`.
- Undo drops a live shape rather than committing it and walking back over it, so
  `canUndo` has to count the live shape too or the button sits dimmed.
- Undo does not always mean "gone". A `MoveOp` or a `TextOp` walked back off the
  op list comes back as a **live object** — the selection is re-lifted from the
  restored bitmap, the caption is re-boxed — because both are placed by eye and
  "not quite right" is the usual reason for undoing one. Every other op is a
  plain step backwards. This is why undo cannot simply drop a live object and
  stop: an untouched restored selection records nothing, so stopping there would
  spend the press with the picture unchanged. `undo()` drops it and keeps going.
- The toolbar is nine tools plus a **"..." drawer**, which is exactly two rows of
  five. It was eleven tools and a lonely orphan row, and the answer to "there are
  too many shapes to aim at" is not a third row. `TOOLS` is what a child reaches
  for without thinking; `MORE_TOOLS` (and the dress-up extras) live in the
  drawer, and the drawer button *wears the icon of whatever was picked out of it*
  — otherwise the toolbar shows no selected tool at all while one is active.
- A selection is grown to a **two-centimetre minimum** (`MIN_SELECTION_MM`,
  measured in real millimetres via `COMPLEX_UNIT_MM`, clamped because some
  devices report a nonsense `xdpi`) and slid back inside the paper, and a plain
  tap grabs a box that size. Short drags used to be rejected outright, which is
  why selecting felt like it did nothing: a small child's drag is a smudge.
- The marquee is two strokes — a pale solid halo under dark dashes. One
  translucent grey box disappeared into a dark scribble, which is precisely the
  picture someone is most likely to be moving something around on.
- Text is a box, not a point. `TextOp` carries a `RectF` whose **width is the
  wrap width**; the bottom is an output, recomputed by `LiveText.reflow()` from
  the laid-out `StaticLayout` height. So the corner handle rewraps the text
  rather than stretching it, the size slider changes the font (and rewraps), and
  a pinch scales box and font together — deliberately, or the text would rewrap
  mid-pinch. Only `LiveText` gets a handle; shapes and selections are pinched,
  and a handle on them would be a second way to do a thing that already works.
- Tapping a live text without moving reopens the dialog on it. There is nowhere
  else a child would find "edit this", and `addText("")` on an existing box
  deletes it, which is the only way to remove text that has not settled yet.
- `Live.toOp()` may return null — a selection that was made and never moved. It
  has to leave no op behind, or every stray marquee becomes an undo step that
  changes nothing visible.
- `ShapeStyle` defaults to `FILLED`, not `OUTLINE`. `FILLED_OUTLINE` derives its
  rim from the fill (`shapeOutlineColor`) rather than taking a second colour, and
  strokes it at half the brush width: at the fat end of the size slider a
  full-width rim is wider than most shapes are tall and swallows the shape.
- Dress-up shapes are drawn from the same drag rectangle as the other shapes.
  Glasses and umbrella draw in two passes, because their wire parts (the bridge,
  the handle) are zero-area paths that vanish under `Paint.Style.FILL`.
- The toolbar is **reparented**, not rebuilt, when the hide-the-tools extra is
  switched on: it moves from `paperColumn` into `toolbarOverlay` and floats over
  the paper. It cannot simply be hidden in the column — that resizes the canvas,
  and a size change recreates the bitmap while op coordinates are canvas pixels,
  so the picture would shift under the child every time the tools opened.
- `replayAll()` scales `basePicture` to *fit*, not to fill. It is normally
  canvas-sized already (identity), but the toolbar extra changes the canvas
  height, and stretching would squash a face to the new shape.
- The expiry alert is fired from the one-second tick and guarded by a persisted
  `KEY_ALERTED`. Without the flag it re-fires every second while the overlay is
  up; without *persisting* it, a relaunch after time is up sets it off again.
  The ringtone is held so unlock can silence it, and it stops itself after
  `ALARM_MS` regardless.
- Autosave writes `filesDir/canvas.png` ~1.2 s after each finished op **and** on
  `onStop`. The debounced write is not belt-and-braces: kid mode blocks Home and
  Back, so killing the app is the only exit a child can reach, and a kill never
  runs `onStop`. It is separate from the 💾 button, which exports to the gallery.
- Kid mode lives on disk, deliberately. The activity re-applies it from
  `KidModeState` in `onStart`, so a force-stop re-enters kid mode instead of
  escaping it, and the timer is an absolute deadline rather than a countdown so
  the clock keeps running while the app is dead.
- The window is edge-to-edge (`setDecorFitsSystemWindows(false)` plus inset
  padding on the root, and `windowLayoutInDisplayCutoutMode=shortEdges` in
  `values-v27`). Both parts are needed: without the cutout mode the notch strip
  stays black once kid mode hides the system bars.
- The unlock lockout (`KidModeState.registerFailedUnlock`) is deliberately
  charged only to the **last** step of the unlock — the PIN when one is set. If a
  wrong sum counted, a child jabbing at the maths question would lock the parent
  out for five minutes at a time, which is a denial of service against the person
  the feature exists to serve.
- The lockout deadline is a persisted wall-clock instant, like the session
  deadline. Anything held only in memory is reset by killing the app, which is
  precisely the move this is meant to survive.
- `startLockTask()` is best-effort. Without device-owner provisioning Android
  shows a confirmation prompt and lets the user unpin by swiping up and holding.
  The persisted session is what actually holds the line; pinning just raises the
  bar. Do not claim the app is escape-proof.

## Publishing

`docs/` holds everything the Play submission needs: `privacy-policy.md` (served
by GitHub Pages once the repo is public — Play's Families policy makes that URL
mandatory), `store-listing.md`, and `release-checklist.md`. Claims about kid mode
in any of them must stay inside what screen pinning actually does; see the last
bullet above.

## USMC conventions

This repo is driven via USMC (remote phone control of the `claude` CLI). Follow:

- **Outbox** — save screenshots / artifacts meant for the phone into
  `.claude/outbox/` (or a top-level `.outbox/`). Only these surface in the
  phone's curated gallery; anything else is buried in the broad "this repo" tab.
- **Inbox** — files sent from the phone land in `.claude/inbox/`; read uploads
  from there.
- Both inbox and outbox are gitignored — never commit their contents.
