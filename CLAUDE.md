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
./gradlew testDebugUnitTest      # JVM unit tests (Robolectric: OpCodec, History, KidModeState, FloodFill)
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

- `PaintCanvasView.kt` — the paper: bitmap, checkpoints, touch handling, live
  preview of the gesture in flight.
- `History.kt` — the op list and redo stack, which ops are hidden (relocated or
  held), and whether a snapshot is still a usable replay start. No View, no
  bitmaps, so it is unit-tested; new history rules go here, not in the view.
- `DrawOps.kt` — `Tool`/`ShapeKind`/`ShapeStyle` enums and the `DrawOp` types,
  plus the shape path maths (star, heart, triangle, and the opt-in dress-up
  shapes).
- `ObjectPicker.kt` — the hand tool's brain: which op is under a finger, and
  what later ops sit on it and have to come along when it is picked up.
- `OpCodec.kt` — the op list to JSON and back, so objects survive a restart.
  The file outlives the code that wrote it: read its compatibility rules before
  changing an op. An op it cannot read becomes an `UnknownOp` that keeps its
  slot (`RelocateOp.hidden` is positional) and is written back verbatim.
- `AtomicWrite.kt` — every file the app keeps goes through `writeAtomically`
  (temp file, fsync, rename). A kill is the normal exit from kid mode.
- `PictureStore.kt` — "My pictures": saved drawings kept in `filesDir/pictures/`
  as op lists, so an old picture opens with its things still pickable.
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
  any screen width), owns colour/tool selection, the keyboard for text, autosave, and
  save-to-gallery.

Every toolbar glyph is a vector drawable in `res/drawable/ic_*.xml`, tinted with
one colour. They used to be emoji, which rendered as a mix of full-colour glyphs
(bucket, sponge, floppy) and monochrome symbols (□ ○ △) — the set never read as
one toolbar. Do not reintroduce emoji here. Strokes are 2.5 units on a 24 grid:
the 2-unit originals read as hairlines once the buttons got chunky.

## Look and feel

The chrome is one design system, and all of it is built from the tokens in
`res/values/colors.xml`: a cream tray, warm near-black ink, one orange accent.
Nothing in the UI uses a literal colour that is not one of those (the canvas
palette and the knobs are drawing colours, not chrome).

- `KidChrome.kt` is the one button. A control is a *face on a lip* — a rounded
  face standing on a 4 dp darker band, like a key on a toy keyboard. Pressing
  sinks the face onto the lip (`state_pressed` in the `StateListDrawable`) and
  `squishOnPress()` scales the view down and springs it back with overshoot,
  ticking the haptics on the way down. The selected tool wears the accent face
  with a white glyph (`setSelectedLook`). Do not add a second button style.
- A colour swatch is a `SwatchDrawable`: the colour on its own darker lip, with a
  gloss, and a double ring (ink outside, white inside, so it reads on the black
  swatch and the white one) when chosen. The chosen one also rests at 1.2× and
  one dp higher in Z, so it overlaps its neighbours rather than the other way
  round. That is why every row is `clipChildren = false`.
- The size control is `BrushSizeSlider`, not a `SeekBar`: a wedge from thin to
  fat in the colour that will come out of the brush, with a thumb that shows
  the size as a dot. The wedge is painted in the eraser's pink when the eraser
  is the tool. The quadratic dot size matches `progressToWidth`.
- Hints go through `toast()` in the activity, which is no longer a system toast
  but a dark pill (`hintPill`) that drops in at the top of the paper and fades.
  One at a time; a new one replaces whatever is up. Keep calling `toast()`.
- The drawer and My pictures are `BottomSheetDialog`s built by `kidSheet()`:
  a title, a close button, the content, opened fully (`STATE_EXPANDED`,
  `skipCollapsed`) so a tall grid never arrives half-hidden behind a peek.
- Dialogs are `MaterialAlertDialogBuilder`, themed in `values/themes.xml`: the
  positive button is a filled accent pill, the rest plain text, so the one to
  press is the one that looks like a button. The parent's dialogs use Material
  chips, check boxes and outlined text fields; the error shows under the field.
- The typeface is Fredoka (`res/font/`, OFL — notice in `docs/fonts/`), applied
  through the theme to every text and, via `captionTypeface` in `DrawOps.kt`,
  to the text tool. The canvas installs it once in its `init`. **Changing the
  font changes how a saved `TextOp` wraps**: the op stores the size it was
  fitted at, in the old font's metrics, so an old caption may wrap differently.
  That was accepted once, for this font; do not change the font casually.
- The launcher icon is a vector (`ic_launcher_foreground` + `_monochrome`) and
  the Android 12+ splash takes the same sky from `values-v31/themes.xml`. The
  three theme files (`values`, `-v27`, `-v31`) share `Base.Theme.ChildPaint`;
  each per-API file repeats the attributes of the one below it, because a
  qualifier replaces the style, it does not extend it.

## Things that will bite you

- The activity is **locked to portrait**. Op coordinates are stored in canvas
  pixels, so a size change would misplace every replayed op. Unlocking rotation
  means normalising those coordinates first.
- `restoreSession()` puts back the saved op list (`canvas-ops.json`) over its
  photo (`paper-<id>.png`), and falls back to the flat `canvas.png` as a
  `basePicture` when the ops cannot be used. Ops are canvas pixels, so they are
  only installed once the paper is laid out at the *width* they were saved at
  (`fitsPaper`; height may differ — that is just the toolbar extra); until
  then (and for good after the first touch) the flat picture stands in, and
  `saveSession()` returns null so the stand-in never overwrites the saved ops.
  Undo walks back to `basePicture`, not to a blank page — that is intended.
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
  `onSizeChanged` and the `tool` setter. `Live` has four subclasses —
  `LiveShape`, `LiveSelection`, `LiveText`, `LiveGroup` — sharing one drag box
  so one finger drags and two pinch and twist any of them.
