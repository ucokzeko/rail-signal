package com.railsignal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.railsignal.R
import com.railsignal.RailSignalApp
import com.railsignal.data.RecoveryEvent
import com.railsignal.data.Sample
import com.railsignal.data.Trip
import com.railsignal.location.FusedLocationAdapter
import com.railsignal.location.LocationFix
import com.railsignal.radio.GuidedPromptActuator
import com.railsignal.radio.RecoveryMode
import com.railsignal.radio.RecoveryPrefs
import com.railsignal.radio.RecoveryStrategy
import com.railsignal.radio.ShizukuActuator
import com.railsignal.telephony.RadioReading
import com.railsignal.trip.RecordingController
import com.railsignal.trip.toSample
import com.railsignal.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records a trip AND runs the cling-recovery watchdog. Detection does NOT trust ServiceState
 * (which reports IN_SERVICE through real blackouts); it uses data validation, RSRP, and
 * telephony-callback silence. On a sustained no-usable-connection it cycles the radio
 * (airplane) to force a re-register — the "iPhone re-registers" behaviour — verifying the
 * result and escalating to a guided prompt if the auto cycle didn't take.
 */
class CommuteForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null

    @Volatile private var latestReading: RadioReading? = null
    @Volatile private var latestReadingTs = 0L
    @Volatile private var latestFix: LocationFix? = null

    // watchdog state
    private var stuckSince = 0L
    private var lastRecoveryAt = 0L
    @Volatile private var recovering = false
    @Volatile private var inCall = false

    private val shizuku by lazy { ShizukuActuator(this) }
    private val guided by lazy { GuidedPromptActuator(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); START_NOT_STICKY }
            else -> { startRecording(); START_STICKY }
        }

    private fun startRecording() {
        if (collectJob != null) return
        startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val app = applicationContext as RailSignalApp
        val tripDao = app.db.tripDao()
        val sampleDao = app.db.sampleDao()
        val location = FusedLocationAdapter(this)
        val mode = recoveryModeLabel()

        collectJob = scope.launch {
            val now = System.currentTimeMillis()
            val tripId = tripDao.insert(Trip(startTs = now))
            RecordingController.update {
                it.copy(isRecording = true, tripId = tripId, startTs = now, sampleCount = 0,
                    latest = null, recoveryMode = mode, recovering = false, lastRecovery = null)
            }

            launch { location.updates.collect { latestFix = it } }

            // Call-aware recovery: a re-register can't take effect on the cell carrying an active
            // voice bearer (the modem holds it to protect the call), so we defer while in-call and
            // fire the instant the call ends — exactly when the modem will honour it.
            launch {
                app.telephony.inCall.collect { nowInCall ->
                    val callEnded = inCall && !nowInCall
                    inCall = nowInCall
                    if (callEnded) watchdog(tripId, ignoreCooldown = true)
                }
            }

            var count = 0
            var lastState = ""
            // Capture precise transitions immediately.
            launch {
                app.telephony.readings.collect { r ->
                    latestReading = r
                    latestReadingTs = System.currentTimeMillis()
                    RecordingController.update { it.copy(latest = r) }
                    if (r.serviceState.name != lastState) {
                        lastState = r.serviceState.name
                        sampleDao.insert(r.toSample(tripId, latestFix, !isValidated()))
                        count++
                        r.carrier?.let { c -> tripDao.setCarrierIfNull(tripId, c) }
                        RecordingController.update { it.copy(sampleCount = count) }
                        watchdog(tripId)
                    }
                }
            }

            // Heartbeat: a fixed-cadence sample + watchdog tick, independent of callbacks.
            // This is what captures blackouts (callback silence) the old sampler went blind to.
            while (isActive) {
                delay(HEARTBEAT_MS)
                val age = System.currentTimeMillis() - latestReadingTs
                val silent = latestReadingTs == 0L || age > SILENCE_MS
                sampleDao.insert(heartbeatSample(tripId, if (silent) null else latestReading, silent))
                count++
                RecordingController.update { it.copy(sampleCount = count) }
                updateNotification(statusLine(silent))
                watchdog(tripId)
            }
        }
    }

    /** Build a sample on the heartbeat. When the radio has gone silent we record a SILENT marker. */
    private fun heartbeatSample(tripId: Long, reading: RadioReading?, silent: Boolean): Sample {
        val stall = !isValidated()
        if (!silent && reading != null) {
            return reading.toSample(tripId, latestFix, stall)
        }
        val net = reading?.networkType?.name ?: "UNKNOWN"
        return Sample(
            tripId = tripId, tsMs = System.currentTimeMillis(),
            lat = latestFix?.lat, lon = latestFix?.lon,
            accuracyM = latestFix?.accuracyM, speedMps = latestFix?.speedMps,
            carrier = reading?.carrier, networkType = net, nsa5g = false,
            rsrp = null, rsrq = null, sinr = null, band = null, arfcn = null, pci = null,
            tac = null, cellId = null, neighborCount = 0,
            serviceState = if (silent) "SILENT" else (reading?.serviceState?.name ?: "UNKNOWN"),
            dataStallInferred = stall, fidelityMask = 0,
        )
    }

    /**
     * Decide whether we're stuck, and fire recovery once the stuck state is sustained.
     * [ignoreCooldown] is set on the call-ended path: that's a one-shot, deliberately-timed
     * trigger, so it skips the inter-recovery cooldown.
     */
    private fun watchdog(tripId: Long, ignoreCooldown: Boolean = false) {
        if (recovering) return
        val now = System.currentTimeMillis()
        if (isAirplaneOn()) { stuckSince = 0L; return } // user has airplane on — don't fight it

        val mode = effectiveMode()
        if (mode == RecoveryMode.OFF) { stuckSince = 0L; return } // off / battery guard — keep monitoring, don't act

        val validated = isValidated()
        val age = now - latestReadingTs
        val silent = latestReadingTs != 0L && age > SILENCE_MS
        val rsrp = latestReading?.rsrp
        val weak = rsrp != null && rsrp <= RSRP_DEAD
        val usable = validated && !silent && !weak

        if (usable) { stuckSince = 0L; return }
        if (stuckSince == 0L) stuckSince = now
        if (now - stuckSince < STUCK_MS) return
        // On a call the re-register is a no-op (modem won't release the call-bearing cell) — keep
        // accumulating stuckSince and let the call-ended trigger fire it the moment the call drops.
        if (inCall) return
        if (!ignoreCooldown && now - lastRecoveryAt < COOLDOWN_MS) return

        val base = when {
            silent -> "SILENCE"
            !validated -> "NO_DATA"
            weak -> "WEAK"
            else -> "STUCK"
        }
        val trigger = if (ignoreCooldown) "${base}_POSTCALL" else base
        lastRecoveryAt = now
        stuckSince = 0L
        val auto = mode == RecoveryMode.AUTO && shizuku.capabilities().canAuto
        scope.launch { runRecovery(tripId, trigger, auto) }
    }

    private suspend fun runRecovery(tripId: Long, trigger: String, auto: Boolean) {
        // The radio power-cycle only ever runs inside an active logging session — never in the
        // background. (The watchdog only ticks while recording, so this is belt-and-suspenders.)
        if (!RecordingController.status.value.isRecording) return
        recovering = true
        val app = applicationContext as RailSignalApp
        val recoveryDao = app.db.recoveryDao()
        val action = if (auto) "RADIO_CYCLE" else "GUIDED"
        RecordingController.update { it.copy(recovering = true) }
        val evId = recoveryDao.insert(
            RecoveryEvent(tripId = tripId, tsMs = System.currentTimeMillis(),
                trigger = trigger, action = action, outcome = "PENDING", recoveryMs = null),
        )
        updateNotification("Recovering ($trigger)…")
        val t0 = System.currentTimeMillis()

        // abortIf = { inCall } is the last-instant gate: if a call slipped in while we were
        // binding the Shizuku service, skip the cut entirely so we never drop the call.
        val res = if (auto) {
            shizuku.recover(RecoveryStrategy.RADIO_CYCLE, abortIf = { inCall })
        } else {
            guided.recover(RecoveryStrategy.USER_GUIDED)
        }

        if (res.deferred) {
            // Deliberately skipped (call active) — not a failure. Don't nag with guided; the
            // call-ended trigger re-fires recovery once the call drops.
            recoveryDao.finish(evId, "DEFERRED_CALL", null)
            RecordingController.update { it.copy(recovering = false, lastRecovery = "$action → deferred (on call)") }
            recovering = false
            return
        }

        // Watch for the connection to come back.
        var restoredMs: Long? = null
        val deadline = System.currentTimeMillis() + RESTORE_WATCH_MS
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            if (isValidated() && !isAirplaneOn()) {
                restoredMs = System.currentTimeMillis() - t0
                break
            }
        }
        val outcome = when {
            restoredMs != null -> "RESTORED"
            !res.attempted -> "FAILED"
            else -> "NO_CHANGE"
        }
        recoveryDao.finish(evId, outcome, restoredMs)
        // Auto cycle didn't take → fall back to nudging the user.
        if (auto && outcome != "RESTORED") guided.recover(RecoveryStrategy.USER_GUIDED)

        val summary = "$action → $outcome" + (restoredMs?.let { " (${it / 1000}s)" } ?: "")
        RecordingController.update { it.copy(recovering = false, lastRecovery = summary) }
        recovering = false
    }

    private fun stopRecording() {
        val snapshot = RecordingController.status.value
        collectJob?.cancel()
        collectJob = null
        val app = applicationContext as RailSignalApp
        // appScope (not the cancelled service scope) so these complete after stopSelf().
        app.appScope.launch {
            snapshot.tripId?.let { app.db.tripDao().finish(it, System.currentTimeMillis(), snapshot.sampleCount) }
            // A recovery cut off mid-flight by the stop would linger as PENDING; settle it.
            app.db.recoveryDao().markPendingInterrupted()
        }
        RecordingController.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isValidated(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isAirplaneOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** User mode, forced to OFF when battery is low or power-saving is on (the battery guard). */
    private fun effectiveMode(): RecoveryMode {
        val mode = RecoveryPrefs.mode(this)
        if (mode == RecoveryMode.OFF) return RecoveryMode.OFF
        if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return RecoveryMode.OFF
        val level = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (level in 1..LOW_BATTERY_PCT) return RecoveryMode.OFF
        return mode
    }

    private fun recoveryModeLabel(): String = when (effectiveMode()) {
        RecoveryMode.OFF -> "Off (stock / battery guard)"
        RecoveryMode.NOTIFY -> "Notify only"
        RecoveryMode.AUTO ->
            if (shizuku.capabilities().canAuto) "Auto · re-register" else "Auto → notify (Shizuku off)"
    }

    private fun statusLine(silent: Boolean): String {
        val r = latestReading
        val sig = if (silent) "SILENT" else "RSRP ${r?.rsrp ?: "—"}"
        val sc = RecordingController.status.value.sampleCount
        val callNote = if (inCall) " · on call (recovery paused)" else ""
        return "$sc pts · $sig · ${if (isValidated()) "data ok" else "no data"}$callNote"
    }

    private fun ensureChannel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL_ID
    }

    private fun buildNotification(text: String): Notification {
        val channelId = ensureChannel()
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CommuteForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("rail-signal · Recording")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_signal)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)
            .setContentIntent(openPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.railsignal.action.START"
        const val ACTION_STOP = "com.railsignal.action.STOP"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "recording"

        private const val HEARTBEAT_MS = 4_000L     // fixed-cadence sampling + watchdog tick
        private const val SILENCE_MS = 15_000L      // no telephony callback this long => blackout
        private const val RSRP_DEAD = -120          // dBm; clinging to a dying cell
        private const val STUCK_MS = 25_000L        // sustained no-usable-connection => recover
        private const val COOLDOWN_MS = 60_000L     // min gap between auto recoveries
        private const val RESTORE_WATCH_MS = 25_000L // watch window for service to return
        private const val LOW_BATTERY_PCT = 15      // at/below this, recovery auto-pauses
    }
}
