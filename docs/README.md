# Tern Docs

Everything here is documentation, not source code. A quick map of where things
live so a new reader can find their way around.

## Where to start

| If you want to... | Go to |
| --- | --- |
| Understand what Tern *is* (product) | `/README.md` at repo root |
| Know what we're building next, and in what order | `backlog/` |
| Understand how the Android app is put together | `architecture/` |
| Read the design of a specific feature | `features/<name>/` |
| Set up dev, run tests, or measure performance | `guides/` |
| Read a specific technical decision (RFC) | `rfcs/` |
| Find the manual UX checklist | `ux/validation-checklist.md` |
| Reference for designing the custom Mezulla hardware | `hardware/` |
| Look up an external spec we reference | `references/` |
| Read the credits and licenses | `legal/` |

## Subfolders explained

- **backlog/** — End-user features (epics) and the smaller pieces of work
  that build them (stories). This is the source of truth for what we plan
  to build, in priority order.
- **architecture/** — How the Android app is structured: state management,
  spatial caching, etc. What the system *is*.
- **features/** — Per-feature design documents. What a feature does, how
  it's modeled, what scenarios it covers.
- **guides/** — How to do common dev work: develop, test, measure performance.
- **rfcs/** — Numbered technical decisions. Used when a specific design
  choice needs an explicit record (e.g. "why we scale icons this way").
- **ux/** — Cross-cutting UX docs: validation checklists, glanceability
  audits.
- **hardware/** — Reference material for the open-source custom Mezulla
  board: design decisions, sourcing notes, antenna / power / sensor
  trade-offs. Read before writing a BOM, not after.
- **references/** — External specs and standards we depend on (e.g. FAI
  Sporting Code).
- **archive/** — Historical docs kept for context but no longer current:
  completed plans, retrospectives, stale comparisons.
- **legal/** — Licenses and credits.
- **assets/** — Images used by docs and the root README.

## Conventions

- Plain English. No PM jargon ("user story", "definition of done", "story
  points"). Anyone new should be able to read any doc without a glossary.
- Epics in the backlog are framed as **end-user features** — things a pilot
  does or experiences — not technical subsystems.
- When a doc becomes a record of completed work, move it to `archive/`
  rather than letting it rot in place.
