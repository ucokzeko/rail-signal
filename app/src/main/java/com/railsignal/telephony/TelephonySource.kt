package com.railsignal.telephony

import kotlinx.coroutines.flow.Flow

/**
 * Narrow port: a stream of live radio readings. All TelephonyCallback / CellInfo
 * registration, permission gating, and NSA-5G inference is hidden in the adapter.
 */
interface TelephonySource {
    val readings: Flow<RadioReading>
}