- The hand's rectangle (`Tool.SELECT` dragged on empty paper) lifts pixels but
  **cuts nothing** until the selection settles: the pixels stay in the bitmap and the
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
- Text is a box, and the **font size is an output**. The child draws the
  rectangle; `LiveText.reflow()` sets the size to the largest at which the words
  fit it (`fitTextSize`: wrapped to the width, inside the height, no word broken)
  and `drawTextBox` centres the block in it. So typing more makes letters
  smaller, the size knob and a pinch resize the box and the words follow, and the
  size slider does not touch text. `TextOp` still stores the size it was fitted
  at, so replay never refits.
- There is **no text dialog**. Typing goes to an invisible `textInput` field in
  the activity layout, and every change is handed to `typeText()`, which refits
  the live box on the paper; the done key is `finishTyping()`. `live`'s setter
  ends the typing (`onTextEditEnded` puts the keyboard away) whenever the box
  stops being the live thing, so no path — settle, undo, bin, new page — leaves a
  keyboard up for a box that is gone. The keyboard must never resize the paper
  (op coordinates are canvas pixels): on API 30+ the window is `adjustResize`
  under edge-to-edge, which does not resize but reports the IME inset, and
  `keepTypingInView()` *translates* the paper column up so the box clears it;
  below 30 it is `adjustNothing`.
- Every live thing has **four knobs on strings** — move (hangs below), size
  (off the corner), turn (above), and a red bin (off the opposite corner) —
  whatever its size. The bin acts on lift, and only if the finger is still on it.
  Binning a thing that was just made first records it and then relocates it to
  nowhere (`RelocateOp(…, null)`), so one undo brings it back; a selection is
  binned as an `EraseOp` of the pixels it lifted. They exist because a
  four-year-old's fingertip covers a small shape completely: the finger holds the
  knob, and the thing stays visible. They duplicate body-drag and pinch on
  purpose; do not remove them as redundant. Knobs work off finger *deltas*, so a
  knob pulled back onto the paper at an edge, or swung round to clear another
  knob (`knobPlaces`), still moves the thing exactly as far as the finger moved.
- Dragging out a shape, text box or selection hangs the pulled corner a
  string's length **above** the finger (`dragCorner`), easing in over the first
  few millimetres so it does not jump on touch-down. A plain tap with a shape
  tool stamps a 3 cm shape.
- The hand (`Tool.SELECT`) picks up what is already placed. `ObjectPicker`
  finds the topmost op under the finger and carries every later op mostly inside
  it; a later bucket fill is carried only if, re-run on the transparent carry
  layer, it stays off the layer's edge — one that escapes was a background fill.
  Several things travel as one `GroupOp` (a rendered layer, so a carried fill
  cannot flood into what it lands on); one brush line goes back as a remapped
  `StrokeOp`, one shape or text as itself. A later `MoveOp` over it, or a
  `ClearOp`, makes it unpickable — the hand then falls back to the rectangle.
  A tap on empty paper that puts a held thing down does only that.
- A pick-up does not delete anything. The originals stay in `ops`; the held
  `Live` lists them in `hidden`, and settling records a `RelocateOp(hidden,
  content)` that replay honours by skipping them. Undo pops the relocation and
  the originals reappear. This is why checkpoints go through
  `checkpointUsable()` before replay: a snapshot that already has a now-hidden op
  painted in is the wrong starting point. A pick-up nobody moved records nothing.
- Tapping a live text without moving brings the keyboard back for it. There is
  nowhere else a child would find "edit this". An empty box is thrown away when
  it settles or on done — recorded as a deletion if the hand had picked it up —
  and undo on an empty fresh box just removes the box.
- The save button opens **My pictures**: "Save this picture" (gallery PNG *and*
  an editable copy) plus a tile per kept picture. Opening one first puts the
  current paper away — under its own picture, or as a new one — unless it is
  blank, so opening never costs a drawing. `currentPictureId` (persisted) makes a
  re-save update the picture it came from; clear, plain paper and a new photo
  drop it, so a new page never overwrites the one it replaced. A store save with
  no session (ops still waiting for their paper size) leaves the stored ops alone.
  Hidden in kid mode along with the save button.
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
- `replayAll()` never stretches `basePicture`. At the paper's width (the normal
  case, and the toolbar extra, which only changes height) it is drawn 1:1 from
  the top-left, where the ops drawn over it expect it; shrinking it to fit the
  new height slid the photo out from under them. Any other width is scaled to
  *fit*, because stretching would squash a face.
- Autosave hands the IO thread an unencoded `OpCodec.Session`; `OpCodec.encode`
  must not run on the UI thread (the JSON grows with every stroke point). Ops are
  immutable, which is what makes handing them across safe — keep them so.
- The expiry alert is fired from the one-second tick and guarded by a persisted
  `KEY_ALERTED`. Without the flag it re-fires every second while the overlay is
  up; without *persisting* it, a relaunch after time is up sets it off again.
  The ringtone is held so unlock can silence it, and it stops itself after
  `ALARM_MS` regardless.
- Autosave writes `filesDir/canvas.png` (plus `canvas-ops.json`, and the photo
  once as `paper-<id>.png`) ~1.2 s after each finished op **and** on `onStop`. The debounced write is not belt-and-braces: kid mode blocks Home and
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
