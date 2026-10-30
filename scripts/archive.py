"""Arsipkan frame GK2A ke GitHub Releases dan tulis archive.json untuk aplikasi Android.

Dua koleksi, masing-masing satu release per hari (waktu UTC):
- archive-YYYYMMDD: citra Indonesia (gk2a_YYYYMMDDHHMM.jpg), dari <site>/frames, disimpan --days hari.
- sectors-YYYYMMDD: full disk & sektor (<id>_YYYYMMDDHHMM.jpg), dari --sectors-dir, disimpan --sector-days hari.

archive.json dibangun ulang dari daftar release, jadi frame hasil backfill ikut terdaftar.
Butuh env GITHUB_TOKEN (izin contents: write) dan GITHUB_REPOSITORY.
"""
import argparse
import datetime as dt
import json
import logging
import os
import re
import sys
from pathlib import Path

import requests

log = logging.getLogger("archive")
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"

# Nama produk (bahasa Inggris, untuk viewer web); aplikasi memakai teks terjemahannya sendiri.
SECTOR_NAMES = {
    "fd": "Full Disk",
    "sea": "Southeast Asia",
    "eas": "East Asia",
    "aus": "Australia",
    "wpac": "Western Pacific",
    "sas": "South Asia",
}


class Collection:
    def __init__(self, prefix, pattern, title):
        self.prefix = prefix
        self.re = re.compile(pattern)
        self.title = title

    def match(self, name):
        """(produk, tanggal, jam) atau None."""
        m = self.re.match(name)
        return (m.group("product"), m.group("date"), m.group("time")) if m else None


INDONESIA = Collection("archive-", r"^(?P<product>gk2a)_(?P<date>\d{8})(?P<time>\d{4})\.jpg$",
                       "GK2A true color frames of Indonesia")
SECTORS = Collection("sectors-", r"^(?P<product>" + "|".join(SECTOR_NAMES) + r")_(?P<date>\d{8})(?P<time>\d{4})\.jpg$",
                     "GK2A full disk and regional sectors")


