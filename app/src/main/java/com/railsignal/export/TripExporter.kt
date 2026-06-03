package com.railsignal.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.railsignal.RailSignalApp
import com.railsignal.data.Sample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports all recorded samples to a CSV in the app's external files dir and returns a
 * FileProvider Uri suitable for an ACTION_SEND share. Returns null if there's nothing to export.
 */
object TripExporter {

    private const val HEADER =
        "trip_id,ts_ms,iso_time,lat,lon,accuracy_m,speed_mps,carrier,network_type,nsa5g," +
            "rsrp,rsrq,sinr,band,arfcn,pci,tac,cell_id,neighbors,service_state,data_stall,fidelity_mask"

    suspend fun exportAllCsv(context: Context): Uri? {
        val app = context.applicationContext as RailSignalApp
        val samples = app.db.sampleDao().all()
        if (samples.isEmpty()) return null

        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, "railsignal_${System.currentTimeMillis()}.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        file.bufferedWriter().use { w ->
            w.appendLine(HEADER)
            for (s in samples) w.appendLine(s.toCsvRow(iso))
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun Sample.toCsvRow(iso: SimpleDateFormat): String = listOf(
        tripId, tsMs, iso.format(Date(tsMs)),
        lat ?: "", lon ?: "", accuracyM ?: "", speedMps ?: "",
        carrier.csv(), networkType, if (nsa5g) 1 else 0,
        rsrp ?: "", rsrq ?: "", sinr ?: "", band ?: "", arfcn ?: "", pci ?: "", tac ?: "",
        cellId ?: "", neighborCount, serviceState, if (dataStallInferred) 1 else 0, fidelityMask,
    ).joinToString(",")

    private fun String?.csv(): String {
        val v = this ?: return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }
}
