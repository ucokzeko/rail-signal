# rail-signal — Design system

Jetpack Compose. The web-centric design principles (OKLCH/Tailwind) are adapted to
`MaterialTheme` (colorScheme + typography + shapes) + Canvas/animation. See [BRAND.md](BRAND.md).

## Colour
Tinted-slate dark scheme (hue ~255, low chroma) + teal signature + semantic signal ramp.
Defined in `ui/theme/Color.kt`, wired into `darkColorScheme` in `ui/theme/Theme.kt`.

| Token | Hex | Use |
|-------|-----|-----|
| background | `#0E1116` | app canvas |
| surface | `#151A21` | bars, sheets |
| surfaceVariant | `#1B2129` | cards |
| outline | `#2A323D` | hairlines, gauge track |
| onBackground | `#E7ECF2` | primary text |
| onSurfaceVariant | `#93A0B0` | muted text/labels |
| primary (teal) | `#2DD4C0` | identity, active nav, primary action, "alive" |
| signal weak | `#ECB949` | degrading health |
| signal dead | `#F0706E` | no/■ service, error |

`quality(rsrp)` maps −120 dBm (dead) → −75 dBm (excellent) to 0..1; `healthColor(q)` lerps
dead → weak → alive across it. The gauge, sparkline, and status word all read from it.

## Type
System sans for chrome; **monospace** for live numbers (gauge readout, metrics). Scale steps
≥1.25; ≤3 weights (Normal/Medium/SemiBold/Bold sparingly). `ui/theme/Type.kt`.

## Shape
Corner scale by component: chips/small `10dp`, cards `16dp`, hero/gauge container `24dp`,
sheets `28dp`. `ui/theme/Shape.kt`.

## Motion
- Gauge value animates on change (tween ~600ms, ease-out).
- Recording state shows a pulsing live dot (infinite, opacity/scale only).
- Mode/selection transitions are crossfades/color tweens. Transform + opacity only.

## Components
- `SignalGauge` (Canvas radial arc + center readout) — Live hero.
- `Sparkline` (Canvas polyline + area) — rolling RSRP history.
- Mode segmented control (Auto/Notify/Off), status pills, recovery timeline rows, trip cards.
- States honoured: empty ("No trips yet"), live/recording, disabled (export while running),
  Shizuku not-ready, low-battery guard.

## Self-check (polish gate)
Contrast ≥4.5:1 on text; 48dp touch targets; tokens only (no stray hex in components);
no anti-patterns (no glow under CTAs, no gradient text, no uniform-radius, no emoji nav icons —
uses `material-icons-extended`). Dark-only by scene sentence.
