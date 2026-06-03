package com.railsignal.trip

import com.railsignal.data.Sample
import com.railsignal.location.LocationFix
import com.railsignal.telephony.RadioReading

/** Combine a radio reading with the latest location fix into a persistable Sample. */
fun RadioReading.toSample(tripId: Long, fix: LocationFix?, dataStall: Boolean): Sample = Sample(
    tripId = tripId,
    tsMs = tsMs,
    lat = fix?.lat,
    lon = fix?.lon,
    accuracyM = fix?.accuracyM,
    speedMps = fix?.speedMps,
    carrier = carrier,
    networkType = networkType.name,
    nsa5g = nsa5g,
    rsrp = rsrp,
    rsrq = rsrq,
    sinr = sinr,
    band = band,
    arfcn = arfcn,
    pci = pci,
    tac = tac,
    cellId = cellId,
    neighborCount = neighborCount,
    serviceState = serviceState.name,
    dataStallInferred = dataStall,
    fidelityMask = fidelityMask,
)
