"""Full disk GK2A + beberapa sektor (Asia Tenggara, Asia Timur, ...) dalam proyeksi asli satelit.

Satu waktu = satu render full disk 2 km (5 kanal diunduh utuh dari arsip NOAA di AWS),
lalu tiap sektor dipotong dari render itu, jadi lengkung bumi tetap terlihat.
Hasilnya ditulis ke folder --out sebagai <sektor>_YYYYMMDDHHMM.jpg; archive.py mengunggahnya
ke GitHub Releases (sectors-YYYYMMDD) dan mendaftarkannya di archive.json.
"""
import argparse
import datetime as dt
import gc
import json
import logging
import shutil
import sys
import tempfile
import time
import warnings
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageDraw

import gk2a_indonesia as base

warnings.filterwarnings("ignore")
log = logging.getLogger("sectors")

# id: (nama, bbox lon_min, lat_min, lon_max, lat_max); None = seluruh bulatan bumi
SECTORS = {
    "fd": ("Full Disk", None),
    "sea": ("Southeast Asia", (90, -15, 145, 25)),
    "eas": ("East Asia", (100, 18, 150, 50)),
    "aus": ("Australia", (108, -46, 160, -8)),
    "wpac": ("Western Pacific", (125, -5, 180, 35)),
    "sas": ("South Asia", (60, 0, 100, 35)),
}
FULL_DISK_SIZE = 1800
SECTOR_MAX_WIDTH = 1600
LINES_FILE = base.ROOT / "data" / "disk_lines.json"


def download(keys, folder):
    def get(key):
        path = folder / key.rsplit("/", 1)[1]
        with requests.get(f"{base.BUCKET_URL}/{key}", stream=True, timeout=120) as r:
            r.raise_for_status()
            with open(path, "wb") as f:
                for chunk in r.iter_content(8 << 20):
                    f.write(chunk)
        return path

    with ThreadPoolExecutor(len(keys)) as pool:
        return list(pool.map(get, keys))


def render_disk(files, product):
    """Render full disk 2 km. Kembalikan (PIL.Image RGB, AreaDefinition)."""
    import satpy
    from satpy import Scene
    from satpy.writers import get_enhanced_image

    satpy.config.set(config_path=[str(base.SATPY_CONFIG)])
    scn = Scene([str(f) for f in files], reader="ami_l1b")
    scn.load([product])
    full = scn.resample(scn.coarsest_area(), resampler="native")
    data = full[product]
    area = data.attrs["area"]
    img = get_enhanced_image(data).pil_image()
    del scn, full, data
    gc.collect()
    if img.mode == "RGBA":
        bg = Image.new("RGB", img.size, (0, 0, 0))
        bg.paste(img, mask=img.split()[3])
        img = bg
    return img.convert("RGB"), area


class Projector:
    """Lon/lat -> piksel citra full disk (titik di balik bumi menjadi NaN)."""

    def __init__(self, area):
        from pyproj import Transformer

        self.tr = Transformer.from_crs("EPSG:4326", area.crs, always_xy=True)
        x0, y0, x1, y1 = area.area_extent
        self.x0, self.y1 = x0, y1
        self.px = (x1 - x0) / area.width

    def __call__(self, lon, lat):
        x, y = self.tr.transform(np.asarray(lon, float), np.asarray(lat, float))
        x = np.where(np.isfinite(x) & (np.abs(x) < 1e12), x, np.nan)
        y = np.where(np.isfinite(y) & (np.abs(y) < 1e12), y, np.nan)
        return (x - self.x0) / self.px, (self.y1 - y) / self.px


def sector_window(proj, bbox, size):
    """Jendela piksel (x0, y0, x1, y1) yang memuat seluruh bbox lon/lat."""
    lon0, lat0, lon1, lat1 = bbox
    n = 60
    lons = np.concatenate([np.linspace(lon0, lon1, n), np.full(n, lon1), np.linspace(lon1, lon0, n), np.full(n, lon0)])
    lats = np.concatenate([np.full(n, lat0), np.linspace(lat0, lat1, n), np.full(n, lat1), np.linspace(lat1, lat0, n)])
    cols, rows = proj(lons, lats)
    ok = np.isfinite(cols) & np.isfinite(rows)
    if not ok.any():
        raise ValueError(f"sektor {bbox} tidak terlihat dari GK2A")
    x0, x1 = np.nanmin(cols[ok]), np.nanmax(cols[ok])
    y0, y1 = np.nanmin(rows[ok]), np.nanmax(rows[ok])
    return (max(0, int(x0)), max(0, int(y0)), min(size, int(x1) + 1), min(size, int(y1) + 1))


