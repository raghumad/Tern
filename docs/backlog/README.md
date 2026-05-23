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

## Starting a new epic

Copy `epic-template.md` to `epic-XX-your-feature.md` (increment XX) and
fill it in.

## Index of current epics

(Add a one-line entry here for each epic as it's created.)

- _none yet_
