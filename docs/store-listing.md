# Play Store Listing — Little Painter

Working draft to paste into Play Console. Not published anywhere itself.

## App name

Little Painter

## Short description

Limit: 80 characters.

```
A shape painter for children: freehand, shapes, fill, text, and kid mode.
```

Character count: 73 (fits the 80-character limit).

## Full description

Limit: 4000 characters.

```
Little Painter is a shape painter for children, built for a phone.

WHAT IT DOES

- Draw: a freehand brush with a size slider, and an eraser.
- Shapes: line, rectangle, oval, triangle, star, heart. Drag to size them,
  then nudge, resize or turn the shape until it sits right — it stays
  adjustable until you touch the paper somewhere else. A button cycles
  filled, filled with a rim, and outline. Filled is the default.
- Fill: a paint bucket that floods any enclosed area, including areas
  enclosed by a wobbly freehand line.
- Text: tap the paper, type a word, and it lands where you tapped in the
  current colour and size.
- 16 colours, unlimited undo and redo, and a save button that puts a copy of
  the picture in the phone's own gallery.

The drawing is kept automatically between sessions, so closing the app never
loses it.

GROWN-UP EXTRAS (OFF BY DEFAULT)

Holding the padlock in parent mode opens a small panel with two optional
features, both off until a grown-up switches them on:

- Paint on a photo: pick a picture and it becomes the page to draw on. It
  uses the Android system photo picker, so the app never gets access to the
  gallery — only the one photo that was chosen. The button is hidden while
  kid mode is running, so the photo is picked before the phone is handed
  over. The same button offers "Plain paper" to go back to a blank page.
- Dress-up shapes: glasses, moustache, hair, hat, umbrella, bow tie and
  crown, sized by dragging like every other shape. Made for drawing on a
  photo of a face.
- Hide the tools: the toolbar disappears and the paper fills the screen. A
  handle at the bottom edge brings it back until the paper is touched
  again.

PARENT MODE AND KID MODE

The app opens in parent mode: the full toolbar, plus a padlock button that
hands the phone to a child for a supervised session.

Kid mode gives the child one canvas and no saving — the save button is
hidden and nothing is written to the gallery while it is active. The parent
sets a time limit from 5 minutes to 2 hours, a custom number of minutes, or
no limit at all; the time-left bar turns orange with 5 minutes left and red
with 1 minute left. When time runs out, an overlay blocks the tools until a
grown-up unlocks it. The drawing itself is never deleted.

When the time runs out the phone can buzz, play an alarm, or do neither —
set when kid mode starts. The alarm stops itself after a few seconds.

Getting back to parent mode means holding the padlock, which can ask a
simple maths question and a 4-digit PIN — both optional, set when kid mode
starts.

HONEST NOTES ON KID MODE

Kid mode uses Android's own screen-pinning feature to keep the child inside
the app. This app is not a device owner, so:

- The first time kid mode starts, Android itself asks the parent to confirm
  pinning. On some phones, screen pinning must first be turned on in
  Settings.
- A child who has learned to swipe up and hold can unpin the screen. Android
  can be set to require the phone's lock-screen PIN before allowing that
  (Settings → Security → Screen pinning → "Ask for PIN before unpinning") —
  worth turning on if it matters to you.

Even so, unpinning only reaches the home screen. The kid-mode session is
stored on the device, not just in memory, so reopening Little Painter drops
straight back into kid mode with the same timer still counting down from
before. Force-stopping the app, or restarting the phone, does not reset the
clock either.

PRIVACY

Little Painter has no internet permission and cannot make network requests.
It collects nothing, sends nothing anywhere, and has no ads, no analytics,
and no accounts. Drawings stay on the device unless a parent taps save,
which writes a PNG into the phone's own gallery. A photo chosen for the
"paint on a photo" feature is handed over by the system picker one image at
a time and never leaves the device either. See the full privacy policy for
details.
```

## Play Console declarations (drafted answers)

### Target audience & content

- Target audience: Children (this triggers the Play Families Policy — see
  the release checklist for the consequences).

### IARC content rating questionnaire

Expected answers, given the app has no violence, no user-generated content,
no ads, and no data collection:

- Violence: none.
- Sexual content: none.
- Language: none.
- Controlled substances: none.
- User-generated content / user interaction: none (no sharing, no chat, no
  accounts).
- Purchases: none.
- Expected result: **Everyone** (ESRB) / **PEGI 3**.

### Data safety form

- Does your app collect or share any of the required user data types? **No.**
  (The optional photo feature reads one picker-selected image into the
  drawing on-device. Play's Data safety form covers collection and sharing —
  transmission off the device — and nothing is transmitted; the app has no
  INTERNET permission. No photo permission is declared, because the system
  photo picker does not need one.)
- Data collected: none.
- Data shared: none.
- Security practices: not applicable (nothing is collected, so encryption
  in transit / deletion request questions do not apply — answer "No data
  collected" where the form offers that option).

### Ads

- Does your app contain ads? **No.**

### App access

- All functionality is available without special access: **no login
  required**, no restricted content, nothing to demonstrate to a reviewer
  beyond installing and opening the app.

### Privacy policy URL

```
https://waterplane-dev.github.io/child-paint/privacy-policy
```

(Only resolves once the repository is public and GitHub Pages is serving
`/docs` — see the release checklist.)

## TODO — graphic assets (cannot be generated in-repo)

These need to be produced outside the repository (design tool or an
image-generation pass) before the listing can go live:

- [ ] 512x512 PNG app icon (Play Store listing icon; distinct from the
      launcher `mipmap` set already in the app).
- [ ] 1024x500 PNG or JPG feature graphic.
- [ ] At least 2 phone screenshots. `docs/screenshot.png` exists in the repo
      and is a usable starting point, but Play wants device-frame-accurate
      captures — take fresh screenshots from a real build for the final
      listing.
