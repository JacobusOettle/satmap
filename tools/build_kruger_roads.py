#!/usr/bin/env python3
"""Builds SatMap/Resources/KrugerRoads.json from tools/fetch_kruger_osm.py's output.

Keeps only the parts of roads inside the park boundary (checked every 10 m or less), simplifies them to within
1 m, and stores the park's outer boundary (simplified to 20 m) and the roads as encoded polylines (RoadPreset in
SatMap/Core/Roads.swift reads them).

    python3 tools/build_kruger_roads.py <folder with boundary.json and roads.json> SatMap/Resources/KrugerRoads.json
"""
import bisect
import collections
import datetime
import json
import math
import sys

STEP_M = 10.0      # how often a road is checked against the boundary
ROW_M = 20.0       # height of the boundary index rows
ROAD_TOL_M = 1.0   # simplification of roads
EDGE_TOL_M = 20.0  # simplification of the boundary


class Projection:
    """Local metres around a centre (equirectangular): fine at park scale."""

    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.mx = 111_320.0 * math.cos(math.radians(lat0))
        self.my = 110_574.0

    def xy(self, lat, lon):
        return ((lon - self.lon0) * self.mx, (lat - self.lat0) * self.my)

    def ll(self, x, y):
        return (self.lat0 + y / self.my, self.lon0 + x / self.mx)


def rings_from_relation(rel):
    """Joins the member ways of a multipolygon relation into closed rings, by role."""
    pieces = {"outer": [], "inner": []}
    for m in rel.get("members", []):
        if m.get("type") != "way" or not m.get("geometry"):
            continue
        pts = [(g["lat"], g["lon"]) for g in m["geometry"] if g]
        if len(pts) >= 2:
            pieces["inner" if m.get("role") == "inner" else "outer"].append(pts)
    rings = {}
    for role, segs in pieces.items():
        out = []
        segs = [list(s) for s in segs]
        while segs:
            ring = segs.pop()
            while ring[0] != ring[-1]:
                for i, s in enumerate(segs):
                    if s[0] == ring[-1]:
                        ring += s[1:]
                    elif s[-1] == ring[-1]:
                        ring += s[-2::-1]
                    elif s[-1] == ring[0]:
                        ring = s[:-1] + ring
                    elif s[0] == ring[0]:
                        ring = s[:0:-1] + ring
                    else:
                        continue
                    segs.pop(i)
                    break
                else:
                    print(f"warning: an {role} ring does not close ({len(ring)} points); closing it", file=sys.stderr)
                    ring.append(ring[0])
            out.append(ring)
        rings[role] = out
    return rings


class Inside:
    """Even-odd point-in-polygon over all rings (outer and inner), with crossings indexed per 20 m row."""

    def __init__(self, rings_xy):
        ys = [y for r in rings_xy for _, y in r]
        self.y0 = min(ys)
        n = int((max(ys) - self.y0) / ROW_M) + 2
        self.rows = [[] for _ in range(n)]
        for r in rings_xy:
            for (x1, y1), (x2, y2) in zip(r, r[1:]):
                if y1 == y2:
                    continue
                lo, hi = min(y1, y2), max(y1, y2)
                first = math.ceil((lo - self.y0) / ROW_M - 0.5)
                last = math.ceil((hi - self.y0) / ROW_M - 0.5) - 1
                for row in range(max(first, 0), min(last, n - 1) + 1):
                    yc = self.y0 + (row + 0.5) * ROW_M
                    self.rows[row].append(x1 + (yc - y1) / (y2 - y1) * (x2 - x1))
        for r in self.rows:
            r.sort()

    def __call__(self, x, y):
        row = int((y - self.y0) / ROW_M)
        if row < 0 or row >= len(self.rows):
            return False
        return bisect.bisect_left(self.rows[row], x) % 2 == 1


