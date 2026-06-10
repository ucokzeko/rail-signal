package com.railsignal.radio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.railsignal.radio.shizuku.IShizukuRadio
import com.railsignal.radio.shizuku.ShizukuRadioService
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * Forces a radio re-registration by power-cycling ONLY the cellular radio (setRadioPower
 * off → hold → on; see [ShizukuRadioService]) through a Shizuku user-service running as shell.
 * Wi-Fi / Bluetooth / Wi-Fi-calling stay up, cleaner than airplane mode. No root; needs the
 * Shizuku daemon running + permission granted.
 *
 * Degrades to guided via the watchdog if anything fails.
 */
class ShizukuActuator(private val ctx: Context) : RadioActuator {

    @Volatile private var service: IShizukuRadio? = null

    private val args: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShizukuRadioService::class.java.name))
            .daemon(false)
            .processNameSuffix("radio")
            .version(5) // bumped to force Shizuku to reload the user-service code

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShizukuRadio.Stub.asInterface(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun capabilities() = Capabilities(
        canAuto = ready(),
        label = if (ready()) "Auto · re-register (Shizuku)" else "Shizuku not ready",
    )

    override suspend fun recover(strategy: RecoveryStrategy, abortIf: () -> Boolean): RecoveryResult {
        if (!ready()) return RecoveryResult(false, "Shizuku not ready")
        val svc = bind() ?: return RecoveryResult(false, "user service bind failed")
        // bind() can take seconds; this is the last instant before we cut the radio, so honour
        // the abort gate here (e.g. a call started meanwhile) and skip rather than drop it.
        if (abortIf()) return RecoveryResult(false, "call active — deferred", deferred = true)
        return runCatching {
            // Blocks ~RADIO_OFF_HOLD_MS inside the user service (radio off, then restore on).
            val ok = svc.reRegister(RADIO_OFF_HOLD_MS)
            if (ok) RecoveryResult(true, "radio power-cycle") else RecoveryResult(false, "reRegister failed")
        }.getOrElse { RecoveryResult(false, "failed: ${it.message}") }
    }

    private suspend fun bind(): IShizukuRadio? {
        service?.let { return it }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { return null }
        // Wait for onServiceConnected.
        repeat(25) {
            service?.let { return it }
            delay(200)
        }
        return service
    }

    companion object {
        // How long to hold the cellular radio OFF before powering it back on. Samsung takes
        // ~5–6s to actually reach POWER_OFF after setRadioPower(false), so this must be long
        // enough that the radio is genuinely off for a few seconds (forcing a fresh acquisition
        // on power-on) — measured on-device.
        const val RADIO_OFF_HOLD_MS = 9_000L
    }
}
