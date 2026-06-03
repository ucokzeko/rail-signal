package com.railsignal.data

/** Aggregate signal profile for one trip (computed from its samples, no schema change). */
data class TripStats(
    val total: Int,
    val alive: Int,
    val weak: Int,
    val dead: Int,
    val avgRsrp: Double?,
    val minRsrp: Int?,
)
