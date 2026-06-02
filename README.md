# Tern

Tern is an Android paragliding app focused on removing unknowns from flying.
Built by a pilot, for personal use.

## What it does

The goal is simple: make paragliding pleasurable by converting unknowns into
knowns. Every feature gets evaluated against that bar.

## Current state

The following features compile and run on device, but have not been verified
in actual flight conditions:

- **Map** — MapLibre-based native map with offline tile support
- **Route planning** — waypoint editing and task definition
- **Weather data** — wind and forecast overlays on the map
- **Airspace display** — cached airspace polygons rendered per viewport
- **PG spots** — paragliding site markers with metadata
- **Peer markers** — other pilots shown on the map via the Mezulla LoRa
  mesh, as a full peer HUD (callsign, relative altitude, heading, distance,
  staleness) plus screen-edge indicators for buddies off the view. The
  end-to-end path (phone ↔ board ↔ mesh) is verified on real hardware by
  replaying real flights (Aravis, Edith's Gap, Bir Billing); not yet flown
  live in the air.

All spatial data (airspaces, PG spots, weather) is cached on disk using
FlatBuffers with Hilbert spatial indexing. The app is designed to work
fully offline — online connectivity is only used for prefetch and cache.

## Building

```bash
git clone https://github.com/raghumad/Tern.git
cd tern-android
./gradlew assembleDebug
```

## Architecture

See [TECHNICAL.md](./TECHNICAL.md) for the technical overview and
[docs/architecture/](./docs/architecture/) for detailed subsystem docs.

## Platform

Android only. iOS is parked until Android matures and there is real demand.

## License

See LICENSE file.
