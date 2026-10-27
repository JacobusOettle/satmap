#!/usr/bin/env python3
"""Downloads Kruger National Park's boundary and every road and track in it from OpenStreetMap (Overpass API).

Writes <out>/boundary.json and <out>/roads.json (Overpass "out geom" JSON). Run by
.github/workflows/kruger-roads.yml; tools/build_kruger_roads.py turns them into the file the app ships.
"""
import json
import sys
import time
import urllib.parse
import urllib.request

SERVERS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
# Same road types as the app (SatMap/Core/Roads.swift): every class of public road, service roads and tracks
# (farm, forest and park management roads, nearly all gravel or dirt). Not footpaths or cycle paths.
HIGHWAYS = ("motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|track|road|"
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link")


def overpass(query, tries=6):
    body = urllib.parse.urlencode({"data": query}).encode()
    last = None
    for attempt in range(tries):
        server = SERVERS[attempt % len(SERVERS)]
        try:
            req = urllib.request.Request(server, data=body, headers={"User-Agent": "SatMap build (github.com/JacobusOettle/satmap)"})
            with urllib.request.urlopen(req, timeout=900) as r:
                data = json.load(r)
            remark = data.get("remark", "")
            if "error" in remark.lower():
                raise RuntimeError(remark)
            print(f"{server}: {len(data.get('elements', []))} elements", flush=True)
            return data
        except Exception as e:  # noqa: BLE001 - any failure: try the next server
            last = e
            print(f"{server}: {e}", flush=True)
            time.sleep(20 * (attempt + 1))
    raise SystemExit(f"Overpass failed: {last}")


def main(out):
    candidates = overpass('[out:json][timeout:120];rel["name"="Kruger National Park"];out tags bb;')["elements"]
    for c in candidates:
        print("candidate", c["id"], c.get("tags", {}).get("boundary"), c.get("tags", {}).get("leisure"), c.get("bounds"))

    def size(c):
        b = c.get("bounds") or {}
        return (b.get("maxlat", 0) - b.get("minlat", 0)) * (b.get("maxlon", 0) - b.get("minlon", 0))

    parks = [c for c in candidates if c.get("tags", {}).get("boundary") in ("national_park", "protected_area")
             or c.get("tags", {}).get("leisure") == "nature_reserve"]
    park = max(parks or candidates, key=size)
    rid = park["id"]
    print("using relation", rid, park.get("tags"), flush=True)

    boundary = overpass(f"[out:json][timeout:300];rel({rid});out geom;")
    with open(f"{out}/boundary.json", "w") as f:
        json.dump(boundary, f)
    roads = overpass(f'[out:json][timeout:900][maxsize:2000000000];area({3600000000 + rid})->.park;'
                     f'way["highway"~"^({HIGHWAYS})$"](area.park);out geom;')
    with open(f"{out}/roads.json", "w") as f:
        json.dump(roads, f)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
