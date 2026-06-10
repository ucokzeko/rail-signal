# rail-signal — Requirements

> **Status:** Signed off 2026-06-02. Plan drafted (gate 3 — sequencing A+ chosen).
> **Plan:** [`../plans/rail-signal/plan.md`](../plans/rail-signal/plan.md)
> **Glossary:** [`../glossary.md`](../glossary.md) · **Decisions:** [`../adr/`](../adr/)

## Problem

Every week the user commutes a **regional commuter rail line**, end to end, on a **Samsung
Galaxy Fold 5**. The connection cuts repeatedly during the journey, while nearby iPhone
users stay connected. The user wants an Android-16 app, **no root**, that helps the phone
"handle signal better, like iOS does".

### What the deep analysis established (and corrected)

1. **It is not the hardware.** The Fold 5's Qualcomm **Snapdragon X70** modem is top-tier
   and generally *out-holds* iPhones as signal degrades. The gap is **OS/radio-policy +
   carrier-config tuning**, not RF silicon. iOS's tightly-tuned carrier bundles fall back
   from weak NSA-5G to LTE and re-register gracefully; One UI + AU carrier configs tend to
   get **stuck in a no-service state** that needs a manual airplane toggle.
2. **A non-root app cannot control the modem.** It can *observe* fully, *map & predict*,
   and *guide* the user. The only non-root path to *act* on the radio is **Shizuku**, and
   that is **flaky and unverified on this device/build** (an open Android-16 airplane-toggle
   bug). Plain `WRITE_SECURE_SETTINGS` writes the setting but the radio ignores it. Root-only
   levers (band/cell lock) are out of scope by the no-root constraint.
3. **The route's dominant failure is a genuine coverage gap** along the open rural dead-zone
   stretch, worsened by the train carriage's **Faraday-cage** window tint, the **loss
   of the 3G fallback layer** (the 2024 3G shutdown → gaps are now hard 4G/5G outages),
   and the fact that the line's onboard repeater only re-amplifies *existing* external signal.
   There is no onboard wifi to fail over to. **You cannot conjure signal — so prediction +
   fast recovery, not "better reception", is the achievable product.**

## Outcomes

Observable end states when this is done — primary outcome is **"stay connected"** (minimise
the felt duration of dropouts on the commute).

1. **Faster recovery.** When the device hits a *stuck no-service state*, it returns to
   service in **seconds**, rather than staying dark until the user notices and manually
   toggles airplane mode (or until the train reaches coverage). [primary]
2. **Invisible gaps where possible.** Outbound actions and the radio reset fire **the instant
   coverage returns**, so short holes are bridged without the user babysitting the phone.
3. **The user knows the corridor.** A **personal, distance-indexed dead-zone map** of the
   line shows where and for how long drops occur — turning surprise cuts into
   predictable, pre-empted events.
4. **Honest evidence exists.** Exportable logs quantify the dropouts (secondary — supports
   escalating to the carrier / rail operator, and measuring whether recovery actually helps).

## Success criteria / metrics

Baseline must be measured first (there is no recovery in v0) — these are the targets the
build is steered toward, not guesses:

- **Recovery latency:** median time from *detected stuck-state* → *service restored* is
  materially below the no-app baseline (target set after baseline measurement; stretch:
  < 20 s for stuck states, vs the minutes a manual toggle takes when unnoticed).
- **Map fidelity:** after **≥ 10 logged trips**, the map bins the route at ~100–250 m and
  reports per-bin drop-probability, median RSRP/SINR, and dwell time per carrier.
- **Survivability:** the monitor runs an **entire ~80-minute commute** without being killed
  by One UI background limits (0 unexpected service deaths across a trip).
- **Distinguishes failure modes:** correctly separates *genuine dead zone* vs *stuck state*
  vs *data-stall* vs *voice/VoLTE-only loss* in the logs.
- **Guided recovery works with zero setup:** stuck-state → actionable one-tap prompt in
  ≤ 5 s, on a clean install with no ADB/Shizuku.
- **Auto-recovery (only if spike passes):** fires within N s of a detected stuck-state and
  *confirms* service was restored (measured, not assumed).

## Users / stakeholders

- **Primary:** the user — single Samsung Galaxy Fold 5, weekly rail commuter.
- **Operator (same person):** does the one-time Shizuku/ADB setup *if* the spike justifies it,
  and the One UI battery-optimisation exemption.
- **Secondary / future:** other regional-rail Android commuters, if v1 is later generalised.
- **External (evidence consumers):** the carrier / rail operator, if the user escalates with logs.

## Scope

### In scope (v1)

- **Commute-scoped continuous monitoring** of signal strength, serving + neighbour cells,
  band/ARFCN, RAT (LTE / NSA-5G / SA), service/registration state, inferred data-stall, and
  VoLTE/voice state — sampled against GPS.
- **Distance-along-track indexing** and a **per-carrier dead-zone map** aggregated over trips.
- **Per-trip logging** with **export** (CSV/KML/GeoJSON) for evidence and analysis.
- **Stuck-state / data-stall detection** distinct from genuine dead zones.
- **Guided one-tap recovery** (deep-link to airplane toggle / "LTE preferred") — always
  available, **no setup**, the dependable baseline.
