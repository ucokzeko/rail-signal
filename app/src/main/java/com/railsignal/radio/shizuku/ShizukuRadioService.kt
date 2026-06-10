package com.railsignal.radio.shizuku

import android.util.Log
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (shell uid 2000, which holds MODIFY_PHONE_STATE).
 * Forces a true radio re-registration by power-cycling ONLY the cellular radio:
 * `ITelephony.setRadioPower(false)` → hold → `setRadioPower(true)`. Wi-Fi / Bluetooth /
 * Wi-Fi-calling stay up — cleaner than airplane mode — and on a "cling" the power-on does a
 * fresh PLMN/cell acquisition, which is what actually moves the modem off a dying cell.
 *
 * Verified on-device (Samsung SM-F946B): the allowed-network-types approach the earlier build
 * used does NOT detach the serving cell; setRadioPower does (mVoiceRegState → POWER_OFF → re-
 * register) while Wi-Fi stays connected.
 *
 * Reached via the RAW ITelephony binder by reflection (ServiceManager → ITelephony$Stub), NOT
 * the typed TelephonyManager API: the bare Shizuku process has no TelephonyFrameworkInitializer,
 * so the typed API NPEs — but the raw binder, called with our shell identity, works.
 * setRadioPower(boolean) operates on the default phone, so no subId is needed.
 */
class ShizukuRadioService() : IShizukuRadio.Stub() {

    @Suppress("unused")
    constructor(context: android.content.Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun reRegister(holdMs: Long): Boolean {
        val tel = runCatching { iTelephony() }
            .onFailure { Log.e(TAG, "could not reach ITelephony", it) }
            .getOrNull() ?: return false

        return try {
            setRadioPower(tel, false)
            Log.i(TAG, "cellular radio OFF, holding ${holdMs}ms")
            Thread.sleep(holdMs)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "setRadioPower(off) failed", t)
            false
        } finally {
            // ALWAYS bring the radio back, even if the hold was interrupted or the off threw.
            runCatching { setRadioPower(tel, true) }
                .onFailure { Log.e(TAG, "setRadioPower(on) restore failed", it) }
            Log.i(TAG, "cellular radio ON")
        }
    }

    /** Raw ITelephony binder via reflection — the SDK has no compile-time handle on it. */
    private fun iTelephony(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "phone")
            ?: error("phone service not registered")
        val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
            .invoke(null, binder) ?: error("ITelephony.asInterface returned null")
    }

    private fun setRadioPower(tel: Any, on: Boolean) {
        tel.javaClass.getMethod("setRadioPower", Boolean::class.javaPrimitiveType)
            .invoke(tel, on)
    }

    private companion object {
        const val TAG = "RailSignalTest"
    }
}
