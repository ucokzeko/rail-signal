package com.railsignal.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DirectionsTransit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.railsignal.RailSignalApp
import com.railsignal.data.Trip
import com.railsignal.data.TripStats
import com.railsignal.export.TripExporter
import com.railsignal.trip.RecordingController
import com.railsignal.ui.theme.SignalDead
import com.railsignal.ui.theme.SignalWeak
import com.railsignal.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as RailSignalApp
    val status by RecordingController.status.collectAsStateWithLifecycle()
    val trips by remember { app.db.tripDao().all() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val recoveryEvents by remember { app.db.recoveryDao().recent() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BatteryHint(context) }

        item {
            if (status.isRecording) RecordingPanel(status) else StartButton { RecordingController.start(context) }
        }
        item {
            if (status.isRecording) {
                FilledTonalButton(
                    onClick = { RecordingController.stop(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Stop, null); Text("  Stop trip")
                }
            }
        }

        item { RecoveryCard(recoveryEvents) }

        item {
            OutlinedButton(
                onClick = {
                    exporting = true
                    scope.launch {
                        val uri = TripExporter.exportAllCsv(context)
                        exporting = false
                        if (uri != null) context.shareCsv(uri) else toast(context, "No data yet")
                    }
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.IosShare, null)
                Text(if (exporting) "  Exporting…" else "  Export CSV")
            }
        }

        item { Text("TRIPS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (trips.isEmpty()) {
            item { EmptyTrips() }
        } else {
            items(trips) { TripCard(it) }
        }
    }
}

@Composable
private fun StartButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
        Text("  Start trip", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecordingPanel(status: RecordingController.Status) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.isRecording) {
        while (status.isRecording) { now = System.currentTimeMillis(); delay(1000) }
    }
    val elapsed = status.startTs?.let { now - it } ?: 0L

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Teal.copy(alpha = alpha)))
                Text("RECORDING · ${status.recoveryMode}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                fmtElapsed(elapsed),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${status.sampleCount} pts · RSRP ${status.latest?.rsrp ?: "—"} · ${status.latest?.serviceState?.name ?: "—"}",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rec = if (status.recovering) "re-registering…" else status.lastRecovery?.let { "last: $it" }
            if (rec != null) Text(rec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TripCard(trip: Trip) {
    val app = LocalContext.current.applicationContext as RailSignalApp
    // Re-query when the trip finishes (endTs/sampleCount change) so a card composed
    // mid-recording shows final stats instead of a stale snapshot.
    val stats by produceState<TripStats?>(null, trip.id, trip.endTs, trip.sampleCount) {
        value = runCatching { app.db.sampleDao().statsForTrip(trip.id) }.getOrNull()
    }
    val fmt = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }
    val duration = trip.endTs?.let { fmtElapsed(it - trip.startTs) } ?: "in progress"
    val samples = stats?.total ?: trip.sampleCount
    val avg = stats?.avgRsrp?.let { " · avg ${it.roundToInt()} dBm" } ?: ""
    val deadPct = stats?.let { if (it.total > 0) 100 * it.dead / it.total else 0 } ?: 0

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt.format(Date(trip.startTs)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(duration, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            HealthBar(stats, Modifier.fillMaxWidth().height(6.dp))
            Text(
                "${trip.carrier ?: "—"} · $samples samples$avg" + if (deadPct > 0) " · $deadPct% dead" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthBar(stats: TripStats?, modifier: Modifier) {
    val s = stats
    Row(modifier.clip(RoundedCornerShape(3.dp))) {
        if (s == null || s.total <= 0) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        } else {
            if (s.alive > 0) Box(Modifier.weight(s.alive.toFloat()).fillMaxHeight().background(Teal))
            if (s.weak > 0) Box(Modifier.weight(s.weak.toFloat()).fillMaxHeight().background(SignalWeak))
            if (s.dead > 0) Box(Modifier.weight(s.dead.toFloat()).fillMaxHeight().background(SignalDead))
        }
    }
}

@Composable
private fun EmptyTrips() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.DirectionsTransit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("No trips yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Start a trip when you board.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BatteryHint(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    if (ignoring) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.BatteryAlert, null, tint = MaterialTheme.colorScheme.error)
                Text("One UI may kill recording in the background", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Set battery to Unrestricted and add rail-signal to \"Never sleeping apps\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
            }) { Text("Disable battery optimisation") }
        }
    }
}

private fun fmtElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Context.shareCsv(uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, "Share rail-signal CSV"))
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
