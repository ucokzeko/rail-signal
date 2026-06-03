package com.railsignal.location

import kotlinx.coroutines.flow.Flow

data class LocationFix(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

interface LocationSource {
    val updates: Flow<LocationFix>
}
