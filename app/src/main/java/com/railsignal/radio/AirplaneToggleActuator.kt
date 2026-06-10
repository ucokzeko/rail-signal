package com.railsignal.radio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Forces a radio re-registration by cycling airplane mode, using WRITE_SECURE_SETTINGS
 * (grantable without root via `adb shell pm grant`). This is the "re-register like iOS"
 * action for the cling pattern.
 *
 * Honest caveat: writing AIRPLANE_MODE_ON moves the radio on some builds but not all (the
 * state-change broadcast is system-only). We attempt the write + a best-effort broadcast;
 * the caller verifies whether service actually came back and escalates to guided if not.
 */
class AirplaneToggleActuator(private val ctx: Context) : RadioActuator {

    override fun capabilities() = Capabilities(
        canAuto = hasPermission(),
        label = if (hasPermission()) "Auto · airplane cycle" else "needs WRITE_SECURE_SETTINGS",
    )

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED

    fun isAirplaneOn(): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    override suspend fun recover(strategy: RecoveryStrategy, abortIf: () -> Boolean): RecoveryResult {
        if (!hasPermission()) return RecoveryResult(false, "no WRITE_SECURE_SETTINGS")
        if (abortIf()) return RecoveryResult(false, "call active — deferred", deferred = true)
        return try {
            setAirplane(true)
            delay(AIRPLANE_OFF_MS)
            setAirplane(false)
            RecoveryResult(true, "airplane cycled")
        } catch (e: SecurityException) {
            RecoveryResult(false, "denied: ${e.message}")
        }
    }

    private fun setAirplane(on: Boolean) {
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
        // Protected broadcast — will throw for a non-system app; harmless to try.
        runCatching {
            ctx.sendBroadcast(
                Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on),
            )
        }
    }

    companion object {
        const val AIRPLANE_OFF_MS = 6_000L
    }
}
