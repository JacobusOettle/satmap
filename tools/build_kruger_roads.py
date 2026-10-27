#!/usr/bin/env python3
"""Builds SatMap/Resources/KrugerRoads.json from tools/extract_kruger_osm.sh's output.

Keeps only the parts of roads inside the park boundary (checked every 10 m or less), simplifies them to within
1 m, and stores the park's outer boundary (simplified to 20 m) and the roads as encoded polylines (RoadPreset in
SatMap/Core/Roads.swift reads them).

    python3 tools/build_kruger_roads.py <folder with boundary.geojson and roads.geojson> SatMap/Resources/KrugerRoads.json \
        [folder of extra .kml/.kmz files]

Roads from the extra KML/KMZ files (their lines; polygons such as drone blocks and points are ignored) are added
too, for roads OpenStreetMap doesn't have. The output tells how much of them OpenStreetMap already covered.
"""
import bisect
import collections
import datetime
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

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


def rings_from_geojson(doc):
    """Outer and inner rings of the (Multi)Polygon features, as (lat, lon) lists."""
    rings = {"outer": [], "inner": []}
    for f in doc.get("features", []):
        g = f.get("geometry") or {}
        polys = g["coordinates"] if g.get("type") == "MultiPolygon" else [g["coordinates"]] if g.get("type") == "Polygon" else []
        for poly in polys:
            for i, ring in enumerate(poly):
                rings["outer" if i == 0 else "inner"].append([(lat, lon) for lon, lat in ring])
    return rings


def lines_from_geojson(doc):
    """(tags, [(lat, lon)]) for every LineString (or MultiLineString part)."""
    for f in doc.get("features", []):
        g = f.get("geometry") or {}
        parts = [g["coordinates"]] if g.get("type") == "LineString" else g["coordinates"] if g.get("type") == "MultiLineString" else []
        for part in parts:
            yield f.get("properties") or {}, [(lat, lon) for lon, lat in part]


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


def kml_lines(path):
    """Every LineString in a .kml or .kmz file, as (lat, lon) lists (polygons and points are left out)."""
    if path.lower().endswith(".kmz"):
        with zipfile.ZipFile(path) as z:
            name = next((n for n in z.namelist() if n.lower().endswith(".kml")), None)
            if name is None:
                return []
            root = ET.fromstring(z.read(name))
    else:
        root = ET.parse(path).getroot()
    lines = []
    for el in root.iter():
        if el.tag.rsplit("}", 1)[-1] != "LineString":
            continue
        for c in el:
            if c.tag.rsplit("}", 1)[-1] == "coordinates" and c.text:
                pts = []
                for tok in c.text.split():
                    parts = tok.split(",")
                    if len(parts) >= 2:
                        pts.append((float(parts[1]), float(parts[0])))
                if len(pts) >= 2:
                    lines.append(pts)
    return lines


class NearRoads:
    """Answers "is there a road within `radius` metres of this point?" with a grid of road segments."""

    def __init__(self, lines_xy, radius):
        self.r = radius
        self.cell = max(radius, 50.0)
        self.grid = collections.defaultdict(list)
        for line in lines_xy:
            for a, b in zip(line, line[1:]):
                x0, x1 = sorted((a[0], b[0]))
                y0, y1 = sorted((a[1], b[1]))
                for gx in range(int((x0 - radius) // self.cell), int((x1 + radius) // self.cell) + 1):
                    for gy in range(int((y0 - radius) // self.cell), int((y1 + radius) // self.cell) + 1):
                        self.grid[(gx, gy)].append((a, b))

    def __call__(self, x, y):
        for (ax, ay), (bx, by) in self.grid.get((int(x // self.cell), int(y // self.cell)), ()):
            dx, dy = bx - ax, by - ay
            ll = dx * dx + dy * dy
            t = 0.0 if ll == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / ll))
            if math.hypot(ax + t * dx - x, ay + t * dy - y) <= self.r:
                return True
        return False


def clip_runs(xy, inside):
    """The parts of a line inside the park, checked every STEP_M or less."""
    runs, run = [], []
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
    return [r for r in runs if len(r) >= 2]


def length_m(pts_xy):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts_xy, pts_xy[1:]))


def main(src, dst):
    rings = rings_from_geojson(json.load(open(f"{src}/boundary.geojson")))
    outer = rings["outer"]
    if not outer:
        raise SystemExit("no outer boundary")
    lats = [p[0] for r in outer for p in r]
    lons = [p[1] for r in outer for p in r]
    proj = Projection((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)
    inside = Inside([[proj.xy(*p) for p in r] for r in outer + rings["inner"]])
    print(f"boundary: {len(outer)} outer, {len(rings['inner'])} inner rings, {sum(map(len, outer))} points")

    kept, total_m, kept_m, ways = [], 0.0, 0.0, 0
    by_type = collections.Counter()
    by_surface = collections.Counter()
    for tags, geom in lines_from_geojson(json.load(open(f"{src}/roads.geojson"))):
        if len(geom) < 2:
            continue
        ways += 1
        xy = [proj.xy(*p) for p in geom]
        total_m += length_m(xy)
        for r in clip_runs(xy, inside):
            m = length_m(r)
            kept_m += m
            by_type[tags.get("highway", "?")] += m
            by_surface[tags.get("surface", "unknown")] += m
            kept.append(r)
    osm_count = len(kept)

    # Roads from KML files: added where OpenStreetMap has no road within 15 m (the imagery corridor).
    extra_dir = sys.argv[3] if len(sys.argv) > 3 else None
    if extra_dir and os.path.isdir(extra_dir):
        near = NearRoads(kept, 15.0)
        for name in sorted(os.listdir(extra_dir)):
            if not name.lower().endswith((".kml", ".kmz")):
                continue
            lines = kml_lines(os.path.join(extra_dir, name))
            km_in = km_new = 0.0
            for line in lines:
                for r in clip_runs([proj.xy(*p) for p in line], inside):
                    km_in += length_m(r) / 1000
                    # Split into pieces OpenStreetMap already covers and pieces it doesn't; keep the latter.
                    piece = []
                    dense = clip_runs(r, lambda x, y: True)[0]
                    for p in dense:
                        if near(*p):
                            if len(piece) >= 2:
                                km_new += length_m(piece) / 1000
                                kept.append(piece)
                            piece = []
                        else:
                            piece.append(p)
                    if len(piece) >= 2:
                        km_new += length_m(piece) / 1000
                        kept.append(piece)
            print(f"{name}: {len(lines)} lines, {km_in:.1f} km inside the park, {km_new:.1f} km not in OpenStreetMap (added)")
    kept = [[proj.ll(*p) for p in simplify(r, ROAD_TOL_M)] for r in kept]

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

    print(f"roads: {ways} ways, {total_m / 1000:.0f} km in total, "
          f"{kept_m / 1000:.0f} km inside the park in {osm_count} pieces; {len(kept)} pieces with KML roads, "
          f"{sum(map(len, kept))} points")
    print("by highway type (km):", ", ".join(f"{k} {v / 1000:.0f}" for k, v in by_type.most_common()))
    print("by surface (km):", ", ".join(f"{k} {v / 1000:.0f}" for k, v in by_surface.most_common(12)))
    print(f"boundary simplified to {sum(map(len, outer_simple))} points; wrote {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
