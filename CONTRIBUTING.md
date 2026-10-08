# Contributing

Little Painter is a small app kept by one person in spare time. Issues and
small pull requests are welcome; big ones are best discussed in an issue first,
because the app says no to most features on purpose.

## What fits

The app is for a child of about three to six on a phone. Every control is on
screen at once, with no menus or scrolling, because a child should never have
to discover a hidden control. So:

- **Bug reports** — always welcome. Say which phone and Android version, and
  what the child (or you) did just before.
- **Translations** — welcome. Strings are in
  `app/src/main/res/values/strings.xml`.
- **New tools or settings** — open an issue first. A new button costs every
  child a little attention, so the bar is high.

Not wanted: ads, analytics, network access, accounts, or anything that sends
data off the phone. The privacy policy promises none of that.

## Before you code

Read [CLAUDE.md](CLAUDE.md), the "Things that will bite you" section in
particular. It is the short list of decisions that look wrong until you know
why they are there — for example, the activity is locked to portrait because
drawing operations are stored in canvas pixels, and the paint bucket matches
within a colour tolerance rather than exactly.

What the app is expected to do, item by item, is in
[docs/requirements/](docs/requirements/README.md).

## Building and testing

```
./gradlew assembleDebug testDebugUnitTest lint
```

Needs JDK 17–21 and Android SDK platform 36. Try a change on a real phone if
you can; touch handling is hard to judge on an emulator.

## Licence

Contributions are accepted under the project's licence, Apache-2.0. No CLA.
