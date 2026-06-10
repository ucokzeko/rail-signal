# Glossary — rail-signal

Ubiquitous language for the project. Code, tests, ADRs, and plans use these terms.

## Connectivity & radio

- **Dead zone** — a stretch of the route with no usable external cellular coverage.
  On the rail line the dominant one is the **open rural dead-zone stretch**.
  Distinct from a *stuck state*: the radio behaves correctly, there's simply no signal.
- **Stuck (no-service) state** — the modem has lost its serving cell and fails to
  re-register promptly even though coverage exists, until forced (airplane toggle /
  reboot). This is the failure mode the manual "airplane mode" trick fixes, and the
  primary target for recovery.
- **NSA 5G (Non-Standalone)** — 5G NR riding on an LTE anchor. The LTE cell is the
  anchor; the 5G cell is the **Secondary Cell Group (SCG)**.
- **SCG failure** — radio-link failure on the 5G secondary cell in weak coverage. Can
  show "5G icon, no data" until the device falls back to LTE. A suspected cause of
  *data* (not voice) dropouts.
- **Cell reselection / handover** — moving the connection between cells; aggressive,
  well-tuned reselection is part of why iPhones re-acquire faster on the same tower.
- **RRC** — Radio Resource Control; the L3 state machine governing connect/idle and
  re-establishment after a link failure.
- **VoLTE / VoNR** — voice over LTE / 5G. With 3G shut down (Oct 2024) voice depends on
  these; voice can drop independently of data, so the app distinguishes them.
- **Faraday-cage effect** — the train carriage's metallic window tint attenuates
  signal heavily; a major *in-train* factor independent of tower coverage.
- **Carrier bundle / carrier config** — operator-specific radio tuning (fallback
  thresholds, band priorities). Apple ships tightly-tuned bundles; not user-writable
  on Android. The most credible root-cause layer for the iPhone-vs-Android gap.

## Signals we log

- **RSRP / RSRQ / SINR** — reference-signal received power / quality / signal-to-noise.
  RSRP = reach; SINR/RSRQ = usable quality (often craters before RSRP does).
- **Band / ARFCN** — frequency band (e.g. low-band B28/700 MHz vs mid-band n78/3.5 GHz)
  and its channel number. Low-band reaches furthest and penetrates the carriage best.
- **Cell ID / PCI / TAC** — serving-cell identifiers; reveal handover boundaries where
  drops cluster. Reading cell identity requires `ACCESS_FINE_LOCATION`.
- **Service state** — registered / out-of-service / emergency-only; the dropout signal.
- **Data-stall** — inferred (not a single API): service state + no throughput +
  `NetworkCapabilities` not `VALIDATED`.

## App concepts

- **Distance-along-track (km-mark)** — position indexed by linear distance along the
  rail route, not raw GPS. Drops recur at the *same km* every trip, so everything is
  binned by km-mark (~100–250 m bins) for prediction.
- **Dead-zone map** — per-carrier, km-indexed aggregate over many trips: per-bin drop
  probability, median RSRP/SINR, and dwell time.
- **Prediction / pre-emption** — using the map + GPS/ETA to anticipate a known hole and
  act *before* it (warn, pre-fetch/flush queued sends, arm recovery on exit). The only
  durable lever, since the app cannot create coverage.
- **Recovery** — restoring service after a drop. Two tiers:
  - **Guided recovery** — detect stuck state → one-tap deep-link / notification asking
    the user to toggle airplane mode or set "LTE preferred". Always available, no setup.
  - **Auto-recovery** — automated cellular **radio power-cycle** (`ITelephony.setRadioPower`
    off→on via Shizuku; keeps Wi-Fi up) with no user tap. Requires **Shizuku**; defers while
    on a call and fires on call-end. (Feasibility-gated by ADR 0003 — passed 2026-06-10.)
- **Shizuku** — an app that grants other apps ADB/shell-level privileges *without root*,
  via a daemon started over USB or wireless debugging. The only non-root path to
  *act* on the radio. Must be re-armed after each reboot; reliability is device-specific.
- **Guided-prompt baseline** — the always-works, zero-setup recovery path that needs no
  Shizuku and no special permission beyond a deep-link.

## Platform constraints (terms)

- **`WRITE_SECURE_SETTINGS`** — ADB-grantable permission; lets the app *write* the
  airplane-mode setting but the **radio does not react** (the toggle broadcast is
  system-only), so it's a dead end for recovery on modern Android.
- **`MODIFY_PHONE_STATE`** — `signature|privileged`; needed for the radio power-cycle
  (`ITelephony.setRadioPower`). Not `pm grant`-able; reachable only via Shizuku (shell identity
  carries it).
- **Foreground service (location type)** — the mechanism that keeps monitoring alive
  with the screen off; `location` type chosen because cell-identity needs location anyway
  and it's the most defensible against One UI's background-killing.
