# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in **child-paint**.

## What this is

TODO: one paragraph — what this project is and the one design choice that explains the rest.

## Commands

TODO: build / run / test, one line each.

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
