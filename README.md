# Rail Signal

No-root Android app that monitors cellular signal, maps dead zones, and forces a
re-register when Android clings to a dying cell.

On the move through fringe coverage (commuter trains, rural roads), Android phones often
**cling to a dead or dying cell** instead of re-registering — so data stalls even though the
bars look fine, while some phones recover faster on the same tower. Rail Signal makes that
visible and does something about it, without root.

## What it does

- **Live signal instrument** — RSRP / RSRQ / SINR, LTE vs NSA-5G, serving cell (band, ARFCN,
  PCI, TAC), with a health gauge + live sparkline.
- **Trip recorder** — logs signal + GPS across a journey to a local database; survives
  screen-off via a foreground service.
- **Dead-zone profile** — each trip shows a signal health bar (alive / weak / dead) + average
  dBm + % dead.
- **Cling recovery** — detects a stuck/weak cell and either notifies you to reset, or (via
  Shizuku) **automatically forces a re-register** by briefly cycling the allowed network type.
- **CSV export** — every sample, for offline analysis.

## Requirements

- Android 12+ (developed and tested on Android 16 / One UI, Galaxy Z Fold5).
- **No root.**
- Automatic recovery additionally needs **Shizuku** (optional — see below).

## Install

### Option A — build from source

```bash
# Android SDK (compileSdk 36) + JDK 17 required.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** (Ladybug or newer), let Gradle sync, and **Run**.
If sync flags AGP/Kotlin/KSP versions, accept the upgrade assistant.

### Option B — sideload the debug APK

Build once (above) and install the resulting `app-debug.apk` on any compatible device.

On first launch, grant **Phone** + **Location** (+ **Notifications**). Location is required for
cell identity (band/ARFCN/PCI) and to tag samples with position.

### Surviving aggressive battery management

For background recording to survive a long trip on OEMs like Samsung One UI, exclude the app
from battery optimization and add it to **"Never sleeping apps"** — the app prompts for this.

## Automatic recovery via Shizuku (no root)

The privileged radio action (`setAllowedNetworkTypesForReason`) needs a system-level permission
a normal app can't hold. Rail Signal runs it through **Shizuku**, which grants apps ADB/shell
privileges **without root**.

1. **Install Shizuku** — https://github.com/RikkaApps/Shizuku (Play Store, or the GitHub
   release APK).
2. **Start the Shizuku service** (no root): enable **Settings → Developer options → Wireless
   debugging**, open Shizuku, and tap **Start**. (Shizuku also documents a USB/computer method.)
3. In Rail Signal → **Record → Grant Shizuku**, then set recovery mode to **Auto**.

> **Caveat (no-root reality):** Shizuku must be re-started after every reboot (and if wireless
> debugging drops). When Shizuku isn't running, recovery automatically falls back to the
> one-tap **Notify** prompt, which needs no setup and always works.

## Usage

- **Live** — real-time gauge (colour = signal health), network type, serving cell, and which
  telephony fields your device exposes.
- **Record** — Start/Stop a trip; pick a recovery mode; **Export CSV**; review past trips.
  - **Recovery modes:** **Auto** (detect + auto re-register via Shizuku) · **Notify** (detect +
    one-tap reset prompt, no radio action) · **Off** (stock Android). Auto-pauses on low battery
    or power-saving.

## How recovery works (and its limits)

- **Detection** ignores Android's `ServiceState` (it reports "in service" straight through real
  blackouts) and instead uses data-validation, RSRP collapse, and telephony-callback silence.
- **Action** — Auto briefly drops NR from the allowed network types and restores it, forcing a
  RAT re-evaluation / re-registration **without** killing Wi-Fi or Bluetooth.
- A non-root app **cannot** change the modem's handover algorithm. This is detection +
  re-register, not a signal booster. `setRadioPower` is a no-op on modern Android; airplane-mode
  cycling works but is deliberately not used (it drops Wi-Fi/BT).

## Data & privacy

Everything (samples, trips, recovery events) is stored locally in an on-device SQLite database.
Nothing leaves the device except a CSV you explicitly export and share.

## Tools

- `tools/analyse_csv.py` — analyse an exported CSV: dropout segments + locations, signal stats,
  network/band usage, and sampling gaps. `python3 tools/analyse_csv.py <export.csv>`

## Docs

- [`docs/architecture.md`](docs/architecture.md) — components, ports, data flow, recovery tiers
- [`docs/adr/`](docs/adr/) — architecture decision records
- [`BRAND.md`](BRAND.md) · [`DESIGN.md`](DESIGN.md) — brand + design system

## Stack

Kotlin · Jetpack Compose (Material 3) · Room · coroutines/Flow · location-type foreground
service · Shizuku (optional). minSdk 31, targetSdk 36.
