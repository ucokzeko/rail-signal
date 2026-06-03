#!/usr/bin/env python3
"""Analyse a rail-signal CSV export.

Usage: python3 tools/analyse_csv.py <export.csv>

Reports: trip summary, sampling cadence/gaps (background-survival check),
service-state and network-type breakdown (time-weighted), dropout + data-stall
segments with locations, signal stats, band/handover usage, and field fidelity.
"""
import csv
import math
import sys
from datetime import datetime

FIDELITY_BITS = [
    (1 << 0, "RSRP"), (1 << 1, "RSRQ"), (1 << 2, "SINR"), (1 << 3, "band"),
    (1 << 4, "ARFCN"), (1 << 5, "PCI"), (1 << 6, "TAC"), (1 << 7, "cellId"),
    (1 << 8, "neighbours"),
]


def fnum(x, cast):
    return cast(x) if x not in (None, "") else None


def haversine(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, [a[0], a[1], b[0], b[1]])
    dla, dlo = la2 - la1, lo2 - lo1
    h = math.sin(dla / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlo / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    k = (len(s) - 1) * p / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] if lo == hi else s[lo] + (s[hi] - s[lo]) * (k - lo)


def hhmmss(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


def dur(ms):
    s = int(ms / 1000)
    return f"{s // 60}m{s % 60:02d}s"


def main(path):
    rows = list(csv.DictReader(open(path)))
    R = []
    for r in rows:
        R.append({
            "ts": int(r["ts_ms"]),
            "lat": fnum(r["lat"], float), "lon": fnum(r["lon"], float),
            "spd": fnum(r["speed_mps"], float),
            "carrier": r["carrier"], "net": r["network_type"],
            "nsa": r["nsa5g"] == "1",
            "rsrp": fnum(r["rsrp"], int), "rsrq": fnum(r["rsrq"], int), "sinr": fnum(r["sinr"], int),
            "band": fnum(r["band"], int), "arfcn": fnum(r["arfcn"], int), "pci": fnum(r["pci"], int),
            "cell": fnum(r["cell_id"], int), "neigh": fnum(r["neighbors"], int),
            "svc": r["service_state"], "stall": r["data_stall"] == "1",
            "fid": int(r["fidelity_mask"]),
        })
    n = len(R)
    R.sort(key=lambda x: x["ts"])
    span = R[-1]["ts"] - R[0]["ts"]

    print(f"=== TRIP ===")
    print(f"samples: {n}   carrier: {R[0]['carrier']}")
    print(f"start: {hhmmss(R[0]['ts'])}   end: {hhmmss(R[-1]['ts'])}   span: {dur(span)}")
    gps = [r for r in R if r["lat"] is not None]
    dist = sum(haversine((gps[i]["lat"], gps[i]["lon"]), (gps[i + 1]["lat"], gps[i + 1]["lon"]))
               for i in range(len(gps) - 1)) if len(gps) > 1 else 0
    spds = [r["spd"] for r in R if r["spd"] is not None]
    print(f"distance: {dist/1000:.1f} km   max speed: {max(spds)*3.6:.0f} km/h" if spds else "")
    if gps:
        print(f"from (lat,lon): {gps[0]['lat']:.4f},{gps[0]['lon']:.4f}  ->  {gps[-1]['lat']:.4f},{gps[-1]['lon']:.4f}")

    # ---- sampling cadence / gaps (background-survival check) ----
    dts = [(R[i + 1]["ts"] - R[i]["ts"]) for i in range(n - 1)]
    print(f"\n=== SAMPLING ===")
    print(f"median gap: {pct(dts,50)/1000:.1f}s   max gap: {max(dts)/1000:.1f}s")
    big = [(i, d) for i, d in enumerate(dts) if d > 15000]
    print(f"gaps >15s (possible service kill): {len(big)}")
    for i, d in big[:10]:
        print(f"   {hhmmss(R[i]['ts'])} -> {hhmmss(R[i+1]['ts'])}  ({dur(d)})")

    # ---- time-weighted breakdowns ----
    def weighted(key):
        acc = {}
        for i in range(n - 1):
            acc[R[i][key]] = acc.get(R[i][key], 0) + dts[i]
        return acc

    print(f"\n=== SERVICE STATE (time-weighted) ===")
    for k, v in sorted(weighted("svc").items(), key=lambda x: -x[1]):
        print(f"   {k:16s} {dur(v):>8s}  {100*v/span:5.1f}%")

    print(f"\n=== NETWORK TYPE (time-weighted) ===")
    for k, v in sorted(weighted("net").items(), key=lambda x: -x[1]):
        print(f"   {k:12s} {dur(v):>8s}  {100*v/span:5.1f}%")
    nsa_t = sum(dts[i] for i in range(n - 1) if R[i]["nsa"])
    print(f"   NSA-5G active: {100*nsa_t/span:.1f}% of time")

    # ---- dropout + stall segments ----
    def segments(pred):
        segs, cur = [], None
        for idx in range(n):
            if pred(R[idx]):
                cur = [idx, idx] if cur is None else [cur[0], idx]
            elif cur:
                segs.append(tuple(cur)); cur = None
        if cur:
            segs.append(tuple(cur))
        return segs

    def report_segs(title, segs):
        print(f"\n=== {title}: {len(segs)} ===")
        total = 0
        for s, e in segs:
            # outage span: from last-good before to first-good after (lower bound = within-segment span)
            t0 = R[s - 1]["ts"] if s > 0 else R[s]["ts"]
            t1 = R[e + 1]["ts"] if e + 1 < n else R[e]["ts"]
            d = t1 - t0
            total += d
            loc = next(((R[j]["lat"], R[j]["lon"]) for j in range(s, e + 1) if R[j]["lat"] is not None), None)
            rsrps = [R[j]["rsrp"] for j in range(s, e + 1) if R[j]["rsrp"] is not None]
            locs = f"{loc[0]:.4f},{loc[1]:.4f}" if loc else "no-gps"
            rs = f"RSRP min {min(rsrps)}" if rsrps else "no-rsrp"
            print(f"   {hhmmss(t0)}  {dur(d):>7s}  @ {locs}  ({e-s+1} samples, {rs})")
        print(f"   total outage time: {dur(total)} ({100*total/span:.1f}% of trip)")

    report_segs("NO-SERVICE SEGMENTS", segments(lambda r: r["svc"] != "IN_SERVICE" and r["net"] != "UNKNOWN"))
    report_segs("DATA-STALL SEGMENTS (in service, unvalidated)", segments(lambda r: r["svc"] == "IN_SERVICE" and r["stall"]))

    # ---- signal stats ----
    print(f"\n=== SIGNAL ===")
    for fld, unit in (("rsrp", "dBm"), ("rsrq", "dB"), ("sinr", "dB")):
        v = [r[fld] for r in R if r[fld] is not None]
        if v:
            print(f"   {fld.upper():5s} min {min(v):4d}  p10 {pct(v,10):6.0f}  median {pct(v,50):6.0f}  p90 {pct(v,90):6.0f}  max {max(v):4d} {unit}")
    rsrp = [r["rsrp"] for r in R if r["rsrp"] is not None]
    if rsrp:
        weak = sum(1 for x in rsrp if x < -110)
        vweak = sum(1 for x in rsrp if x < -120)
        print(f"   RSRP < -110 dBm (weak): {100*weak/len(rsrp):.0f}%   < -120 (very weak): {100*vweak/len(rsrp):.0f}%")

    # ---- bands / handovers ----
    print(f"\n=== BANDS / CELLS ===")
    bands = weighted("band")
    for k, v in sorted(bands.items(), key=lambda x: -x[1]):
        print(f"   band {k}  {dur(v):>8s}  {100*v/span:5.1f}%")
    cells = [r["cell"] for r in R if r["cell"] is not None]
    pcis = [r["pci"] for r in R if r["pci"] is not None]
    ho = sum(1 for i in range(len(pcis) - 1) if pcis[i] != pcis[i + 1])
    print(f"   distinct cells: {len(set(cells))}   PCI changes (handovers): {ho}")

    # ---- fidelity (OR over trip) ----
    orall = 0
    for r in R:
        orall |= r["fid"]
    print(f"\n=== FIELD FIDELITY (ever populated this trip) ===")
    print("   " + "  ".join(f"{name}{'✓' if orall & bit else '✗'}" for bit, name in FIDELITY_BITS))


if __name__ == "__main__":
    main(sys.argv[1])
