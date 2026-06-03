package com.railsignal.radio.shizuku

import android.content.Context
import android.util.Log
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (shell uid 2000). Forces a re-register by briefly
 * dropping NR from the allowed network types (breaks the 5G-NSA cling + triggers a RAT
 * re-evaluation), then restoring the user's exact previous types.
 *
 * Uses `cmd phone` via exec rather than the typed TelephonyManager API: the bare Shizuku
 * process has no TelephonyFrameworkInitializer, so the in-process API NPEs — but `cmd phone`
 * runs in the phone process with our shell identity and works. Needs the `-s 0` slot flag and
 * a binary-string bitmask (verified on this device).
 */
class ShizukuRadioService() : IShizukuRadio.Stub() {

    @Suppress("unused")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun reRegister(holdMs: Long): Boolean {
        val current = exec("cmd phone get-allowed-network-types-for-users") ?: return false
        val saved = parseMask(current)
        if (saved == 0L) {
            Log.e(TAG, "could not parse allowed types: '$current'")
            return false
        }
        var target = saved and (1L shl NR_BIT).inv() // drop NR
        if (target == saved) target = 1L shl LTE_BIT  // no NR present → fall back to LTE-only

        return try {
            exec("cmd phone set-allowed-network-types-for-users -s 0 ${bin(target)}")
            Log.i(TAG, "applied ${bin(target)} (from ${bin(saved)}), holding ${holdMs}ms")
            Thread.sleep(holdMs)
            true
        } finally {
            exec("cmd phone set-allowed-network-types-for-users -s 0 ${bin(saved)}") // ALWAYS restore
            Log.i(TAG, "restored ${bin(saved)}")
        }
    }

    private fun exec(cmd: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    }.onFailure { Log.e(TAG, "exec failed: $cmd", it) }.getOrNull()

    private fun parseMask(names: String): Long {
        var mask = 0L
        names.substringAfterLast(':').split("|").forEach { token ->
            NAME_TO_TYPE[token.trim()]?.let { mask = mask or (1L shl (it - 1)) }
        }
        return mask
    }

    private fun bin(mask: Long): String = java.lang.Long.toBinaryString(mask)

    private companion object {
        const val TAG = "RailSignalTest"
        const val NR_BIT = 19   // NETWORK_TYPE_NR(20) - 1
        const val LTE_BIT = 12  // NETWORK_TYPE_LTE(13) - 1
        val NAME_TO_TYPE = mapOf(
            "GPRS" to 1, "EDGE" to 2, "UMTS" to 3, "CDMA" to 4, "EVDO_0" to 5, "EVDO_A" to 6,
            "1xRTT" to 7, "HSDPA" to 8, "HSUPA" to 9, "HSPA" to 10, "IDEN" to 11, "EVDO_B" to 12,
            "LTE" to 13, "EHRPD" to 14, "HSPA+" to 15, "GSM" to 16, "TD_SCDMA" to 17, "IWLAN" to 18,
            "LTE_CA" to 19, "NR" to 20,
        )
    }
}
