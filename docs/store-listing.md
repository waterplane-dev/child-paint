# Play Store listing — Little Painter

The listing text and graphics live in `fastlane/metadata/android/en-US/` and are the
source of truth: `title.txt`, `short_description.txt`, `full_description.txt` (plain text, no
HTML, Play does not render it reliably), `changelogs/<versionCode>.txt` and `images/`
(regenerate the graphics with `scripts/store/play-assets.sh`). Paste from those files into
Play Console; do not keep a second copy of the text here.

## Console answers

- Category: **Art & Design**. Contact email: the one in `docs/privacy-policy.md`.
- Privacy policy URL: `https://waterplane-dev.github.io/child-paint/privacy-policy`.
- Target audience: ages 5 and under (3-6 app). This triggers the Families Policy.
- Ads: none. App access: no login, nothing special to demonstrate.
- Data safety: no data collected, none shared. The app has no INTERNET permission and no SDKs;
  Auto Backup and device transfer are switched off (see the privacy policy).
- Content rating (IARC): no violence, sexual content, bad language, controlled substances,
  user-generated content sharing or purchases. Expected: Everyone / PEGI 3.
