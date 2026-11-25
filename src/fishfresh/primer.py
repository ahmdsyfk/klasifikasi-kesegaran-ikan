"""Tarik DATA PRIMER (foto pasar dari aplikasi Android) dari Supabase ke data/raw/PRIMER.

Struktur hasil, kompatibel dengan data.scan_dataset() / infer_label() / infer_species():
  data/raw/PRIMER/<dataset>/full/<Spesies> - <Highly Fresh|Fresh|Not Fresh>/<id>.jpg   foto asli
  data/raw/PRIMER/<dataset>/eye/<Spesies> - <Kelas>/<id>.png    crop mata 224 bermasker (eye_roi)
  data/raw/PRIMER/<dataset>/metadata.csv                        satu baris per foto

Crop mata DIBUAT ULANG di laptop dari foto asli + (crop_cx, crop_cy, crop_r) dengan fungsi yang
sama persis dengan data latih (eye_roi.crop_eye), sehingga praproses HP tidak memengaruhi
eksperimen. Kolom `fish_code` wajib dipakai sebagai grup saat split (foto ikan yang sama
tidak boleh tersebar ke train/val/test).

Kredensial: SUPABASE_URL dan SUPABASE_SECRET_KEY di environment (lihat supabase_rest.py).

Contoh:
  uv run python -m fishfresh.primer pull                      # semua dataset
  uv run python -m fishfresh.primer pull --dataset primer_1
  uv run python -m fishfresh.primer progress                  # ringkasan jumlah per kelas
"""

from __future__ import annotations

import argparse
import csv
import io
from pathlib import Path

LABEL_DIR = {"highly_fresh": "Highly Fresh", "fresh": "Fresh", "not_fresh": "Not Fresh"}
BUCKET = "fish-eyes"
CSV_FIELDS = [
    "id", "dataset_id", "fish_code", "species", "label", "eye_side", "market", "latitude",
    "longitude", "captured_at", "raw_width", "raw_height", "raw_sha256", "crop_cx", "crop_cy",
    "crop_r", "crop_margin", "crop_adjusted", "sharpness_lapvar", "brightness_mean",
    "device_model", "app_version", "notes", "created_by", "model_id", "model_version",
    "score_highly_fresh", "score_fresh", "score_not_fresh", "pred_label", "inference_ms",
    "shown_before_label", "file_full", "file_eye",
]


def class_dir(row: dict) -> str:
    species = " ".join(str(row["species"]).split()).title() or "Unknown"
    return f"{species} - {LABEL_DIR[row['label']]}"


def crop_from_circle(im, cx: float, cy: float, r: float, out_size: int = 224):
    """Crop mata identik eye_roi.crop_eye: kotak bersisi 2r berpusat (cx, cy), margin sudah di r."""
    from .eye_roi import crop_eye

    box = (None, cx - r, cy - r, cx + r, cy + r)
    _, masked = crop_eye(im, box, 0.0, out_size)
    return masked


def pull(dataset: str | None, out_root: Path, out_size: int) -> None:
    from PIL import Image, ImageOps

    from .supabase_rest import Supabase

    sb = Supabase()
    params = {"select": "*", "order": "captured_at.asc"}
    if dataset:
        params["dataset_id"] = f"eq.{dataset}"
    rows = sb.select_all("v_export", params)
    print(f"{len(rows)} foto di Supabase")

    by_ds: dict[str, list[dict]] = {}
    new = 0
    for row in rows:
        base = out_root / row["dataset_id"]
        full = base / "full" / class_dir(row) / f"{row['id']}.jpg"
        eye = base / "eye" / class_dir(row) / f"{row['id']}.png"
        if not full.exists():
            full.parent.mkdir(parents=True, exist_ok=True)
            full.write_bytes(sb.download(BUCKET, row["raw_path"]))
            new += 1
        if not eye.exists():
            eye.parent.mkdir(parents=True, exist_ok=True)
            # Foto asli dari HP sudah tegak; exif_transpose tetap dipanggil sama seperti data latih.
            with Image.open(full) as im:
                im = ImageOps.exif_transpose(im).convert("RGB")
                crop_from_circle(im, row["crop_cx"], row["crop_cy"], row["crop_r"], out_size).save(eye)
        row["file_full"] = str(full.relative_to(base))
        row["file_eye"] = str(eye.relative_to(base))
        by_ds.setdefault(row["dataset_id"], []).append(row)

    for ds, rs in by_ds.items():
        _write_csv(out_root / ds / "metadata.csv", rs)
    print(f"{new} foto baru diunduh ke {out_root}")


def _write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, CSV_FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def progress() -> None:
    from .supabase_rest import Supabase

    rows = Supabase().select_all("v_progress", {"select": "*", "order": "dataset_id,species,label"})
    buf = io.StringIO()
    w = csv.writer(buf, delimiter="\t")
    w.writerow(["dataset", "spesies", "kelas", "foto", "ikan"])
    for r in rows:
        w.writerow([r["dataset_id"], r["species"], r["label"], r["n_photos"], r["n_fish"]])
    print(buf.getvalue() if rows else "Belum ada data.")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pull", help="unduh foto + metadata dari Supabase")
    p.add_argument("--dataset", default=None, help="mis. primer_1 (default: semua)")
    p.add_argument("--out", type=Path, default=Path("data/raw/PRIMER"))
    p.add_argument("--size", type=int, default=224, help="ukuran crop mata")
    sub.add_parser("progress", help="jumlah foto per dataset/spesies/kelas")
    a = ap.parse_args(argv)
    if a.cmd == "pull":
        pull(a.dataset, a.out, a.size)
    else:
        progress()


if __name__ == "__main__":
    main()
