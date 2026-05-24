# Tern Backlog

This is where we plan and track what Tern should do next. It lives in the
repo on purpose — so it travels with the code, is easy to search, and
doesn't depend on any outside tool like GitHub Issues or Jira.

## How it works

- **Epics** are end-user features — things a pilot can do, see, or feel
  in the cockpit. They are *not* technical components or subsystems. If a
  title sounds like a layer of plumbing, it belongs inside an epic as a
  story, not as an epic of its own.
- **Stories** are the smaller pieces of work that, together, deliver an
  epic. Each story should be small enough to finish and ship on its own.
- Each epic lives in its own file: `epic-XX-short-name.md`.
- Stories live inside the epic file, under a `## Stories` section, so you
  can read the whole feature at a glance.

## Priorities

Three levels — keep it simple:

- **now** — actively being worked on or up next.
- **soon** — queued, will pull from here when "now" empties.
- **later** — known to be valuable but not scheduled. Includes anything
  that requires the pilot to change behavior or buy hardware.

## Status

Every epic and story has one of:

- **todo** — not started.
- **in progress** — actively being worked on.
- **done** — shipped and verified.
- **parked** — paused on purpose. Note *why* in the file.

## Writing style

Plain English. No "As a pilot, I want..." templates, no story points, no
sprint ceremony. Anyone new to Tern should be able to read any epic and
understand it without a glossary.

## Mission check

Every epic should answer: **what unknown does this convert into a known
for the pilot?** If it can't, it probably doesn't belong.

## Current focus vs. end-goal epics

These two kinds of files live side by side and play different roles:

- **`epic-XX-*.md` — end-goal epics.** Each describes a finished
  pilot-facing feature: what the pilot can do, see, or feel once the
  whole thing is built. They're long-lived and stable. They describe
  *the destination*, not what's happening this week.
- **`current-focus.md` — what we're actually doing right now.** Always
  exactly one of these in the backlog. Describes the bare-minimum slice
  of work currently in flight — often scaffolding that isn't yet
  pilot-visible. It points to whichever epic it's serving. When the
  focus area completes, update or replace this file with the next focus.

The split is intentional: end-goal files keep us from forgetting where
we're headed; the current focus keeps us from drifting into doing the
whole thing at once.

## Starting a new epic

Copy `epic-template.md` to `epic-XX-your-feature.md` (increment XX) and
fill it in.

## Shifting current focus

When the current focus is done, edit `current-focus.md` in place to
describe the new focus. Don't create `current-focus-2.md`. The history
of focus changes lives in git log.

## Index

- [Current focus](current-focus.md) — what's actively being built right now.
- [Epic 01](epic-01-peer-awareness-and-sos.md) — Pilots in the same area
  see each other and signal for help without cell service. (End goal.)
- [Known issues](known-issues.md) — snapshot of regressions parked while
  focus is on LoRa. Not a to-do for now; revisit when focus shifts.