- **Prediction / pre-emption** using map + GPS/ETA: warn before a known hole, and arm
  recovery for the moment of exit. (Outbound-send queueing — see open questions.)
- **Shizuku feasibility spike** on the actual Fold 5 / One UI build, early, to decide whether
  automated recovery is reliable enough to build.
- **Automated recovery via Shizuku** — *conditional* on the spike passing.
- **Background survivability** engineering for One UI (location foreground service + exemption
  prompts + persistent notification).

### Non-goals (firm)

- **Root**, and anything that needs it: band lock, cell lock, network-mode lock as a guarantee
  (NSG-class control).
- **Creating coverage** where there is none (the open rural dead-zone stretch) — physically impossible.
- **Acting as a VPN / signal booster / carrier replacement.**
- **A polished, multi-device, Play-Store-published v1.** Fold-5-first, personal sideload.
- **iOS / cross-platform.**
- **Silent, guaranteed auto-recovery on a clean install** — capped without Shizuku; do not
  promise it.

## Constraints

- **No root** (hard, user-stated).
- **Target Android 16 / API 36**; device **Samsung Galaxy Fold 5 (SM-F946B)**, One UI 8.x.
- **Privileged radio actions only via Shizuku** — one-time grant over USB/wireless debugging,
  **re-armed after every reboot**; reliability is device/build-specific and unverified.
- **Samsung background-killing** ("Sleeping apps", Deep Sleep) → requires a **location-type
  foreground service** + a **manual battery-optimisation exemption** the app can only *prompt*
  for, not self-apply.
- **`ACCESS_FINE_LOCATION` required** for cell identity; location-while-running via the FGS.
- **No onboard wifi fallback**; everything is over cellular.
- **Single developer**, personal project. Assumed stack: **Kotlin + Jetpack Compose**,
  Android Studio (to confirm).

## Assumptions & open questions

| # | Item | Default assumption | Needs confirming? |
|---|------|--------------------|-------------------|
| 1 | **Carrier(s)** in use | App auto-detects per SIM; map is per-carrier | Which carrier(s)? Telstra has broadest regional reach; Vodafone improved post Optus-sharing (Jan 2025). Affects expectations, not architecture. |
| 2 | **Tech stack** | **Confirmed:** Kotlin + Jetpack Compose, coroutines/Flow, Room. minSdk 31 (Android 12), targetSdk 36 | ✓ resolved |
| 3 | **Outbound-send queueing** | **Confirmed OUT of v1.** Pre-emption = predict + warn + arm fast recovery on exit. No traffic interception, no in-app outbox | ✓ resolved |
| 4 | **Shizuku re-arm friction** | Acceptable for a technical user, *if* the spike proves value | Confirmed by "spike-first" choice; revisit after spike. |
| 5 | **Distribution** | Personal sideload (debug/release APK), no Play Store v1 | Confirm; Play Store later would add policy work (accessibility/secure-settings category). |
| 6 | **Recovery action of choice** (if auto) | Spike tests *both* airplane-cycle and network-type-lock; pick the one that proves reliable | ✓ Resolved (2026-06-10): **neither** — network-type lock doesn't detach the cell, airplane drops Wi-Fi. `ITelephony.setRadioPower` cellular power-cycle won. See [ADR 0003](../adr/0003-spike-gate-auto-recovery.md). |
| 7 | **Map seeding** | Cold-start map is empty; fills over trips. Optionally seed from CellMapper/OpenCellID | Want external seed data, or purely personal? |

## Risks

| Severity | Risk | Mitigation |
|----------|------|------------|
| **High** | Shizuku auto-recovery unreliable on Fold 5 / One UI 8 (open Android-16 toggle bug) → can't deliver outcome 1 automatically | **Spike-first** (chosen). Guided-prompt baseline still delivers a degraded form of outcome 1 with no setup. |
| **High** | One UI kills the foreground service mid-commute → no data, no recovery | Location-type FGS + persistent notification + battery-opt exemption prompt + "Never sleeping apps" guidance; verify across a full trip. |
| **Med** | Even a successful airplane toggle doesn't recover a *wedged* modem (some One UI builds need full reboot) | Detect non-recovery after toggle → escalate prompt to "reboot"; document the ceiling honestly. |
| **Med** | Dominant gap (the line's dead-zone corridor) is genuinely dead → recovery cannot help there | Set expectations: there, value = prediction + pre-emption + instant recovery *on exit*, not in-zone connectivity. |
| **Med** | Cell-identity / band fields partially masked by OEM API on the Fold 5 | Validate exact exposed fields in an early observation spike; degrade map fidelity gracefully. |
| **Low** | Play-policy exposure if ever published (accessibility/secure-settings) | Out of v1 scope; sideload. Revisit if distribution changes. |
| **Low** | Battery drain from continuous GPS + telephony sampling | Commute-scoped activation (geofence / activity recognition), adaptive sampling, stop on arrival. |

## Definition of done (v1)

- Monitors + logs a full commute reliably; produces a per-carrier, km-indexed dead-zone map
  after repeated trips, with export.
- Detects stuck-state vs dead-zone vs data-stall vs voice-loss.
- Guided one-tap recovery works on a clean install.
- The Shizuku spike has a clear pass/fail verdict on the Fold 5; **if pass**, automated
  recovery is wired and measured against the baseline; **if fail**, the app ships
  guided-only and the failure is documented.
