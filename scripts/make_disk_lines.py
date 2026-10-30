"""Buat data/disk_lines.json: garis pantai & batas negara Natural Earth 1:50m untuk belahan bumi
yang terlihat GK2A (bujur ~45°BT s/d ~150°BB). Hanya perlu dijalankan sekali:
  python scripts/make_disk_lines.py ne_50m_coastline.shp ne_50m_admin_0_boundary_lines_land.shp
"""
import json
import sys

import shapefile  # pyshp


def visible(lon):
    return lon >= 45 or lon <= -148


def lines(path):
    out = []
    for shape in shapefile.Reader(path).shapes():
        parts = list(shape.parts) + [len(shape.points)]
        for a, b in zip(parts[:-1], parts[1:]):
            pts = shape.points[a:b]
            if any(visible(x) for x, _ in pts):
                out.append([[round(x, 2), round(y, 2)] for x, y in pts])
    return out


data = {"coastline": lines(sys.argv[1]), "borders": lines(sys.argv[2])}
with open("data/disk_lines.json", "w") as f:
    json.dump(data, f, separators=(",", ":"))
print({k: len(v) for k, v in data.items()})
