# ADR 0003 — Gate automated recovery behind a hands-on Shizuku spike

- **Status:** Accepted
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
