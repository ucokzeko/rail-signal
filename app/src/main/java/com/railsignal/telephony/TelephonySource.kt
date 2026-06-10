package com.railsignal.telephony

import kotlinx.coroutines.flow.Flow

/**
 * Narrow port: a stream of live radio readings plus a stream of call-active state. All
 * TelephonyCallback / CellInfo registration, permission gating, and NSA-5G inference is
 * hidden in the adapter.
 */
interface TelephonySource {
    val readings: Flow<RadioReading>

    /** `true` while a voice call is up (ringing or off-hook), `false` when idle. */
    val inCall: Flow<Boolean>
}
