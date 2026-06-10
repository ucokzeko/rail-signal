package com.railsignal.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.railsignal.data.RecoveryEvent
import com.railsignal.radio.RecoveryMode
import com.railsignal.radio.RecoveryPrefs
import com.railsignal.radio.RecoveryStrategy
import com.railsignal.radio.ShizukuActuator
import com.railsignal.ui.theme.SignalDead
import com.railsignal.ui.theme.SignalWeak
import com.railsignal.ui.theme.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCard(events: List<RecoveryEvent>) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(RecoveryPrefs.mode(context)) }
    val ready = shizukuReady()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val status = when (mode) {
        RecoveryMode.OFF -> "Stock Android. rail-signal won't touch the radio."
        RecoveryMode.NOTIFY -> "Detects a stuck cell and notifies you to reset. No radio action."
        RecoveryMode.AUTO ->
            if (ready) "Auto re-register via Shizuku when the signal clings." else "Shizuku not ready, falls back to notify."
    }
    val options = listOf(RecoveryMode.AUTO to "Auto", RecoveryMode.NOTIFY to "Notify", RecoveryMode.OFF to "Off")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CLING RECOVERY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; RecoveryPrefs.setMode(context, m) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }

            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("Auto-pauses on low battery or power-saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (mode == RecoveryMode.AUTO && !ready) {
                OutlinedButton(onClick = { requestShizuku() }) { Text("Grant Shizuku") }
            }

            // Debug builds only: fire the real recovery path (ShizukuActuator → setRadioPower)
            // on demand, so the cellular cycle can be verified without waiting for a dead zone.
            // Honours the same call guard as auto-recovery — it won't cut an active call.
            if (isDebuggable(context)) {
                val scope = rememberCoroutineScope()
                OutlinedButton(
                    enabled = ready,
                    onClick = {
                        Toast.makeText(context, "Testing radio cycle…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ShizukuActuator(context).recover(
                                    RecoveryStrategy.RADIO_CYCLE,
                                    abortIf = { deviceInCall(context) },
                                )
                            }
                            Toast.makeText(context, "Test recovery: ${r.note}", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("Test recovery (radio cycle)") }
            }

            if (events.isNotEmpty()) {
                Text("RECENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                events.take(6).forEach { e -> RecoveryRow(e, fmt.format(Date(e.tsMs))) }
            }
        }
    }
}

@Composable
private fun RecoveryRow(e: RecoveryEvent, time: String) {
    val color = when (e.outcome) {
        "RESTORED" -> Teal
        "NO_CHANGE" -> SignalWeak
        "FAILED" -> SignalDead
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${e.trigger}→${e.action}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        val tail = e.outcome + (e.recoveryMs?.let { " ${it / 1000}s" } ?: "")
        Text(tail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun isDebuggable(context: android.content.Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

@Suppress("DEPRECATION")
private fun deviceInCall(context: android.content.Context): Boolean = runCatching {
    context.getSystemService(TelephonyManager::class.java)?.callState != TelephonyManager.CALL_STATE_IDLE
}.getOrDefault(false)

private fun shizukuReady(): Boolean = runCatching {
    Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

private fun requestShizuku() {
    runCatching {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
        }
    }
}
