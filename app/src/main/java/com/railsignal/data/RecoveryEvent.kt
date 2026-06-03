package com.railsignal.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A recovery attempt: why it fired, what action ran, and whether service came back. */
@Entity(tableName = "recovery_events")
data class RecoveryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val tsMs: Long,
    val trigger: String,   // SILENCE | NO_DATA | WEAK | STUCK
    val action: String,    // AIRPLANE_CYCLE | GUIDED
    val outcome: String,   // PENDING | RESTORED | NO_CHANGE | FAILED
    val recoveryMs: Long?,  // action -> data validated again
)
