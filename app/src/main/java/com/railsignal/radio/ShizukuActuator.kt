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
 * Forces a radio re-registration by cycling ONLY the cellular radio (setRadioPower off->on)
 * through a Shizuku user-service running as shell. Wi-Fi / Bluetooth / Wi-Fi-calling stay up —
 * cleaner than airplane mode. No root; needs the Shizuku daemon running + permission granted.
 *
 * Unverified on-device until tested — degrades to guided via the watchdog if anything fails.
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

    override suspend fun recover(strategy: RecoveryStrategy): RecoveryResult {
        if (!ready()) return RecoveryResult(false, "Shizuku not ready")
        val svc = bind() ?: return RecoveryResult(false, "user service bind failed")
        return runCatching {
            // Blocks ~NETWORK_HOLD_MS inside the user service (LTE-only hold, then restore).
            val ok = svc.reRegister(NETWORK_HOLD_MS)
            if (ok) RecoveryResult(true, "network-type re-register") else RecoveryResult(false, "reRegister failed")
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
        const val NETWORK_HOLD_MS = 5_000L
    }
}
