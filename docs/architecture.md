# Rail Signal — Architecture

Single-module Android app (Kotlin + Jetpack Compose + Room). Platform and privileged access sit
behind narrow **ports** so the UI and recording logic never depend on a specific telephony API,
location provider, or recovery mechanism. See [`adr/`](adr/) for the decisions behind this.

## Component map

```
                         ┌──────────────────────────────────────────────┐
   ui/ (Compose)         │   CommuteForegroundService  (location type)   │  service/
   ┌──────────────┐      │   recording loop + cling watchdog             │
   │ MainActivity │      └───────────┬───────────────────┬──────────────┘
   │ LiveScreen   │ reads            │ drives            │ drives
   │ RecordScreen │◄─────┐  ┌────────▼────────┐   ┌──────▼───────────┐
   │ RecoveryCard │      │  │ TelephonySource │   │ LocationSource   │  ports
   │ theme/       │      │  │ (port)          │   │ (port)           │
   └──────────────┘      │  └────────┬────────┘   └──────┬───────────┘
          ▲              │           │ Flow<RadioReading> │ Flow<LocationFix>
          │ StateFlow    │           └─────────┬──────────┘
   ┌──────┴───────┐      │                     ▼
   │ Recording    │◄─────┘            ┌──────────────────┐  SampleMapper
   │ Controller   │                   │  TripRecorder    │
   └──────────────┘                   └────────┬─────────┘
                                                ▼ writes
                                     ┌────────────────────┐
                                     │  Room (data/)      │  Trip · Sample
                                     │  DAOs + TripStats  │  RecoveryEvent
                                     └────────────────────┘
                                                ▲ reads
                            cling detected ─────┤
                                     ┌──────────┴────────┐      ┌──────────────────────────┐
                                     │  watchdog (in     │ uses │ RadioActuator (port)     │  radio/
                                     │  the service)     ├─────▶│  ├ GuidedPromptActuator   │  (Notify)
                                     └───────────────────┘      │  └ ShizukuActuator        │  (Auto)
                                                                └────────────┬─────────────┘
                                                                             │ Shizuku binder
                                                                ┌────────────▼─────────────┐
                                                                │ ShizukuRadioService      │  radio/shizuku/
                                                                │ (runs as shell uid)      │  (AIDL user-service)
                                                                └──────────────────────────┘
```

## Modules

| Package | Responsibility | Key types |
|---------|----------------|-----------|
| `telephony/` | Live radio readings + call-active state; hides `TelephonyCallback`/`CellInfo`, NSA-5G inference, permission gating | `TelephonySource` (port), `TelephonyCallbackAdapter`, `RadioReading`, `Fidelity` |
| `location/` | GPS stream | `LocationSource` (port), `FusedLocationAdapter`, `LocationFix` |
| `data/` | Local persistence | `RailSignalDatabase`, `Trip`, `Sample`, `RecoveryEvent`, DAOs, `TripStats` |
| `trip/` | Recording orchestration + UI bridge | `RecordingController`, `RadioReading.toSample` |
| `service/` | Commute-scoped foreground recording + the cling watchdog/recovery loop | `CommuteForegroundService` |
| `radio/` | The privileged-action seam + recovery policy | `RadioActuator` (port), `GuidedPromptActuator`, `ShizukuActuator`, `RecoveryMode`, `RecoveryPrefs` |
| `radio/shizuku/` | Shell-uid user-service for the privileged call | `IShizukuRadio` (AIDL), `ShizukuRadioService` |
| `ui/` | Compose screens + theme | `MainActivity`, `LiveSignalScreen`, `RecordScreen`, `RecoveryCard`, `theme/` |

## Data flow

1. `CommuteForegroundService` collects `TelephonySource.readings` + `LocationSource.updates`.
2. A heartbeat (~4 s) and every service-state change produce a `Sample` (via `SampleMapper`),
   written to Room; live status is pushed to `RecordingController` for the UI.
3. The **watchdog** evaluates each tick for a cling (data unvalidated, RSRP collapse, or
   telephony silence) and, when sustained, invokes the active `RadioActuator`. It is
   **call-aware**: a re-register can't dislodge the cell carrying an active voice bearer, so
   while a call is up the watchdog defers and instead fires the instant the call ends
   (`TelephonySource.inCall`; logged with a `_POSTCALL` trigger suffix).
4. The **Live** screen subscribes to `TelephonySource` directly; the **Record** screen reads
   `RecordingController` + Room (`Trip`, `TripStats`, `RecoveryEvent`).

## Data model (Room)

- **Trip** — `id`, `startTs`, `endTs?`, `carrier?`, `direction`, `sampleCount`.
- **Sample** — FK `tripId`, `tsMs`, GPS (`lat/lon/accuracyM/speedMps`), radio (`rsrp/rsrq/sinr/
  band/arfcn/pci/tac/cellId`), `networkType`, `nsa5g`, `serviceState`, `dataStallInferred`,
  `fidelityMask`.
- **RecoveryEvent** — `tripId`, `tsMs`, `trigger`, `action`, `outcome`, `recoveryMs?`.
- **TripStats** — derived aggregate (not a table): per-trip alive/weak/dead counts + avg/min RSRP.

## Recovery tiers (the privileged-action seam)

All radio actions sit behind `RadioActuator`, selected by `RecoveryMode` + a battery guard:

| Mode | Actuator | Mechanism | Needs |
|------|----------|-----------|-------|
| **Auto** | `ShizukuActuator` → `ShizukuRadioService` | Power-cycle the cellular radio only — `ITelephony.setRadioPower(false)` → hold ~9s → `(true)` via the raw binder, run as shell uid (keeps Wi-Fi up). Aborts if a call is active. | Shizuku running |
| **Notify** | `GuidedPromptActuator` | High-priority notification deep-linking to airplane/network settings | nothing |
| **Off** | — | No radio action (stock behaviour) | nothing |

`effectiveMode()` forces **Off** on low battery or power-saving. If Shizuku isn't ready, Auto
degrades to the guided prompt. Detection deliberately does **not** trust `ServiceState`. See
[`adr/0001`](adr/0001-no-root-shizuku-seam-guided-baseline.md) and
[`adr/0003`](adr/0003-spike-gate-auto-recovery.md).

## Build / run

`./gradlew :app:assembleDebug` (Android SDK compileSdk 36, JDK 17). The Gradle wrapper jar is
committed, so `./gradlew` works on clone. `local.properties` (SDK path) is generated per machine
and gitignored. See [`../README.md`](../README.md) for device install + Shizuku setup.
