package com.railsignal.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Tinted slate-ink neutrals (never pure black/gray).
val Bg = Color(0xFF0E1116)
val Surface = Color(0xFF151A21)
val SurfaceVariant = Color(0xFF1B2129)
val Outline = Color(0xFF2A323D)
val OutlineSoft = Color(0xFF20272F)
val TextHigh = Color(0xFFE7ECF2)
val TextMuted = Color(0xFF93A0B0)

// Signal teal — signature accent + the "alive" end of the health ramp.
val Teal = Color(0xFF2DD4C0)
val OnTeal = Color(0xFF04201C)
val TealContainer = Color(0xFF0F3A35)
val OnTealContainer = Color(0xFFA7F0E7)

// Semantic signal-health ramp.
val SignalGood = Teal
val SignalWeak = Color(0xFFECB949)
val SignalDead = Color(0xFFF0706E)
val OnDead = Color(0xFF2A0707)

/** Map RSRP (dBm) + in-service to a 0..1 quality. −120 dead, −75 excellent. */
fun signalQuality(rsrp: Int?, inService: Boolean): Float {
    if (!inService || rsrp == null) return 0f
    return ((rsrp + 120f) / 45f).coerceIn(0f, 1f)
}

/** Lerp dead → weak → alive across the quality range. */
fun healthColor(quality: Float): Color = when {
    quality <= 0f -> SignalDead
    quality < 0.34f -> lerp(SignalDead, SignalWeak, quality / 0.34f)
    quality < 0.7f -> lerp(SignalWeak, SignalGood, (quality - 0.34f) / 0.36f)
    else -> SignalGood
}
