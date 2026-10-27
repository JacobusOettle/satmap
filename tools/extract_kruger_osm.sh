#!/usr/bin/env bash
# Cuts Kruger National Park's boundary and every road and track in and around it out of an OpenStreetMap
# extract of South Africa (https://download.geofabrik.de/africa/south-africa-latest.osm.pbf), with osmium-tool.
# Writes <out>/boundary.geojson and <out>/roads.geojson for tools/build_kruger_roads.py.
#
#   tools/extract_kruger_osm.sh south-africa-latest.osm.pbf <out>
set -euo pipefail
pbf=$1
out=$2
mkdir -p "$out"

# The park: relation 1752987 ("Kruger National Park", boundary=protected_area).
osmium getid --add-referenced "$pbf" r1752987 -o "$out/park.osm.pbf" --overwrite
osmium export "$out/park.osm.pbf" --geometry-types=polygon -o "$out/boundary.geojson" --overwrite

# Roads in a box around the park (build_kruger_roads.py keeps only the parts inside). The same road types as the
# app (SatMap/Core/Roads.swift): every class of public road, service roads, and tracks (gravel and dirt roads,
# including the park's management roads). Not footpaths or cycle paths.
osmium extract --bbox 30.85,-25.56,32.06,-22.30 --strategy complete_ways "$pbf" -o "$out/box.osm.pbf" --overwrite
osmium tags-filter "$out/box.osm.pbf" \
  w/highway=motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,service,track,road,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link \
  -o "$out/roads.osm.pbf" --overwrite
osmium export "$out/roads.osm.pbf" --geometry-types=linestring -o "$out/roads.geojson" --overwrite
ls -l "$out"
