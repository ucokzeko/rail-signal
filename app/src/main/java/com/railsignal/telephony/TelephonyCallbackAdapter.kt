package com.railsignal.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor

/**
 * Reads the serving cell via [TelephonyCallback] (API 31+). Merges signal strength,
 * service state, display info (for NSA-5G) and cell info into a [RadioReading] on every
 * callback. Cell identity (band/ARFCN/PCI/cellId) requires ACCESS_FINE_LOCATION; without
 * it those fields stay null and the fidelity mask reflects that.
 */
class TelephonyCallbackAdapter(context: Context) : TelephonySource {

    private val appCtx = context.applicationContext
    private val tm: TelephonyManager? = appCtx.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    override val readings: Flow<RadioReading> = callbackFlow {
        val executor = Executor { it.run() }

        var signal: SignalStrength? = null
        var service: ServiceState? = null
        var display: TelephonyDisplayInfo? = null
        var cells: List<CellInfo> = emptyList()

        fun hasLocation() = ContextCompat.checkSelfPermission(
            appCtx, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        fun push() = trySend(buildReading(tm, signal, service, display, cells))

        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.CellInfoListener {
            override fun onSignalStrengthsChanged(s: SignalStrength) { signal = s; push() }
            override fun onServiceStateChanged(s: ServiceState) { service = s; push() }
            override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) { display = d; push() }
            override fun onCellInfoChanged(c: MutableList<CellInfo>) { cells = c; push() }
        }

        val registered = try {
            tm?.registerTelephonyCallback(executor, callback)
            true
        } catch (e: SecurityException) {
            close(e) // READ_PHONE_STATE not granted yet; collector can re-subscribe later
            false
        }

        if (registered) {
            if (hasLocation()) {
                runCatching {
                    tm?.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(activeCellInfo: MutableList<CellInfo>) {
                            cells = activeCellInfo; push()
                        }
                    })
                }
            }
            push() // initial snapshot
        }

        // callbackFlow REQUIRES awaitClose on every path, including the early-failure one.
        awaitClose { if (registered) runCatching { tm?.unregisterTelephonyCallback(callback) } }
    }

    @SuppressLint("MissingPermission")
    override val inCall: Flow<Boolean> = callbackFlow {
        val executor = Executor { it.run() }
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            // No phone number arg here → needs only READ_PHONE_STATE, not READ_CALL_LOG.
            override fun onCallStateChanged(state: Int) {
                trySend(state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        val registered = try {
            tm?.registerTelephonyCallback(executor, callback)
            true
        } catch (e: SecurityException) {
            close(e) // READ_PHONE_STATE not granted yet; collector can re-subscribe later
            false
        }
        if (registered) trySend(false) // assume idle until the first callback says otherwise
        awaitClose { if (registered) runCatching { tm?.unregisterTelephonyCallback(callback) } }
    }
}

private fun Int.valid(): Int? =
    if (this == Int.MAX_VALUE || this == CellInfo.UNAVAILABLE) null else this

private fun Long.validLong(): Long? =
    if (this == Long.MAX_VALUE || this == CellInfo.UNAVAILABLE_LONG) null else this

@SuppressLint("MissingPermission")
private fun buildReading(
    tm: TelephonyManager?,
    signal: SignalStrength?,
    service: ServiceState?,
    display: TelephonyDisplayInfo?,
    cells: List<CellInfo>,
): RadioReading {
    val ts = System.currentTimeMillis()
    val carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() }

    val serviceState = when (service?.state) {
        ServiceState.STATE_IN_SERVICE -> ServiceStateCode.IN_SERVICE
        ServiceState.STATE_OUT_OF_SERVICE -> ServiceStateCode.OUT_OF_SERVICE
        ServiceState.STATE_EMERGENCY_ONLY -> ServiceStateCode.EMERGENCY_ONLY
        ServiceState.STATE_POWER_OFF -> ServiceStateCode.POWER_OFF
        else -> ServiceStateCode.UNKNOWN
    }

    val (networkType, nsa5g) = classifyNetwork(display)

    var rsrp: Int? = null
    var rsrq: Int? = null
    var sinr: Int? = null
    var band: Int? = null
    var arfcn: Int? = null
    var pci: Int? = null
    var tac: Int? = null
    var cellId: Long? = null

    when (val serving = cells.firstOrNull { it.isRegistered }) {
        is CellInfoLte -> {
            val ss = serving.cellSignalStrength
            rsrp = ss.rsrp.valid(); rsrq = ss.rsrq.valid(); sinr = ss.rssnr.valid()
            val id: CellIdentityLte = serving.cellIdentity
            arfcn = id.earfcn.valid(); pci = id.pci.valid(); tac = id.tac.valid()
            cellId = id.ci.valid()?.toLong() // LTE CI uses the Int UNAVAILABLE sentinel
            band = id.bands.firstOrNull()
        }
        is CellInfoNr -> {
            (serving.cellSignalStrength as? CellSignalStrengthNr)?.let { ss ->
                rsrp = ss.ssRsrp.valid(); rsrq = ss.ssRsrq.valid(); sinr = ss.ssSinr.valid()
            }
            (serving.cellIdentity as? CellIdentityNr)?.let { id ->
                arfcn = id.nrarfcn.valid(); pci = id.pci.valid(); tac = id.tac.valid()
                cellId = id.nci.validLong()
                band = id.bands.firstOrNull()
            }
        }
        else -> Unit
    }

    if (rsrp == null) {
        // Dual-SIM: cellSignalStrengths lists Invalid entries (GSM/CDMA) first; take the
        // first one with a valid dBm rather than firstOrNull().
        rsrp = signal?.cellSignalStrengths?.firstNotNullOfOrNull { it.dbm.valid() }
    }

    val neighborCount = cells.count { !it.isRegistered }

    return RadioReading(
        tsMs = ts, carrier = carrier, networkType = networkType, nsa5g = nsa5g,
        rsrp = rsrp, rsrq = rsrq, sinr = sinr, band = band, arfcn = arfcn, pci = pci,
        tac = tac, cellId = cellId, neighborCount = neighborCount, serviceState = serviceState,
    )
}

private fun classifyNetwork(d: TelephonyDisplayInfo?): Pair<NetworkType, Boolean> {
    if (d == null) return NetworkType.UNKNOWN to false
    val nsa = d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
    val type = when {
        nsa -> NetworkType.NR_NSA
        d.networkType == TelephonyManager.NETWORK_TYPE_NR -> NetworkType.NR_SA
        d.networkType == TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.LTE
        d.networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN -> NetworkType.NONE
        else -> NetworkType.THREE_G_OR_OLDER
    }
    return type to nsa
}
