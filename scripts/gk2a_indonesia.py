"""Unduh citra GK2A dari arsip publik NOAA (AWS), buat true color wilayah Indonesia + animasi.

Data dibaca langsung dari S3 lewat HTTP range request, jadi hanya bagian
wilayah Indonesia yang diunduh (bukan file full disk ratusan MB).

Hasil ditulis ke folder situs (default: site/):
  frames/gk2a_YYYYMMDDHHMM.jpg   frame per 10 menit
  latest.jpg                     frame terbaru
  animation.mp4 / animation.gif  animasi beberapa jam terakhir
  frames.json                    daftar frame (untuk viewer web / aplikasi Android)
"""
import argparse
import datetime as dt
import gc
import json
import logging
import re
import shutil
import subprocess
import sys
import tempfile
import time
import warnings
from pathlib import Path

import fsspec
import requests
from PIL import Image, ImageDraw, ImageFont

warnings.filterwarnings("ignore")
log = logging.getLogger("gk2a")

BUCKET_URL = "https://noaa-gk2a-pds.s3.amazonaws.com"
PREFIX = "AMI/L1B/FD"

# lon_min, lat_min, lon_max, lat_max
INDONESIA_BBOX = (94.0, -11.5, 142.0, 8.0)

# Kanal yang dibutuhkan tiap produk satpy (nama kanal di nama file GK2A).
# Urutan = urutan fallback bila produk gagal (mis. tabel koreksi Rayleigh gagal diunduh).
# Komposit *_night_clouds didefinisikan di satpy_config/composites/ami.yaml.
DAY_CHANNELS = ["vi004", "vi005", "vi006", "vi008"]
PRODUCT_CHANNELS = {
    "true_color_night_clouds": DAY_CHANNELS + ["ir105"],
    "true_color_raw_night_clouds": DAY_CHANNELS + ["ir105"],
    "true_color": DAY_CHANNELS,
    "true_color_raw": DAY_CHANNELS,
}
FILE_RE = re.compile(r"gk2a_ami_le1b_([a-z]{2}\d{3})_fd\d{3}ge_(\d{12})\.nc$")

ROOT = Path(__file__).resolve().parent.parent
LINES_FILE = ROOT / "data" / "indonesia_lines.json"
SATPY_CONFIG = ROOT / "satpy_config"
WIB = dt.timezone(dt.timedelta(hours=7))
# Penanda versi label di komentar JPEG; frame dengan label lama dibuat ulang bertahap.
LABEL_TAG = b"YD1BUX label v2"


# ---------------------------------------------------------------- daftar file di S3
def list_keys(prefix):
    keys, token = [], None
    while True:
        params = {"list-type": "2", "prefix": prefix}
        if token:
            params["continuation-token"] = token
        r = requests.get(BUCKET_URL + "/", params=params, timeout=60)
        r.raise_for_status()
        keys += re.findall(r"<Key>([^<]+)</Key>", r.text)
        m = re.search(r"<NextContinuationToken>([^<]+)</NextContinuationToken>", r.text)
        if not m:
            return keys
        token = m.group(1)


