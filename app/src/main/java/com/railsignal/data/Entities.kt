package com.railsignal.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTs: Long,
    val endTs: Long? = null,
    val carrier: String? = null,
    val direction: String = "UNKNOWN",
    val sampleCount: Int = 0,
)

@Entity(
    tableName = "samples",
    indices = [Index("tripId")],
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val tsMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val speedMps: Float?,
    val carrier: String?,
    val networkType: String,
    val nsa5g: Boolean,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val band: Int?,
    val arfcn: Int?,
    val pci: Int?,
    val tac: Int?,
    val cellId: Long?,
    val neighborCount: Int,
    val serviceState: String,
    val dataStallInferred: Boolean,
    val fidelityMask: Int,
)
