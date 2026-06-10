# ADR 0001 — No root; Shizuku is the privileged-action seam, guided-prompt is the baseline

- **Status:** Accepted
- **Date:** 2026-06-02
- **Relates to:** [requirements](../requirements/rail-signal.md), [0003](0003-spike-gate-auto-recovery.md)

## Context

The user requires **no root**. Deep analysis of Android 14/15/16 (and One UI) established
that a non-root app **cannot act on the cellular radio** through public APIs:

- `WRITE_SECURE_SETTINGS` (ADB-grantable) lets the app *write* `AIRPLANE_MODE_ON`, but the
  radio does **not** react — the toggle is driven by a **system-only protected broadcast** the
  app cannot send. Dead end for recovery on modern Android.
- Effective airplane toggle (`ConnectivityManager.setAirplaneMode` / `cmd connectivity
  airplane-mode`) needs `NETWORK_SETTINGS`, and network-type lock needs `MODIFY_PHONE_STATE` —
  both `signature|privileged`, **not** obtainable via `pm grant`.
- **Shizuku** (a daemon started over USB/wireless debugging) lets an app borrow the ADB/shell
  identity and invoke these privileged calls **without root**. It is the *only* non-root path
  to act on the radio. Cost: must be re-armed after each reboot; reliability is device-specific.
- An **AccessibilityService** that taps the Quick-Settings/Settings UI is the only pure-install
  actuator, but it is fragile across One UI versions and increasingly Play-policy-risky.

## Decision

Two-tier recovery with a hard architectural seam between *observe/decide* and *act*:

1. **Guided-prompt recovery is the always-available baseline.** Zero setup, no special
   permission beyond a deep-link/notification. The app detects the stuck state and asks the
   *user* to toggle. This is the dependable floor and is never removed.
2. **Privileged actions go through a single `RadioActuator` port with a Shizuku adapter.** All
   automated radio actions (the cellular radio power-cycle — see [0003](0003-spike-gate-auto-recovery.md))
   are isolated behind one interface so the rest of the app never assumes they exist or work.
3. **AccessibilityService actuation is explicitly rejected for v1** (fragility + policy), but the
   port leaves room for it as a future adapter.

## Consequences

- The app is useful and stable on a clean install (observe + map + predict + guided recovery)
  regardless of whether any privileged path works.
- Auto-recovery is an *optional, swappable adapter* — its unreliability can't destabilise the
  core product.
- Users who want auto-recovery accept a one-time Shizuku setup and reboot re-arming.
- A clean port boundary makes the Shizuku spike ([0003](0003-spike-gate-auto-recovery.md)) a
  contained experiment rather than an architectural commitment.
