# Privacy Policy — Little Painter

Effective date: 2026-10-21

Little Painter (package `io.github.johanneshoh.childpaint`) is a shape and
drawing app for children. This policy is short because the app does very
little with data: it has no internet access and nothing to send anywhere.

## Summary

- The app has **no INTERNET permission** and cannot make network requests.
- No data is collected, transmitted, sold, or shared with anyone, including
  the developer.
- No ads, no analytics, no crash reporting, no third-party SDKs. The app's
  only dependencies are AndroidX (`core-ktx`, `activity`, `appcompat`, `constraintlayout`)
  and Material Components — libraries, not services, and none of them phone
  home.
- No accounts, no sign-in, no user-generated-content sharing of any kind.
- The only permissions are VIBRATE, for the optional buzz when a kid-mode
  session runs out, and on Android 9 and below WRITE_EXTERNAL_STORAGE, for
  saving a picture to the gallery.

## Where drawings are stored

The current drawing is autosaved to the app's private internal storage
(`filesDir/canvas.png`), a location only this app can read. It is not
readable by other apps and is not uploaded anywhere.

If a parent taps the save button, the app writes a PNG of the drawing to the
device's own gallery, under `Pictures/Little Painter`, using the standard Android
MediaStore (or, on Android 9 and below, the legacy public Pictures directory
via the `WRITE_EXTERNAL_STORAGE` permission, which the app requests only on
those older versions and only for this purpose). That file stays on the
device; the app has no means of transmitting it elsewhere.

## Backup and transfer

The app opts out of Android's Auto Backup (`allowBackup="false"`) and excludes
all of its data from both cloud backup and device-to-device transfer. Drawings,
saved pictures, the kid-mode session and the PIN are therefore never copied to
a Google account or to a new phone by Android. A phone set up from another one
starts with an empty Little Painter. The one exception is a picture a parent
saved to the gallery with the save button: that is an ordinary file in the
Pictures folder, which the phone's own gallery or photo-backup app may handle
like any other photo, under that app's own settings and not under this app's
control.

## Photos

A parent can optionally switch on "Paint on a photo", which opens the Android
system photo picker. The app never receives access to the gallery as a whole:
the picker runs outside the app, it requests no photo permission, and the app
is handed only the single image the parent chose. That image is drawn onto the
current page and from then on is part of the drawing, stored exactly like any
other drawing — in the app's private internal storage, and in the gallery only
if a parent taps save. It is not transmitted anywhere, because the app has no
means of transmitting anything.

The feature is off until a grown-up turns it on, and its button is hidden while
kid mode is running.

## Parental controls (kid mode)

Kid mode lets a parent hand the phone to a child for a limited, supervised
session. The optional 4-digit unlock PIN is stored in the app's private
SharedPreferences, on-device only. Like everything else the app
stores, it is excluded from Android's automatic cloud backup and from
device-to-device transfer (see "Backup and transfer" below).

## Children's privacy

Little Painter is directed at children. It collects no personal information,
and no information of any kind, from anyone — including children — so there
is nothing about a child to disclose, share, or delete. Because no personal
information is collected, no parental consent is required under COPPA or the
GDPR's child-consent rules.

A parent who wants to remove all app data can do either of the following at
any time:

- Uninstall the app, which deletes the drawing, the kid-mode session, and the
  PIN along with the app itself; or
- Clear the app's storage from Android Settings (Settings → Apps →
  Little Painter → Storage & cache → Clear storage), which removes the same
  data without uninstalling.

## Contact

Responsible for this app: Johannes Hoh. Questions about this policy and any
request about your data: johanneshoh@googlemail.com

## Canonical version

The canonical copy of this policy lives in the app's source repository at
`docs/privacy-policy.md`. The version published at
https://waterplane-dev.github.io/child-paint/privacy-policy is generated from
that file.
