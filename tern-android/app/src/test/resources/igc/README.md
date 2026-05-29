# IGC test resources

Real flight logs and parser fixtures used by the swarm-simulator BDD
test framework. Layout:

```
igc/
  flights/         # real flight data, grouped by region
    fr/            # France
      2026-04-25-aravis-team-*.igc
    in/            # India (future)
    ...
  scenarios/       # scenario manifests (one per BDD test scenario)
  fixtures/        # parser unit-test fixtures (synthetic, exact ground truth)
```

## `flights/`

Real IGC flight logs. Each pilot is one file. File naming:
`<YYYY-MM-DD>-<scenario-slug>-<pilot-id>.igc`.

Pilot IDs are short stable handles (often the pilot's XContest username)
used to refer to them in scenario manifests and BDD scenarios.

## `scenarios/`

One file per scenario the BDD framework can play back. Each scenario
points at a list of IGC files in `flights/`, assigns pilot IDs, and
documents what makes the scenario interesting (terrain, launch timing,
team dynamics, etc.).

## `fixtures/`

Hand-crafted IGC files used by parser unit tests. Deterministic, small,
exact ground truth — these are *not* real flights and shouldn't be used
as scenarios.

## Adding new flights

1. Drop the IGC under `flights/<region>/` with a clean name.
2. If it's part of a new scenario, add a manifest under `scenarios/`.
3. Add a human-readable note to that manifest about what the scenario
   exercises (range, terrain, launch staggering, SOS, etc.).
