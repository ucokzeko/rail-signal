# ADR 0003 — Gate automated recovery behind a hands-on Shizuku spike

- **Status:** Accepted · spike run 2026-06-10 (verdict below)
- **Date:** 2026-06-02
- **Relates to:** [requirements](../requirements/rail-signal.md), [0001](0001-no-root-shizuku-seam-guided-baseline.md)

## Context

Automated radio recovery (airplane cycle / network-type lock via Shizuku) is the highest-value
feature **and** the highest-risk one. The research could only verify it *directionally*:

- A documented **open bug** has Shizuku-driven airplane toggling **failing on Android 16**
  (Shizuku issue #1561) on at least one device.
- Network-type lock via `MODIFY_PHONE_STATE` through Shizuku is *more* plausible (shell carries
  that permission) but its per-`reason` value can be **overridden by carrier/system**, so
  stickiness is unverified.
- Some One UI builds need a **full reboot**, not just an airplane toggle, to clear a wedged
  modem — a ceiling no non-root app can cross.

None of this is confirmed on the **actual Fold 5 / One UI 8.x build**. Committing the
architecture to auto-recovery before validating it would be betting on unverified behaviour.

## Decision

**Do not build the auto-recovery feature until a contained spike proves it works on the target
device.** Specifically:

1. Build the zero-setup product first (observe → map → predict → guided recovery), which has no
   dependency on privileged actions.
2. Run an early **Shizuku spike** on the user's Fold 5 that tests *both* candidate actions
   (airplane-mode cycle and network-type lock), measuring: does it execute, does the radio
   actually react, does it survive across reboots, and does service measurably recover.
3. **Decision gate:** if a method reliably recovers service → implement it as the `RadioActuator`
   Shizuku adapter ([0001](0001-no-root-shizuku-seam-guided-baseline.md)). If neither is
   reliable → ship guided-only and **document the negative result** in this ADR's follow-up.

## Consequences

- Engineering effort on auto-recovery is spent only after feasibility is proven, not on faith.
- The spike needs the physical device (and ideally a real dropout, or a forced no-service test)
  — it is an explicit, scheduled plan phase, not an afterthought.
- The product's promised outcomes degrade gracefully: "stay connected" is delivered by guided
  recovery + pre-emption even in the fail case.
- This ADR will be **updated with the spike verdict** once run, recording what worked on One UI
  8.x for future reference.

## Spike verdict (2026-06-10) — Fold 5 (SM-F946B), Telstra, One UI

Run on the physical device through the shell (uid 2000) identity Shizuku provides:

- **Network-type lock — REJECTED (ineffective).** `cmd phone set-allowed-network-types-for-users`
  applies (readback confirms), but the modem keeps its CS/voice registration glued to the serving
  LTE cell even when LTE is fully disallowed — only the data bearer reacts. Allowed-types is a
  *cell-selection preference*, not a detach command, so it cannot dislodge a clung cell. No
  permission level changes this (the shell command already runs at `MODIFY_PHONE_STATE`).
- **Airplane cycle — works, not chosen.** `cmd connectivity airplane-mode enable/disable` (shell,
  so Shizuku-reachable) forces a real re-registration and moved the device to a different cell —
  but it drops Wi-Fi/Bluetooth too.
- **`restart-modem` — unavailable.** `cmd phone restart-modem` is denied to the shell uid.
- **`ITelephony.setRadioPower(false/true)` via the raw binder — CHOSEN.** Reached by reflection
  (`ServiceManager.getService("phone")` → `ITelephony$Stub`), run as shell: a true cellular radio
  power-cycle (`mVoiceRegState → POWER_OFF → re-register`) that **keeps Wi-Fi up**. No root, no
  system app, no Knox trip. Validated end-to-end through the app's Shizuku user-service.

**Gate resolved → PASS.** Auto-recovery is implemented as the `setRadioPower` cellular power-cycle
behind the [0001](0001-no-root-shizuku-seam-guided-baseline.md) `RadioActuator` Shizuku adapter:
hold the radio off ~9s (Samsung reaches `POWER_OFF` ~5–6s after the call), **deferred while a call
is active** (it would drop the call) and **fired on call-end**, and run only during an active
logging session.
