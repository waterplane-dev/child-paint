# Security

Little Painter has no internet permission, no accounts and no server. The parts
where a security problem could still matter are:

- **Kid mode** — a way for a child to get out of a session, or to reset the
  timer or the unlock lockout, without a grown-up. Screen pinning itself can be
  undone by a child who knows the gesture; that is Android's behaviour and is
  documented in the README, so it is not a vulnerability report. Anything beyond
  it is.
- **Files the app writes** — the autosave and "My pictures" live in the app's
  private storage; a save to the gallery writes one PNG. Anything that leaks
  them elsewhere is worth reporting.

## Reporting

Please report privately through GitHub: **Security → Report a vulnerability**
on this repository. Do not open a public issue for it.

This is a one-person hobby project. Expect a first answer within a couple of
weeks, and a fix in the next release if the report holds up.

## Supported versions

Only the latest release.
