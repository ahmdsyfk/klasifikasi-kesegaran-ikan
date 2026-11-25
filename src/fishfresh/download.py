"""Unduh dataset publik dari Mendeley Data (CC BY 4.0).

MFED v2 : 10.17632/67nmx3mhwh.2  (satu berkas zip ~3,7 GB)
FFE  v1 : 10.17632/xzyx7pbr3w.1  (24 folder "<Spesies> - <Kelas>", ~4.392 citra)
"""

from __future__ import annotations

import hashlib
import http.client
import json
import random
import time
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from tqdm import tqdm

API = "https://data.mendeley.com/public-api/datasets"
MFED = ("67nmx3mhwh", 2)
FFE = ("xzyx7pbr3w", 1)
UA = {"User-Agent": "fishfresh-research-downloader"}


class ChecksumError(OSError):
    pass


def _retry(fn, tries: int = 6):
    for i in range(tries):
        try:
            return fn()
        except (OSError, http.client.HTTPException) as e:
            if i == tries - 1:
                raise
            wait = 2**i + random.random()
            print(f"  [ulang {i + 1}/{tries - 1} dalam {wait:.0f}s] {type(e).__name__}: {e}")
            time.sleep(wait)


def _get_json(url: str):
    def go():
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read())

    return _retry(go)


def _download(url: str, dest: Path, sha256: str | None = None, show: bool = True) -> None:
    _retry(lambda: _download_once(url, dest, sha256, show))


def _download_once(url: str, dest: Path, sha256: str | None = None, show: bool = True) -> None:
    if dest.exists() and (sha256 is None or _sha256(dest) == sha256):
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        bar = tqdm(total=total, unit="B", unit_scale=True, desc=dest.name, disable=not show)
        while chunk := r.read(1 << 20):
            f.write(chunk)
            bar.update(len(chunk))
        bar.close()
    if sha256 and _sha256(tmp) != sha256:
        tmp.unlink()
        raise ChecksumError(f"Checksum tidak cocok: {dest}")
    tmp.rename(dest)


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while b := f.read(1 << 20):
            h.update(b)
    return h.hexdigest()


def download_mfed(raw_dir: Path, out_dir: Path) -> None:
    ds, ver = MFED
    files = _get_json(f"{API}/{ds}/files?folder_id=root&version={ver}")
    for f in files:
        zpath = raw_dir / "MFED.zip"
        cd = f["content_details"]
        print(f"MFED: {f['filename']} ({f['size'] / 2**30:.2f} GB)")
        _download(cd["download_url"], zpath, cd.get("sha256_hash"))
        if not out_dir.exists() or not any(out_dir.iterdir()):
            print(f"Ekstrak ke {out_dir} ...")
            with zipfile.ZipFile(zpath) as z:
                z.extractall(out_dir)


def download_ffe(out_dir: Path, workers: int = 4) -> None:
    ds, ver = FFE
    folders = _get_json(f"{API}/{ds}/folders/{ver}")
    jobs = []
    for fo in folders:
        name = " ".join(fo["name"].split())  # rapikan spasi ganda ("Nibea Albiflora -  Highly Fresh")
        files = _get_json(f"{API}/{ds}/files?folder_id={fo['id']}&version={ver}")
        for f in files:
            cd = f["content_details"]
            jobs.append((cd["download_url"], out_dir / name / f["filename"], cd.get("sha256_hash")))
    print(f"FFE: {len(folders)} folder, {len(jobs)} berkas")
    failed = []

    def job(j):
        try:
            _download(*j, show=False)
        except Exception as e:  # noqa: BLE001 - catat lalu lanjut ke berkas lain
            failed.append((str(j[1]), f"{type(e).__name__}: {e}"))

    with ThreadPoolExecutor(workers) as ex:
        list(tqdm(ex.map(job, jobs), total=len(jobs), desc="FFE"))
    if failed:
        log = out_dir / "_gagal_unduh.txt"
        log.write_text("\n".join(f"{p}\t{e}" for p, e in failed))
        print(f"[peringatan] {len(failed)} berkas gagal diunduh -> {log} (jalankan ulang untuk mencoba lagi)")


def download_all(cfg: dict, which: str = "all") -> None:
    mfed_dir = Path(cfg["paths"]["mfed_dir"])
    raw = mfed_dir.parent
    if which in ("all", "mfed"):
        download_mfed(raw, mfed_dir)
    if which in ("all", "ffe"):
        download_ffe(Path(cfg["paths"]["ffe_dir"]))