def simplify(pts, tol):
    """Douglas-Peucker on (x, y) points."""
    if len(pts) <= 2:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        s, e = stack.pop()
        (ax, ay), (bx, by) = pts[s], pts[e]
        dx, dy = bx - ax, by - ay
        ll = dx * dx + dy * dy
        best, idx = -1.0, s
        for i in range(s + 1, e):
            px, py = pts[i]
            t = 0.0 if ll == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
            qx, qy = ax + t * dx - px, ay + t * dy - py
            d = qx * qx + qy * qy
            if d > best:
                best, idx = d, i
        if best > tol * tol:
            keep[idx] = True
            stack += [(s, idx), (idx, e)]
    return [p for p, k in zip(pts, keep) if k]


def encode(points):
    """Google's encoded polyline, 5 decimals (matches Polyline in SatMap/Core/Roads.swift)."""
    out, last = [], (0, 0)
    for lat, lon in points:
        cur = (int(round(lat * 1e5)), int(round(lon * 1e5)))
        for d in (cur[0] - last[0], cur[1] - last[1]):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last = cur
    return "".join(out)


def length_m(pts_xy):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts_xy, pts_xy[1:]))


def main(src, dst):
    boundary = json.load(open(f"{src}/boundary.json"))
    rel = next(e for e in boundary["elements"] if e["type"] == "relation")
    rings = rings_from_relation(rel)
    outer = rings["outer"]
    if not outer:
        raise SystemExit("no outer boundary")
    lats = [p[0] for r in outer for p in r]
    lons = [p[1] for r in outer for p in r]
    proj = Projection((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)
    inside = Inside([[proj.xy(*p) for p in r] for r in outer + rings["inner"]])
    print(f"boundary: {len(outer)} outer, {len(rings['inner'])} inner rings, {sum(map(len, outer))} points")

    roads = json.load(open(f"{src}/roads.json"))
    kept, total_m, kept_m = [], 0.0, 0.0
    by_type = collections.Counter()
    by_surface = collections.Counter()
    for way in roads["elements"]:
        if way.get("type") != "way":
            continue
        geom = [(g["lat"], g["lon"]) for g in way.get("geometry") or [] if g]
        if len(geom) < 2:
            continue
        xy = [proj.xy(*p) for p in geom]
        total_m += length_m(xy)
        run = []
        runs = []
        for (ax, ay), (bx, by) in zip(xy, xy[1:]):
            n = max(1, math.ceil(math.hypot(bx - ax, by - ay) / STEP_M))
            for k in range(n):
                t0, tm, t1 = k / n, (k + 0.5) / n, (k + 1) / n
                if inside(ax + (bx - ax) * tm, ay + (by - ay) * tm):
                    if not run:
                        run.append((ax + (bx - ax) * t0, ay + (by - ay) * t0))
                    run.append((ax + (bx - ax) * t1, ay + (by - ay) * t1))
                elif run:
                    runs.append(run)
                    run = []
        if run:
            runs.append(run)
        tags = way.get("tags", {})
        for r in runs:
            if len(r) < 2:
                continue
            m = length_m(r)
            kept_m += m
            by_type[tags.get("highway", "?")] += m
            by_surface[tags.get("surface", "unknown")] += m
            kept.append([proj.ll(*p) for p in simplify(r, ROAD_TOL_M)])

    outer_simple = []
    for r in outer:
        s = simplify([proj.xy(*p) for p in r], EDGE_TOL_M)
        outer_simple.append([proj.ll(*p) for p in s])

    today = datetime.date.today().isoformat()
    doc = {
        "name": "Kruger National Park roads",
        "note": f"from OpenStreetMap (© OpenStreetMap contributors, ODbL), {today}",
        "boundary": [encode(r) for r in outer_simple],
        "roads": [encode(r) for r in kept],
    }
    with open(dst, "w") as f:
        json.dump(doc, f, separators=(",", ":"))

    print(f"roads: {len(roads['elements'])} ways, {total_m / 1000:.0f} km in total, "
          f"{kept_m / 1000:.0f} km inside the park in {len(kept)} pieces, {sum(map(len, kept))} points")
    print("by highway type (km):", ", ".join(f"{k} {v / 1000:.0f}" for k, v in by_type.most_common()))
    print("by surface (km):", ", ".join(f"{k} {v / 1000:.0f}" for k, v in by_surface.most_common(12)))
    print(f"boundary simplified to {sum(map(len, outer_simple))} points; wrote {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
