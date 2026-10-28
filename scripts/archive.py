"""Arsipkan frame GK2A ke GitHub Releases dan tulis archive.json untuk aplikasi Android.

Satu release per hari (tag archive-YYYYMMDD, waktu UTC), berisi file gk2a_YYYYMMDDHHMM.jpg.
Release yang lebih tua dari --days dihapus. archive.json dibangun ulang dari daftar release,
jadi frame hasil backfill ikut terdaftar.

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
FRAME_RE = re.compile(r"^gk2a_(\d{8})(\d{4})\.jpg$")


def session(token):
    s = requests.Session()
    s.headers.update({"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                      "X-GitHub-Api-Version": "2022-11-28"})
    return s


def list_archive_releases(s, repo):
    releases, url = {}, f"{API}/repos/{repo}/releases?per_page=100"
    while url:
        r = s.get(url, timeout=60)
        r.raise_for_status()
        for rel in r.json():
            if rel["tag_name"].startswith("archive-"):
                releases[rel["tag_name"]] = rel
        url = r.links.get("next", {}).get("url")
    return releases


def all_assets(s, repo, rel):
    """Daftar lengkap nama asset (endpoint releases hanya memuat sebagian bila asset banyak)."""
    names, url = set(), f"{API}/repos/{repo}/releases/{rel['id']}/assets?per_page=100"
    while url:
        r = s.get(url, timeout=60)
        r.raise_for_status()
        names |= {a["name"] for a in r.json()}
        url = r.links.get("next", {}).get("url")
    return names


def create_release(s, repo, tag, day):
    date = f"{day[:4]}-{day[4:6]}-{day[6:]}"
    r = s.post(f"{API}/repos/{repo}/releases", timeout=60, json={
        "tag_name": tag,
        "name": f"Archive {date}",
        "body": f"GK2A true color frames of Indonesia for {date} (UTC), every 10 minutes.",
        "make_latest": "false",
    })
    r.raise_for_status()
    log.info("Release %s dibuat", tag)
    return r.json()


def delete_release(s, repo, rel):
    s.delete(f"{API}/repos/{repo}/releases/{rel['id']}", timeout=60).raise_for_status()
    s.delete(f"{API}/repos/{repo}/git/refs/tags/{rel['tag_name']}", timeout=60)
    log.info("Release %s dihapus (lewat masa simpan)", rel["tag_name"])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--site", default="site", type=Path)
    p.add_argument("--days", type=int, default=30, help="masa simpan arsip (hari)")
    p.add_argument("--no-index", action="store_true", help="jangan tulis archive.json")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    repo = os.environ["GITHUB_REPOSITORY"]
    s = session(os.environ["GITHUB_TOKEN"])
    releases = list_archive_releases(s, repo)
    assets = {tag: all_assets(s, repo, rel) for tag, rel in releases.items()}

    uploaded = 0
    for f in sorted((args.site / "frames").glob("gk2a_*.jpg")):
        m = FRAME_RE.match(f.name)
        if not m:
            continue
        tag = f"archive-{m.group(1)}"
        if tag not in releases:
            releases[tag] = create_release(s, repo, tag, m.group(1))
            assets[tag] = set()
        if f.name in assets[tag]:
            continue
        rel = releases[tag]
        r = s.post(f"{UPLOADS}/repos/{repo}/releases/{rel['id']}/assets", params={"name": f.name},
                   headers={"Content-Type": "image/jpeg"}, data=f.read_bytes(), timeout=120)
        r.raise_for_status()
        assets[tag].add(f.name)
        uploaded += 1
    log.info("%d frame baru diarsipkan", uploaded)

    cutoff = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=args.days)).strftime("%Y%m%d")
    for tag in [t for t in releases if t.removeprefix("archive-") < cutoff]:
        delete_release(s, repo, releases.pop(tag))
        assets.pop(tag, None)

    if args.no_index:
        return 0
    days = {}
    for tag, names in assets.items():
        times = sorted(m.group(2) for n in names if (m := FRAME_RE.match(n)))
        if times:
            days[tag.removeprefix("archive-")] = times
    index = {
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "url_template": f"https://github.com/{repo}/releases/download/archive-{{date}}/gk2a_{{date}}{{time}}.jpg",
        "retention_days": args.days,
        "days": dict(sorted(days.items())),
    }
    (args.site / "archive.json").write_text(json.dumps(index, separators=(",", ":")))
    log.info("archive.json: %d hari, %d frame", len(days), sum(len(v) for v in days.values()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
