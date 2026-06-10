package com.railsignal.radio

/**
 * The single privileged-action seam (ADR 0001). Everything that tries to act on the radio —
 * automatic radio-power cycle, or a guided user prompt — sits behind this port, so the rest of
 * the app never assumes a privileged path exists or works.
 *
 * [abortIf] is the last-instant safety gate: a destructive actuator must evaluate it after any
 * slow setup (e.g. binding the Shizuku service) but immediately before it touches the radio, and
 * skip the action — returning a [RecoveryResult.deferred] result — if it returns true. The
 * caller wires this to live call state so a call that starts mid-recovery is never dropped.
 */
interface RadioActuator {
    fun capabilities(): Capabilities
    suspend fun recover(strategy: RecoveryStrategy, abortIf: () -> Boolean = { false }): RecoveryResult
}

enum class RecoveryStrategy { RADIO_CYCLE, AIRPLANE_CYCLE, USER_GUIDED }

data class Capabilities(val canAuto: Boolean, val label: String)

/** [deferred] = deliberately skipped (e.g. a call was active), as opposed to attempted-and-failed. */
data class RecoveryResult(val attempted: Boolean, val note: String, val deferred: Boolean = false)
