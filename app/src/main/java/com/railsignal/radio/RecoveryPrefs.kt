package com.railsignal.radio

import android.content.Context

/** How rail-signal responds to a detected cling. */
enum class RecoveryMode {
    AUTO,    // detect + auto re-register (Shizuku)
    NOTIFY,  // detect + tap-to-reset notification only — never touches the radio
    OFF,     // do nothing — stock Android behaviour
}

/** Persisted recovery mode (default AUTO). Battery/power-save guard lives in the service. */
object RecoveryPrefs {
    private const val FILE = "railsignal"
    private const val KEY_MODE = "recovery_mode"

    // Default NOTIFY: reliable with zero setup. Auto is opt-in (needs Shizuku running).
    fun mode(ctx: Context): RecoveryMode = runCatching {
        RecoveryMode.valueOf(sp(ctx).getString(KEY_MODE, RecoveryMode.NOTIFY.name)!!)
    }.getOrDefault(RecoveryMode.NOTIFY)

    fun setMode(ctx: Context, mode: RecoveryMode) =
        sp(ctx).edit().putString(KEY_MODE, mode.name).apply()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
