package com.railsignal.radio

/**
 * The single privileged-action seam (ADR 0001). Everything that tries to act on the radio —
 * automatic airplane cycle, or a guided user prompt — sits behind this port, so the rest of
 * the app never assumes a privileged path exists or works.
 */
interface RadioActuator {
    fun capabilities(): Capabilities
    suspend fun recover(strategy: RecoveryStrategy): RecoveryResult
}

enum class RecoveryStrategy { RADIO_CYCLE, AIRPLANE_CYCLE, USER_GUIDED }

data class Capabilities(val canAuto: Boolean, val label: String)

data class RecoveryResult(val attempted: Boolean, val note: String)
