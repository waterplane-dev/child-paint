# Release Checklist — Little Painter

A manual, ordered checklist for the first public release. Work top to
bottom; later steps depend on earlier ones.

## Decisions to confirm before anything is public

- [ ] **The contact email `johanneshoh@googlemail.com` will be publicly
      visible**, both in the repo (this file, `docs/privacy-policy.md`) and in the Play Store listing (Data safety
      form, and wherever Play surfaces developer contact details). If that
      is not wanted, swap it for a forwarding alias **now**, before the repo
      goes public or anything is submitted to Play, and update it in every
      file above.
- [ ] Confirm the GitHub owner/repo (`waterplane-dev/child-paint`) is the one
      to make public — there is no undo for history already pushed to a
      public repo.
- [ ] Confirm `io.github.johanneshoh.childpaint` is the applicationId to
      ship. Once an app is published under an applicationId on Play, it
      cannot be changed for that listing.

## 1. Verify the release build

R8 minification and resource shrinking are newly enabled for release
builds, and this is the first release build made with real signing. Do not
skip this step — a debug build passing is not evidence a minified release
build works, since R8 can strip or rename code that reflection, view
binding, or serialization depend on.

- [ ] Build and install the release build on a real device (not an
      emulator, not `assembleDebug`):
      ```
      ./gradlew installRelease
      ```
      (Requires step 2's signing to be set up first, or install-time
      signature checks may reject it depending on device state — otherwise
      use `assembleRelease` and `adb install` the APK from
      `app/build/outputs/apk/release/`.)
- [ ] Exercise every tool at least once: freehand brush, eraser, each shape
      (line, rectangle, oval, triangle, star, heart) in both outline and
      filled mode, the paint bucket, the text tool, undo/redo, and save to
      gallery.
- [ ] Run one full kid-mode session: start kid mode with a short time limit,
      let it run out and confirm the time-up overlay appears, then unlock
      using the full flow (maths question and PIN, if enabled) and confirm
      it returns to parent mode with the drawing intact.
- [ ] Confirm autosave survives a force-stop (kill the app from Android's
      recent-apps/force-stop, reopen, confirm the drawing and any active
      kid-mode session are both restored).

## 2. One-time signing setup

- [ ] Generate an upload keystore (only if one does not already exist):
      ```
      keytool -genkeypair -v \
        -keystore upload-keystore.jks \
        -alias little-painter-upload \
        -keyalg RSA -keysize 2048 -validity 10000
      ```
- [ ] **Losing this keystore, or its passwords, is unrecoverable** unless
      the app is enrolled in Play App Signing. That enrolment is not
      optional — every new app uploading an AAB is enrolled, and the key
      generated above is therefore an *upload* key, which Google can help
      you reset if it is lost. Store the keystore and its passwords in a
      password manager, not just on disk.
- [ ] Create `keystore.properties` at the repo root (see
      `keystore.properties.example` for the expected keys: `storeFile`,
      `storePassword`, `keyAlias`, `keyPassword`) pointing at the keystore
      from the previous step.
- [ ] Confirm `keystore.properties` and `*.jks` are gitignored (they already
      are — see `.gitignore`) so neither is ever committed.

## 3. Build the release bundle

- [ ] ```
      ./gradlew bundleRelease
      ```
      produces `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Play Console requires an **AAB (Android App Bundle)**, not an APK, for
      new app uploads — do not try to upload the release APK instead.
- [ ] `versionCode` (see `app/build.gradle.kts`) must increment on every single upload to
      Play, including test tracks; `versionName` is
      user-facing and does not need to change on every build.

## 4. Make the repository and docs public

- [ ] Make `github.com/waterplane-dev/child-paint` public.
- [ ] Enable GitHub Pages for the repo, source set to the `/docs` folder on
      the default branch.
- [ ] Confirm `https://waterplane-dev.github.io/child-paint/privacy-policy`
      resolves and renders `docs/privacy-policy.md` correctly. (This URL
      does not exist until this step is done — Play Console will reject it
      if entered earlier.)

## 5. Play Console

Work through in this order:

- [ ] Create the app in Play Console under the name **Little Painter**.
- [ ] Set **Target audience & content** to **children**. This triggers the
      **Play Families Policy** — review its requirements (no unauthorised
      third-party services, ads restrictions, no external links out of the
      app for young children, etc.) before proceeding; this app is already
      compliant by construction (no internet permission, no ads, no
      third-party SDKs), but the checklist inside Play Console must still be
      completed.
- [ ] Complete the **IARC content rating questionnaire**. Given no
      violence, no user-generated content, no ads, and no data collection,
      expect a result of **Everyone** (ESRB) / **PEGI 3**. See
      `docs/store-listing.md` for the summary of answers.
- [ ] Complete the **Data safety form**: answer that the app collects no
      data and shares no data.
- [ ] Enter the privacy policy URL from step 4.
- [ ] Complete the **ads declaration**: no ads.
- [ ] Complete **app access**: no login required, all functionality
      available without special access.
- [ ] Listing text and graphics: paste from `fastlane/metadata/android/en-US/`
      (source of truth; graphics via `scripts/store/play-assets.sh`).
- [ ] Upload the AAB from step 3 to **internal testing** to check it installs,
      then to a **closed test**. A new personal developer account must run the
      closed test with **at least 12 testers opted in for 14 continuous days**
      (per app) before it may apply for production access. Check the pre-launch
      report meanwhile.
- [ ] After day 14, apply for production access in Play Console, then promote
      to **production**.

## Known limitations to be aware of when listing

- The app is **portrait-locked** (`android:screenOrientation="portrait"` in
  the manifest). Under `targetSdk` 36, Android 16 ignores portrait/landscape
  locks on screens 600dp and wider (most tablets, and some large-screen
  foldables) — the app can appear in landscape on those devices even though
  it is not designed for it. When choosing target devices in the Play
  Console device catalogue, prefer phones and avoid tablets/large-screen
  devices until this is addressed in the app itself.
