package com.railsignal.trip

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.railsignal.service.CommuteForegroundService
import com.railsignal.telephony.RadioReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between the recording service and the UI. The service writes live
 * status here; Compose observes it. Start/Stop are routed to the foreground service.
 */
object RecordingController {

    data class Status(
        val isRecording: Boolean = false,
        val tripId: Long? = null,
        val startTs: Long? = null,
        val sampleCount: Int = 0,
        val latest: RadioReading? = null,
        val recoveryMode: String = "—",   // "Auto · re-register" | "Guided · tap to reset"
        val recovering: Boolean = false,  // a recovery attempt is in flight
        val lastRecovery: String? = null, // e.g. "RADIO_CYCLE → RESTORED (8s)"
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = Status()
    }

    fun start(context: Context) {
        val intent = Intent(context, CommuteForegroundService::class.java)
            .setAction(CommuteForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, CommuteForegroundService::class.java)
            .setAction(CommuteForegroundService.ACTION_STOP)
        // Already-running service; a plain startService delivers the STOP action.
        context.startService(intent)
    }
}