def session(token):
    s = requests.Session()
    s.headers.update({"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                      "X-GitHub-Api-Version": "2022-11-28"})
    return s


def list_releases(s, repo):
    releases, url = {}, f"{API}/repos/{repo}/releases?per_page=100"
    while url:
        r = s.get(url, timeout=60)
        r.raise_for_status()
        for rel in r.json():
            if rel["tag_name"].startswith((INDONESIA.prefix, SECTORS.prefix)):
                releases[rel["tag_name"]] = rel
        url = r.links.get("next", {}).get("url")
    return releases


def all_assets(s, repo, rel):
    """{nama: (id, ukuran)} semua asset (endpoint releases hanya memuat sebagian bila asset banyak)."""
    assets, url = {}, f"{API}/repos/{repo}/releases/{rel['id']}/assets?per_page=100"
    while url:
        r = s.get(url, timeout=60)
        r.raise_for_status()
        assets.update({a["name"]: (a["id"], a["size"]) for a in r.json()})
        url = r.links.get("next", {}).get("url")
    return assets


def create_release(s, repo, coll, day):
    date = f"{day[:4]}-{day[4:6]}-{day[6:]}"
    r = s.post(f"{API}/repos/{repo}/releases", timeout=60, json={
        "tag_name": coll.prefix + day,
        "name": f"{'Archive' if coll is INDONESIA else 'Sectors'} {date}",
        "body": f"{coll.title} for {date} (UTC), every 10 minutes.",
        "make_latest": "false",
    })
    r.raise_for_status()
    log.info("Release %s dibuat", coll.prefix + day)
    return r.json()


def delete_release(s, repo, rel):
    s.delete(f"{API}/repos/{repo}/releases/{rel['id']}", timeout=60).raise_for_status()
    s.delete(f"{API}/repos/{repo}/git/refs/tags/{rel['tag_name']}", timeout=60)
    log.info("Release %s dihapus (lewat masa simpan)", rel["tag_name"])


def upload(s, repo, coll, files, releases, assets):
    uploaded = 0
    for f in sorted(files):
        m = coll.match(f.name)
        if not m:
            continue
        tag = coll.prefix + m[1]
        if tag not in releases:
            releases[tag] = create_release(s, repo, coll, m[1])
            assets[tag] = {}
        old = assets[tag].get(f.name)
        if old and old[1] == f.stat().st_size:
            continue
        if old:
            # Frame dibuat ulang (mis. dulu hitam, atau label baru): ganti asset lama.
            s.delete(f"{API}/repos/{repo}/releases/assets/{old[0]}", timeout=60).raise_for_status()
            log.info("%s diganti dengan versi baru", f.name)
        rel = releases[tag]
        r = s.post(f"{UPLOADS}/repos/{repo}/releases/{rel['id']}/assets", params={"name": f.name},
                   headers={"Content-Type": "image/jpeg"}, data=f.read_bytes(), timeout=120)
        r.raise_for_status()
        assets[tag][f.name] = (r.json()["id"], f.stat().st_size)
        uploaded += 1
    log.info("%s: %d frame baru diarsipkan", coll.prefix.rstrip("-"), uploaded)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--site", default="site", type=Path)
    p.add_argument("--days", type=int, default=30, help="masa simpan arsip Indonesia (hari)")
    p.add_argument("--sectors-dir", type=Path, help="folder frame full disk & sektor")
    p.add_argument("--sector-days", type=int, default=7, help="masa simpan arsip sektor (hari)")
    p.add_argument("--no-index", action="store_true", help="jangan tulis archive.json")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    repo = os.environ["GITHUB_REPOSITORY"]
    s = session(os.environ["GITHUB_TOKEN"])
    releases = list_releases(s, repo)
    assets = {tag: all_assets(s, repo, rel) for tag, rel in releases.items()}

    upload(s, repo, INDONESIA, (args.site / "frames").glob("gk2a_*.jpg"), releases, assets)
    if args.sectors_dir and args.sectors_dir.is_dir():
        upload(s, repo, SECTORS, args.sectors_dir.glob("*.jpg"), releases, assets)

    now = dt.datetime.now(dt.timezone.utc)
    for coll, days in ((INDONESIA, args.days), (SECTORS, args.sector_days)):
        cutoff = (now - dt.timedelta(days=days)).strftime("%Y%m%d")
        for tag in [t for t in releases if t.startswith(coll.prefix) and t.removeprefix(coll.prefix) < cutoff]:
            delete_release(s, repo, releases.pop(tag))
            assets.pop(tag, None)

    if args.no_index:
        return 0
    # products[id]["days"][YYYYMMDD] = ["HHMM", ...]
    products = {}
    for tag, names in assets.items():
        coll = INDONESIA if tag.startswith(INDONESIA.prefix) else SECTORS
        for n in names:
            m = coll.match(n)
            if m:
                pid = "indonesia" if coll is INDONESIA else m[0]
                products.setdefault(pid, {}).setdefault(m[1], []).append(m[2])
    base_url = f"https://github.com/{repo}/releases/download"
    order = ["indonesia", *SECTOR_NAMES]
    index_products = {}
    for pid in [p for p in order if p in products]:
        coll, prefix = (INDONESIA, "gk2a") if pid == "indonesia" else (SECTORS, pid)
        index_products[pid] = {
            "name": "Indonesia" if pid == "indonesia" else SECTOR_NAMES[pid],
            "url_template": f"{base_url}/{coll.prefix}{{date}}/{prefix}_{{date}}{{time}}.jpg",
            "retention_days": args.days if pid == "indonesia" else args.sector_days,
            "days": {d: sorted(t) for d, t in sorted(products[pid].items())},
        }
    indonesia = index_products.get("indonesia", {})
    index = {
        "updated": now.isoformat(timespec="seconds"),
        # Kolom lama (aplikasi v1.0.4 ke bawah hanya membaca ini).
        "url_template": f"{base_url}/archive-{{date}}/gk2a_{{date}}{{time}}.jpg",
        "retention_days": args.days,
        "days": indonesia.get("days", {}),
        "products": index_products,
    }
    (args.site / "archive.json").write_text(json.dumps(index, separators=(",", ":")))
    log.info("archive.json: %s", {pid: sum(len(v) for v in p["days"].values()) for pid, p in index_products.items()})
    return 0


if __name__ == "__main__":
    sys.exit(main())
