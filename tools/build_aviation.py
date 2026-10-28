#!/usr/bin/env python3
"""Builds SatMap/Resources/Aviation.tsv, the aerodromes and navaids shown in drone mode, from OurAirports
(https://ourairports.com/data/, public domain).

One line per place, tab-separated:
  A  kind  code  name  lat  lon  elevation_ft  runway_heading      (kind: L large, M medium, S small, H heliport,
                                                                    W seaplane base; heading in degrees true, 0-179)
  N  kind  ident  name  lat  lon  frequency                          (kind: V VOR/VOR-DME/VORTAC, T TACAN, D DME,
                                                                    B NDB/NDB-DME; frequency as pilots say it)
Closed aerodromes and balloon ports are left out. The code is the ICAO code, else the GPS code, else the local code
(what pilots use), else OurAirports' own ident when it is an ICAO-style code (FAKP), else empty.

Usage: tools/build_aviation.py [download-dir]   (downloads the three CSV files if they aren't there)
"""
import csv
import math
import os
import re
import sys
import urllib.request

BASE = "https://davidmegginson.github.io/ourairports-data/"
OUT = os.path.join(os.path.dirname(__file__), "..", "SatMap", "Resources", "Aviation.tsv")
KINDS = {"large_airport": "L", "medium_airport": "M", "small_airport": "S", "heliport": "H", "seaplane_base": "W"}
NAVAIDS = {"VOR": "V", "VOR-DME": "V", "VORTAC": "V", "TACAN": "T", "DME": "D", "NDB": "B", "NDB-DME": "B"}


def fetch(folder, name):
    path = os.path.join(folder, name)
    if not os.path.exists(path):
        urllib.request.urlretrieve(BASE + name, path)
    return path


def clean(s):
    return " ".join((s or "").replace("\t", " ").split())


def number(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(y, x)) % 360


def runway_headings(path):
    """The heading (true, 0-179) of each aerodrome's longest open runway that has one."""
    best = {}
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r.get("closed") == "1":
                continue
            heading = number(r.get("le_heading_degT"))
            if heading is None:
                ends = [number(r.get(k)) for k in ("le_latitude_deg", "le_longitude_deg", "he_latitude_deg", "he_longitude_deg")]
                if None not in ends and (ends[0], ends[1]) != (ends[2], ends[3]):
                    heading = bearing(*ends)
            if heading is None:
                continue
            length = number(r.get("length_ft")) or 0
            ref = r["airport_ref"]
            if ref not in best or length > best[ref][0]:
                best[ref] = (length, int(round(heading)) % 180)
    return {k: v[1] for k, v in best.items()}


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(folder, exist_ok=True)
    headings = runway_headings(fetch(folder, "runways.csv"))
    lines = []
    with open(fetch(folder, "airports.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            kind = KINDS.get(r["type"])
            lat, lon = number(r["latitude_deg"]), number(r["longitude_deg"])
            if kind is None or lat is None or lon is None:
                continue
            code = next((c for c in (r.get("icao_code"), r.get("gps_code"), r.get("local_code")) if c), "")
            if not code and re.fullmatch(r"[A-Z]{4}", r["ident"]):
                code = r["ident"]
            elevation = number(r.get("elevation_ft"))
            heading = headings.get(r["id"], "") if kind != "H" else ""
            lines.append("\t".join(["A", kind, clean(code), clean(r["name"]), f"{lat:.5f}", f"{lon:.5f}",
                                    "" if elevation is None else str(int(elevation)), str(heading)]))
    with open(fetch(folder, "navaids.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            kind = NAVAIDS.get(r["type"])
            lat, lon = number(r["latitude_deg"]), number(r["longitude_deg"])
            if kind is None or lat is None or lon is None:
                continue
            khz = number(r.get("frequency_khz"))
            if khz is None:
                frequency = ""
            elif kind == "B":
                frequency = f"{int(khz)} kHz"
            else:
                frequency = f"{khz / 1000:.2f} MHz"
            lines.append("\t".join(["N", kind, clean(r["ident"]), clean(r["name"]), f"{lat:.5f}", f"{lon:.5f}", frequency]))
    lines.sort()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Aerodromes and navaids from OurAirports (public domain), built by tools/build_aviation.py\n")
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} places, {os.path.getsize(OUT)} bytes -> {OUT}")


if __name__ == "__main__":
    main()
