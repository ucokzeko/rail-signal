# ADR 0002 — Prediction-first: a distance-indexed dead-zone map is the core

- **Status:** Accepted
- **Date:** 2026-06-02
- **Relates to:** [requirements](../requirements/rail-signal.md)

## Context

The route analysis is unambiguous: the dominant failure on the rail line is a **genuine
coverage gap** on the open rural dead-zone stretch, compounded by the train carriage's
Faraday-cage window tint, the post-2024 loss of the 3G fallback layer, and an onboard repeater
that can only re-amplify *existing* external signal. **No app can create coverage**, and there
is no wifi fallback.

But a train is a near-deterministic system: it runs on rails, at predictable speed, hitting the
**same dead zones at the same distance-along-track every trip**. That regularity is the exploit.

## Decision

Make **prediction the core of the product**, not an add-on:

- Index every sample by **distance-along-track (km-mark)**, not raw GPS, and aggregate across
  trips into a **per-carrier dead-zone map** binned at ~100–250 m: per-bin drop probability,
  median RSRP/SINR, dwell time.
- Drive behaviour off the map + live GPS/ETA: **anticipate** each known hole and act *before*
  and *at the exit* of it (warn, pre-fetch, arm recovery for the instant of re-entry to
  coverage) rather than only reacting after a stall.
- Recovery (guided or auto) is the *response*; the map + prediction is the *intelligence layer*
  that makes the response timely and that no existing app (Network Cell Info, CellMapper,
  "refresher" apps) provides for a specific commute.

## Consequences

- The app delivers value (a personal coverage map + pre-emption) **even if all recovery levers
  fail**, decoupling usefulness from the risky Shizuku path.
- Requires reliable GPS + map-matching to a route polyline, and storage/aggregation across
  trips (e.g. Room) — real engineering, justified by being the durable lever.
- Cold-start: the map is empty on trip 1 and sharpens over trips; optional external seeding
  (CellMapper/OpenCellID) is a possible enhancement, not a dependency.
- Sets the data model: samples → trips → km-binned aggregates, per carrier.
