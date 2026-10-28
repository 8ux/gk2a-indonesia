"""Buat data/indonesia_lines.json dari shapefile Natural Earth (public domain).

Hanya perlu dijalankan sekali (hasilnya sudah ada di repo):
  python scripts/make_lines.py ne_10m_coastline.shp ne_10m_admin_0_boundary_lines_land.shp
"""
import json
import sys

import shapefile  # pyshp

BBOX = (88.0, -16.0, 148.0, 13.0)  # lon_min, lat_min, lon_max, lat_max (wilayah + margin)


def lines_in_bbox(path):
    out = []
    for shape in shapefile.Reader(path).shapes():
        parts = list(shape.parts) + [len(shape.points)]
        for a, b in zip(parts[:-1], parts[1:]):
            pts = shape.points[a:b]
            if any(BBOX[0] <= x <= BBOX[2] and BBOX[1] <= y <= BBOX[3] for x, y in pts):
                out.append([[round(x, 3), round(y, 3)] for x, y in pts])
    return out


coast, borders = sys.argv[1], sys.argv[2]
data = {"coastline": lines_in_bbox(coast), "borders": lines_in_bbox(borders)}
with open("data/indonesia_lines.json", "w") as f:
    json.dump(data, f, separators=(",", ":"))
print({k: len(v) for k, v in data.items()})
