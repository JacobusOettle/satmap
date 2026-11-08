KML or KMZ files with roads to add to the Kruger National Park road list (for example management tracks and
firebreaks that OpenStreetMap doesn't have). Adding or changing a file here rebuilds the list
(`.github/workflows/kruger-roads.yml`, which commits `SatMap/Resources/KrugerRoads.json`).

- Only lines are used; polygons (such as drone blocks) and points are ignored, and only the parts inside the park
  are kept.
- If a file has a folder named "Roads", only the lines inside it are used (other lines in the file, such as section
  outlines, are left out).
- A stretch is added only where neither OpenStreetMap nor a line added before it has a road within 15 m, so roads
  that OpenStreetMap already has, or that several files or folders repeat, are added once.
- This repository is public: commit only the roads (for example the "Roads" folder of a day's file), not the rest
  of a work file (launch points, blocks).

`Kruger roads 8 Nov 2026.kml` is the "Roads" folder of the crew's 8 Nov 2026 file (Stolsnek, Pretoriuskop, Skukuza,
Lower Sabie and Tshokwane). It replaces the 27 Oct 2026 file, whose roads it all contains.
