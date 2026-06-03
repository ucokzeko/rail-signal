package com.railsignal.telephony

/** Radio access technology, collapsed to what matters for this app. */
enum class NetworkType { LTE, NR_NSA, NR_SA, THREE_G_OR_OLDER, UNKNOWN, NONE }

enum class ServiceStateCode { IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF, UNKNOWN }

/** Bit flags recording which fields the device actually populates (the P0 fidelity probe). */
object Fidelity {
    const val RSRP = 1 shl 0
    const val RSRQ = 1 shl 1
    const val SINR = 1 shl 2
    const val BAND = 1 shl 3
    const val ARFCN = 1 shl 4
    const val PCI = 1 shl 5
    const val TAC = 1 shl 6
    const val CELL_ID = 1 shl 7
    const val NEIGHBORS = 1 shl 8

    /** Display order for the fidelity panel. */
    val labels: List<Pair<Int, String>> = listOf(
        RSRP to "RSRP", RSRQ to "RSRQ", SINR to "SINR", BAND to "band",
        ARFCN to "ARFCN", PCI to "PCI", TAC to "TAC", CELL_ID to "cellId",
        NEIGHBORS to "neigh",
    )
}

/** One snapshot of the serving cell. Nullable fields are "not exposed by this device/state". */
data class RadioReading(
    val tsMs: Long,
    val carrier: String?,
    val networkType: NetworkType,
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
    val serviceState: ServiceStateCode,
) {
    /** Which fields were non-null in THIS reading. */
    val fidelityMask: Int
        get() {
            var m = 0
            if (rsrp != null) m = m or Fidelity.RSRP
            if (rsrq != null) m = m or Fidelity.RSRQ
            if (sinr != null) m = m or Fidelity.SINR
            if (band != null) m = m or Fidelity.BAND
            if (arfcn != null) m = m or Fidelity.ARFCN
            if (pci != null) m = m or Fidelity.PCI
            if (tac != null) m = m or Fidelity.TAC
            if (cellId != null) m = m or Fidelity.CELL_ID
            if (neighborCount > 0) m = m or Fidelity.NEIGHBORS
            return m
        }
}