def available_scenes(start, end, channels):
    """Kembalikan {waktu: {kanal: key}} untuk scene yang semua kanalnya sudah tersedia."""
    scenes = {}
    hour = start.replace(minute=0, second=0, microsecond=0)
    while hour <= end:
        for key in list_keys(f"{PREFIX}/{hour:%Y%m/%d/%H}/"):
            m = FILE_RE.search(key)
            if not m:
                continue
            t = dt.datetime.strptime(m.group(2), "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)
            if start <= t <= end:
                scenes.setdefault(t, {})[m.group(1)] = key
        hour += dt.timedelta(hours=1)
    return {t: ch for t, ch in scenes.items() if all(c in ch for c in channels)}


# ---------------------------------------------------------------- pemrosesan satpy
def make_area(res):
    from pyresample import create_area_def

    lon0, lat0, lon1, lat1 = INDONESIA_BBOX
    return create_area_def("indonesia", "EPSG:4326", area_extent=[lon0, lat0, lon1, lat1],
                           resolution=res, units="degrees")


def render(keys, product, area):
    """Baca scene langsung dari S3, potong wilayah Indonesia, kembalikan PIL.Image RGB."""
    import satpy
    from satpy import Scene
    from satpy.readers.core.remote import FSFile
    from satpy.writers import get_enhanced_image

    satpy.config.set(config_path=[str(SATPY_CONFIG)])
    fs = fsspec.filesystem("http", client_kwargs={"trust_env": True})
    files = [FSFile(fs.open(f"{BUCKET_URL}/{k}", block_size=8 * 2**20, cache_type="blockcache"), fs)
             for k in keys]
    scn = Scene(files, reader="ami_l1b")
    scn.load([product])
    lon0, lat0, lon1, lat1 = INDONESIA_BBOX
    local = scn.crop(ll_bbox=(lon0 - 1, lat0 - 1, lon1 + 1, lat1 + 1))
    res = local.resample(area, resampler="nearest", radius_of_influence=5000)
    img = get_enhanced_image(res[product]).pil_image()
    del scn, local, res
    gc.collect()
    if img.mode == "RGBA":
        bg = Image.new("RGB", img.size, (0, 0, 0))
        bg.paste(img, mask=img.split()[3])
        img = bg
    return img.convert("RGB")


def draw_lines(img, area_res, scale):
    """Gambar garis pantai & batas negara (proyeksi lat/lon, jadi konversinya linear)."""
    lines = json.loads(LINES_FILE.read_text())
    lon0, _, _, lat1 = INDONESIA_BBOX
    px = scale / area_res  # piksel per derajat
    draw = ImageDraw.Draw(img)
    styles = {"coastline": ((255, 255, 0), 1), "borders": ((255, 140, 0), 1)}
    for name, (color, width) in styles.items():
        for line in lines[name]:
            pts = [((x - lon0) * px, (lat1 - y) * px) for x, y in line]
            if len(pts) > 1:
                draw.line(pts, fill=color, width=width)


def draw_label(img, t):
    """Label kecil di pojok kanan atas: GK2A AMI <tanggal jam WIB> YD1BUX."""
    draw = ImageDraw.Draw(img)
    size = max(12, img.width // 110)
    try:
        font = ImageFont.load_default(size=size)
    except TypeError:  # Pillow lama
        font = ImageFont.load_default()
    text = f"GK2A AMI  {t.astimezone(WIB):%Y-%m-%d %H:%M} WIB  YD1BUX"
    pad = size // 2
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    x = img.width - pad - right
    y = pad - top
    draw.rectangle((x + left - pad // 2, y + top - pad // 2, x + right + pad // 2, y + bottom + pad // 2),
                   fill=(0, 0, 0))
    draw.text((x, y), text, fill=(255, 255, 255), font=font)


def make_frame(t, channel_keys, products, res, width, out_path):
    area = make_area(res)
    for product in products:
        keys = [channel_keys[c] for c in PRODUCT_CHANNELS[product]]
        try:
            t0 = time.time()
            img = render(keys, product, area)
            log.info("%s: %s selesai dalam %.0f detik", t, product, time.time() - t0)
            break
        except Exception:
            log.exception("%s: produk %s gagal, mencoba produk berikutnya", t, product)
    else:
        return False
    scale = width / img.width
    if scale < 1:
        img = img.resize((width, round(img.height * scale)), Image.LANCZOS)
    else:
        scale = 1.0
    draw_lines(img, res, scale)
    draw_label(img, t)
    img.save(out_path, quality=88, optimize=True, comment=LABEL_TAG)
    return True


# ---------------------------------------------------------------- animasi & situs
def is_blank(path):
    """Frame rusak yang hampir seluruhnya hitam (mis. true_color di malam hari tanpa komposit IR)."""
    with Image.open(path) as im:
        im = im.convert("RGB")
        im.thumbnail((300, 150))
        px = im.get_flattened_data() if hasattr(im, "get_flattened_data") else im.getdata()
        dark = sum(1 for p in px if max(p) < 12)
        return dark / (im.width * im.height) > 0.5


def has_current_label(path):
    with Image.open(path) as im:
        return im.info.get("comment") == LABEL_TAG


def frame_time(path):
    return dt.datetime.strptime(path.stem.split("_")[1], "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)


def make_animations(frames, site, fps, anim_width):
    if not frames or not shutil.which("ffmpeg"):
        log.warning("Tidak ada frame atau ffmpeg tidak ditemukan; animasi dilewati")
        return
    with tempfile.TemporaryDirectory() as tmp:
        lst = Path(tmp) / "list.txt"
        # Frame terakhir diulang supaya animasi "berhenti" sebentar di citra terbaru.
        seq = frames + [frames[-1]] * fps
        lst.write_text("".join(f"file '{f.resolve()}'\nduration {1 / fps}\n" for f in seq))
        common = ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(lst)]
        scale = f"scale={anim_width}:-2:flags=lanczos"
        subprocess.run(common + ["-vf", f"{scale},format=yuv420p", "-c:v", "libx264", "-crf", "23",
                                 "-movflags", "+faststart", "-r", str(fps),
                                 str(site / "animation.mp4")], check=True)
        gif_scale = f"scale={min(anim_width, 1000)}:-1:flags=lanczos"
        subprocess.run(common + ["-vf", f"{gif_scale},split[a][b];[a]palettegen=max_colors=192[p];"
                                        f"[b][p]paletteuse=dither=sierra2_4a", "-r", str(fps),
                                 str(site / "animation.gif")], check=True)


def write_site(frames, site):
    shutil.copy(frames[-1], site / "latest.jpg")
    manifest = {
        "satellite": "GK2A",
        "region": "Indonesia",
        "bbox": INDONESIA_BBOX,
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "latest": "latest.jpg",
        "animation_mp4": "animation.mp4",
        "animation_gif": "animation.gif",
        "frames": [{"time": frame_time(f).isoformat(), "url": f"frames/{f.name}"} for f in frames],
    }
    (site / "frames.json").write_text(json.dumps(manifest, indent=1))
    for f in (ROOT / "web").iterdir():
        shutil.copy(f, site / f.name)
    (site / ".nojekyll").touch()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--site", default="site", type=Path, help="folder output situs")
    p.add_argument("--hours", type=float, default=3, help="panjang animasi (jam)")
    p.add_argument("--max-new", type=int, default=3, help="maksimum frame baru per eksekusi")
    p.add_argument("--product", default="true_color_night_clouds", choices=list(PRODUCT_CHANNELS),
                   help="produk satpy (otomatis turun ke produk berikutnya bila gagal)")
    p.add_argument("--res", type=float, default=0.01, help="resolusi pemrosesan (derajat)")
    p.add_argument("--width", type=int, default=2400, help="lebar frame JPEG (piksel)")
    p.add_argument("--anim-width", type=int, default=1280, help="lebar animasi MP4 (piksel)")
    p.add_argument("--fps", type=int, default=6)
    p.add_argument("--end", help="waktu akhir UTC, format YYYYmmddHHMM (default: sekarang)")
    p.add_argument("--frames-only", action="store_true",
                   help="hanya buat frame (untuk backfill arsip), tanpa animasi & file situs")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    order = list(PRODUCT_CHANNELS)
    products = order[order.index(args.product):]
    frames_dir = args.site / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)

    end = (dt.datetime.strptime(args.end, "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)
           if args.end else dt.datetime.now(dt.timezone.utc))
    start = end - dt.timedelta(hours=args.hours)

    scenes = available_scenes(start, end, PRODUCT_CHANNELS[products[-1]])
    for f in frames_dir.glob("gk2a_*.jpg"):
        if is_blank(f):
            log.warning("%s hampir seluruhnya hitam; dihapus dan dibuat ulang", f.name)
            f.unlink()
    # Frame berlabel lama tetap dipakai sampai versi barunya selesai dibuat (ditimpa).
    existing = {frame_time(f) for f in frames_dir.glob("gk2a_*.jpg") if has_current_label(f)}
    todo = sorted((t for t in scenes if t not in existing), reverse=True)[:args.max_new]
    log.info("%d scene tersedia, %d frame terkini, memproses %d", len(scenes), len(existing), len(todo))

    for t in todo:
        ch = scenes[t]
        usable = [pr for pr in products if all(c in ch for c in PRODUCT_CHANNELS[pr])]
        make_frame(t, ch, usable, args.res, args.width, frames_dir / f"gk2a_{t:%Y%m%d%H%M}.jpg")

    for f in frames_dir.glob("gk2a_*.jpg"):
        if frame_time(f) < start:
            f.unlink()
    frames = sorted(frames_dir.glob("gk2a_*.jpg"), key=frame_time)
    if args.frames_only:
        log.info("Selesai: %d frame", len(frames))
        return 0
    if not frames:
        log.error("Belum ada frame yang berhasil dibuat")
        return 1
    if todo or not (args.site / "animation.mp4").exists():
        make_animations(frames, args.site, args.fps, args.anim_width)
    write_site(frames, args.site)
    log.info("Selesai: %d frame (%s s/d %s)", len(frames), frame_time(frames[0]), frame_time(frames[-1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
