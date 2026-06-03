package com.railsignal

import android.app.Application
import androidx.room.Room
import com.railsignal.data.RailSignalDatabase
import com.railsignal.telephony.TelephonyCallbackAdapter
import com.railsignal.telephony.TelephonySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny manual service-locator. Keeps the app dependency-free of a DI framework for P0/P1.
 */
class RailSignalApp : Application() {

    val db: RailSignalDatabase by lazy {
        Room.databaseBuilder(this, RailSignalDatabase::class.java, "railsignal.db")
            // Schema bumped to v2 (recovery_events). Existing trips are wiped on upgrade —
            // acceptable for this dev build; data is exported to CSV anyway.
            .fallbackToDestructiveMigration()
            .build()
    }

    /** Shared so the live screen and the recording service reuse one telephony stream source. */
    val telephony: TelephonySource by lazy { TelephonyCallbackAdapter(this) }

    /** Outlives any single Service — used for writes that must finish after stopSelf(). */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: RailSignalApp
            private set
    }
}
