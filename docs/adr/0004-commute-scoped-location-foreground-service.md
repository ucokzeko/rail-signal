# ADR 0004 — Commute-scoped, location-type foreground service for monitoring

- **Status:** Accepted
- **Date:** 2026-06-02
- **Relates to:** [requirements](../requirements/rail-signal.md)

## Context

Continuous radio + GPS monitoring with the screen off is mandatory for the map and for
stuck-state detection. Two platform realities constrain how:

- **Android 14+** requires every foreground service to declare a `foregroundServiceType`. There
  is **no telephony/"signal-monitor" type**; `dataSync` is time-capped on Android 15;
  `specialUse` is heavily review-gated.
- **Samsung One UI is the worst-case** background-killer ("Sleeping apps", Deep Sleep). It
  honours *compliant* foreground services for `targetSdk ≥ 14`, but only if the user also
  **excludes the app from battery optimisation** and adds it to **"Never sleeping apps"** —
  manual steps the app can prompt for but not self-apply.
- Running 24/7 would drain battery for no benefit; monitoring only matters on the commute.

## Decision

- Use a **`location`-type foreground service** with a persistent notification. It is the most
  defensible type because cell-identity already requires `ACCESS_FINE_LOCATION`, so location
  access is genuinely needed and grants location-while-running.
- **Scope monitoring to the commute**, not always-on: start via a **geofence** around the
  origin station and/or **Activity Recognition** ("in vehicle / on train"), and stop on arrival
  or when stationary off-route. Adaptive sampling (denser near known dead-zone bins).
- **Onboard the user** through the One UI exemptions: prompt for `REQUEST_IGNORE_BATTERY_
  OPTIMIZATIONS` and guide them to "Never sleeping apps", with a status indicator if not set.
- Avoid exact alarms; the FGS loops internally while active.

## Consequences

- Survives a full ~80-minute commute without being killed — the key reliability requirement —
  *provided* the user completes the one-time exemption (the app verifies and nags).
- Battery cost is bounded to actual travel, not the whole day.
- Adds a dependency on geofencing / activity-recognition APIs and their permissions
  (`ACTIVITY_RECOGNITION`, background location considerations).
- If the user skips the exemptions, monitoring degrades; the app must detect and surface that
  rather than silently failing.
