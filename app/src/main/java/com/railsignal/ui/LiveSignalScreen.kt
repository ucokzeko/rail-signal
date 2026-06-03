package com.railsignal.ui

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.railsignal.RailSignalApp
import com.railsignal.telephony.Fidelity
import com.railsignal.telephony.NetworkType
import com.railsignal.telephony.RadioReading
import com.railsignal.telephony.ServiceStateCode
import com.railsignal.telephony.TelephonySource
import com.railsignal.ui.theme.Teal
import com.railsignal.ui.theme.healthColor
import com.railsignal.ui.theme.signalQuality
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

class LiveSignalViewModel(app: Application) : AndroidViewModel(app) {
    data class LiveState(val reading: RadioReading?, val fidelity: Int, val history: List<Int?>)

    private val source: TelephonySource = (app as RailSignalApp).telephony

    val state: StateFlow<LiveState> = source.readings
        .scan(LiveState(null, 0, emptyList())) { acc, r ->
            LiveState(r, acc.fidelity or r.fidelityMask, (acc.history + r.rsrp).takeLast(56))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveState(null, 0, emptyList()))
}

@Composable
fun LiveSignalScreen(vm: LiveSignalViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val r = state.reading
    val inService = r?.serviceState == ServiceStateCode.IN_SERVICE
    val quality = signalQuality(r?.rsrp, inService)
    val color by animateColorAsState(healthColor(quality), tween(600), label = "health")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalGauge(
            quality = quality,
            color = color,
            dbm = r?.rsrp?.toString() ?: "—",
            status = statusWord(r, quality),
            sub = "${r?.carrier ?: "—"} · ${r?.networkType.label()}",
        )

        Sparkline(state.history, color, Modifier.fillMaxWidth().height(54.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("RSRQ", r?.rsrq?.let { "$it" }, "dB", Modifier.weight(1f))
            MetricTile("SINR", r?.sinr?.let { "$it" }, "dB", Modifier.weight(1f))
            MetricTile("BAND", r?.band?.let { "B$it" }, r?.arfcn?.let { "$it" } ?: "", Modifier.weight(1f))
        }

        CellCard(r, state.fidelity)
    }
}

@Composable
private fun SignalGauge(quality: Float, color: Color, dbm: String, status: String, sub: String) {
    val animated by animateFloatAsState(quality, tween(600, easing = FastOutSlowInEasing), label = "q")
    val track = MaterialTheme.colorScheme.outline
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(236.dp)) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val w = 15.dp.toPx()
            val stroke = Stroke(width = w, cap = StrokeCap.Round)
            val topLeft = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(track.copy(alpha = 0.30f), 135f, 270f, false, topLeft, arc, style = stroke)
            drawArc(color, 135f, 270f * animated.coerceIn(0f, 1f), false, topLeft, arc, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                dbm,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 46.sp, color = MaterialTheme.colorScheme.onBackground,
            )
            Text("dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(status, style = MaterialTheme.typography.labelLarge, color = color, letterSpacing = 3.sp)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Sparkline(values: List<Int?>, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        fun y(v: Int?): Float {
            val q = (((v ?: -125) + 120f) / 50f).coerceIn(0f, 1f)
            return size.height * (1f - q) * 0.92f + size.height * 0.04f
        }
        val stepX = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) line.moveTo(x, y(v)) else line.lineTo(x, y(v))
        }
        val area = Path().apply {
            addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawLine(track.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        drawPath(area, color.copy(alpha = 0.12f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MetricTile(label: String, value: String?, unit: String, modifier: Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                value ?: "—",
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CellCard(r: RadioReading?, fidelity: Int) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SERVING CELL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.CellTower, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatPair("CELL ID", r?.cellId?.toString(), Modifier.weight(1f))
                StatPair("PCI", r?.pci?.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatPair("TAC", r?.tac?.toString(), Modifier.weight(1f))
                StatPair("NEIGHBOURS", r?.neighborCount?.toString(), Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            FidelityMeter(fidelity)
        }
    }
}

@Composable
private fun StatPair(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(
            value ?: "—",
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FidelityMeter(fidelity: Int) {
    val labels = Fidelity.labels
    val present = labels.count { (bit, _) -> fidelity and bit != 0 }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("FIELDS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEach { (bit, _) ->
                val on = fidelity and bit != 0
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Teal else MaterialTheme.colorScheme.outline))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "$present/${labels.size}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
            color = if (present == labels.size) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusWord(r: RadioReading?, quality: Float): String = when {
    r == null -> "WAITING"
    r.serviceState == ServiceStateCode.OUT_OF_SERVICE || r.serviceState == ServiceStateCode.POWER_OFF -> "DEAD"
    quality < 0.34f -> "WEAK"
    else -> "ALIVE"
}

private fun NetworkType?.label(): String = when (this) {
    NetworkType.LTE -> "LTE"
    NetworkType.NR_NSA -> "5G (NSA)"
    NetworkType.NR_SA -> "5G (SA)"
    NetworkType.THREE_G_OR_OLDER -> "3G/older"
    NetworkType.NONE -> "no service"
    else -> "—"
}