def draw_lines(img, proj, window, lines):
    """Garis pantai (kuning) & batas negara (oranye) pada potongan [window] yang sudah diskalakan ke img."""
    x0, y0, x1, y1 = window
    sx = img.width / (x1 - x0)
    sy = img.height / (y1 - y0)
    draw = ImageDraw.Draw(img)
    for name, color in (("coastline", (255, 255, 0)), ("borders", (255, 140, 0))):
        for line in lines[name]:
            pts = np.asarray(line)
            cols, rows = proj(pts[:, 0], pts[:, 1])
            xs = (cols - x0) * sx
            ys = (rows - y0) * sy
            seg = []
            for x, y in zip(xs, ys):
                if np.isfinite(x) and np.isfinite(y):
                    seg.append((float(x), float(y)))
                    continue
                if len(seg) > 1:
                    draw.line(seg, fill=color, width=1)
                seg = []
            if len(seg) > 1:
                draw.line(seg, fill=color, width=1)


def make_sectors(t, files, products, out):
    for product in products:
        try:
            t0 = time.time()
            disk, area = render_disk(files, product)
            log.info("%s: full disk %s selesai dalam %.0f detik", t, product, time.time() - t0)
            break
        except Exception:
            log.exception("%s: produk %s gagal, mencoba produk berikutnya", t, product)
    else:
        return False

    proj = Projector(area)
    lines = json.loads(LINES_FILE.read_text())
    stamp = f"{t:%Y%m%d%H%M}"
    for sid, (name, bbox) in SECTORS.items():
        if bbox is None:
            window = (0, 0, disk.width, disk.height)
            size = (FULL_DISK_SIZE, FULL_DISK_SIZE)
        else:
            window = sector_window(proj, bbox, disk.width)
            w, h = window[2] - window[0], window[3] - window[1]
            scale = min(1.0, SECTOR_MAX_WIDTH / w)
            size = (round(w * scale), round(h * scale))
        img = disk.crop(window).resize(size, Image.LANCZOS)
        draw_lines(img, proj, window, lines)
        base.draw_label(img, t)
        img.save(out / f"{sid}_{stamp}.jpg", quality=88, optimize=True, comment=base.LABEL_TAG)
    del disk
    gc.collect()
    return True


def existing_times(site):
    """Waktu yang sudah ada di arsip (dari archive.json situs sebelumnya)."""
    try:
        index = json.loads((site / "archive.json").read_text())
        days = index.get("products", {}).get("fd", {}).get("days", {})
    except (OSError, ValueError):
        return set()
    return {dt.datetime.strptime(d + hm, "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)
            for d, times in days.items() for hm in times}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="sectors", type=Path, help="folder hasil (untuk archive.py)")
    p.add_argument("--site", default="site", type=Path, help="folder situs (membaca archive.json lama)")
    p.add_argument("--hours", type=float, default=3, help="hanya proses waktu dalam rentang ini")
    p.add_argument("--max-new", type=int, default=1, help="maksimum waktu baru per eksekusi")
    p.add_argument("--product", default="true_color_night_clouds", choices=list(base.PRODUCT_CHANNELS))
    p.add_argument("--end", help="waktu akhir UTC, format YYYYmmddHHMM (default: sekarang)")
    p.add_argument("--local", type=Path, help="(uji) folder berisi file .nc, tanpa unduh dari S3")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    order = list(base.PRODUCT_CHANNELS)
    products = order[order.index(args.product):]
    args.out.mkdir(parents=True, exist_ok=True)

    if args.local:
        files = sorted(args.local.glob("*.nc"))
        t = dt.datetime.strptime(base.FILE_RE.search(files[0].name).group(2), "%Y%m%d%H%M")
        ok = make_sectors(t.replace(tzinfo=dt.timezone.utc), files, products, args.out)
        return 0 if ok else 1

    end = (dt.datetime.strptime(args.end, "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)
           if args.end else dt.datetime.now(dt.timezone.utc))
    start = end - dt.timedelta(hours=args.hours)
    scenes = base.available_scenes(start, end, base.PRODUCT_CHANNELS[products[-1]])
    done = existing_times(args.site)
    todo = sorted((t for t in scenes if t not in done), reverse=True)[:args.max_new]
    log.info("%d scene tersedia, %d sudah diarsip, memproses %d", len(scenes), len(done & set(scenes)), len(todo))

    for t in todo:
        ch = scenes[t]
        usable = [pr for pr in products if all(c in ch for c in base.PRODUCT_CHANNELS[pr])]
        needed = sorted({c for pr in usable for c in base.PRODUCT_CHANNELS[pr]})
        tmp = Path(tempfile.mkdtemp(prefix="gk2a_fd_"))
        try:
            t0 = time.time()
            files = download([ch[c] for c in needed], tmp)
            log.info("%s: %d file diunduh dalam %.0f detik", t, len(files), time.time() - t0)
            make_sectors(t, files, usable, args.out)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
