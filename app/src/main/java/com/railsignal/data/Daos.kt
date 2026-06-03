package com.railsignal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Query("UPDATE trips SET endTs = :endTs, sampleCount = :count WHERE id = :id")
    suspend fun finish(id: Long, endTs: Long, count: Int)

    @Query("UPDATE trips SET carrier = :carrier WHERE id = :id AND carrier IS NULL")
    suspend fun setCarrierIfNull(id: Long, carrier: String?)

    @Query("SELECT * FROM trips ORDER BY startTs DESC")
    fun all(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): Trip?
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: Sample)

    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY tsMs")
    suspend fun forTrip(tripId: Long): List<Sample>

    @Query("SELECT * FROM samples ORDER BY tsMs")
    suspend fun all(): List<Sample>

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun totalCount(): Int

    @Query(
        """
        SELECT COUNT(*) AS total,
          IFNULL(SUM(CASE WHEN serviceState = 'IN_SERVICE' AND rsrp >= -105 THEN 1 ELSE 0 END), 0) AS alive,
          IFNULL(SUM(CASE WHEN serviceState = 'IN_SERVICE' AND rsrp < -105 AND rsrp > -118 THEN 1 ELSE 0 END), 0) AS weak,
          IFNULL(SUM(CASE WHEN serviceState <> 'IN_SERVICE' OR rsrp IS NULL OR rsrp <= -118 THEN 1 ELSE 0 END), 0) AS dead,
          AVG(rsrp) AS avgRsrp, MIN(rsrp) AS minRsrp
        FROM samples WHERE tripId = :tripId
        """,
    )
    suspend fun statsForTrip(tripId: Long): TripStats
}

@Dao
interface RecoveryDao {
    @Insert
    suspend fun insert(event: RecoveryEvent): Long

    @Query("UPDATE recovery_events SET outcome = :outcome, recoveryMs = :recoveryMs WHERE id = :id")
    suspend fun finish(id: Long, outcome: String, recoveryMs: Long?)

    @Query("UPDATE recovery_events SET outcome = 'INTERRUPTED' WHERE outcome = 'PENDING'")
    suspend fun markPendingInterrupted()

    @Query("SELECT * FROM recovery_events ORDER BY tsMs DESC LIMIT 30")
    fun recent(): Flow<List<RecoveryEvent>>

    @Query("SELECT * FROM recovery_events ORDER BY tsMs")
    suspend fun all(): List<RecoveryEvent>
}
