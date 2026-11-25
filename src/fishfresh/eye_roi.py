"""Isolasi area mata ikan + penyaringan citra tak berguna.

Tahapan (`uv run --extra roi fishfresh roi`):
1. Deteksi mata zero-shot dengan OWLv2 (google/owlv2-base-patch16-ensemble), prompt teks
   "a photo of a fish eye" / "an eye". Tidak butuh anotasi manual. Hasil di-cache per SHA-256.
2. Pemilihan kotak: buang kotak yang terlalu besar (mis. seluruh kepala) atau rasio sisi tak wajar,
   ambil skor tertinggi; bila ada kotak lebih besar yang memuat kotak terbaik dengan skor
   sebanding (kasus hanya pupil yang terdeteksi), kotak itu dipakai agar iris ikut tercakup.
3. Crop persegi berpusat di mata (sisi = max(w, h) x (1 + margin)), resize ke image_size,
   lalu area di luar lingkaran yang menyinggung tepi persegi dihitamkan (RGB 0).
   -> Model hanya melihat mata (kornea, pupil, iris), bukan kulit/insang/latar.
4. Metrik kualitas pada area mata: ukuran mata (px), ketajaman (variansi Laplacian),
   kecerahan rata-rata, dan fraksi piksel jenuh. Citra yang gagal kriteria dibuang dan dicatat.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image, ImageOps
from tqdm import tqdm

OWL_ID = "google/owlv2-base-patch16-ensemble"
PROMPTS = ["a photo of a fish eye", "an eye"]
DET_COLUMNS = ["sha256", "W", "H", "n_boxes", "boxes"]


def roi_dir(cfg: dict) -> Path:
    d = Path(cfg["paths"]["roi_dir"])
    d.mkdir(parents=True, exist_ok=True)
    return d


def open_rgb(path_or_file) -> Image.Image:
    with Image.open(path_or_file) as im:
        return ImageOps.exif_transpose(im).convert("RGB")


# ------------------------------------------------------------------ deteksi
class EyeDetector:
    def __init__(self, device: str | None = None, min_score: float = 0.02, top_k: int = 15):
        import torch
        from transformers import Owlv2ForObjectDetection, Owlv2Processor

        if device is None:
            device = "cuda" if torch.cuda.is_available() else (
                "mps" if torch.backends.mps.is_available() else "cpu")
        self.torch = torch
        self.device = device
        self.proc = Owlv2Processor.from_pretrained(OWL_ID)
        self.model = Owlv2ForObjectDetection.from_pretrained(OWL_ID).to(device).eval()
        self.min_score = min_score
        self.top_k = top_k

    def __call__(self, images: list[Image.Image]) -> list[list[tuple]]:
        """Kembalikan, per citra, daftar (score, x0, y0, x1, y1) dalam koordinat piksel asli."""
        torch = self.torch
        inp = self.proc(text=[PROMPTS] * len(images), images=images, return_tensors="pt").to(self.device)
        with torch.no_grad():
            out = self.model(**inp)
        # OWLv2 mem-pad citra menjadi persegi di kanan/bawah -> skala ke sisi terpanjang
        sizes = torch.tensor([[max(im.size)] * 2 for im in images])
        res = self.proc.post_process_grounded_object_detection(
            out, threshold=self.min_score, target_sizes=sizes)
        boxes = []
        for im, r in zip(images, res):
            W, H = im.size
            sc = r["scores"].cpu().numpy()
            bx = r["boxes"].cpu().numpy()
            order = np.argsort(-sc)[: self.top_k]
            lst = []
            for k in order:
                x0, y0, x1, y1 = bx[k]
                x0, x1 = float(np.clip(x0, 0, W)), float(np.clip(x1, 0, W))
                y0, y1 = float(np.clip(y0, 0, H)), float(np.clip(y1, 0, H))
                if x1 - x0 > 2 and y1 - y0 > 2:
                    lst.append((round(float(sc[k]), 4), round(x0, 1), round(y0, 1), round(x1, 1), round(y1, 1)))
            boxes.append(lst)
        return boxes


def run_detection(items: list[tuple[str, object]], cache_path: Path, batch: int = 8,
                  device: str | None = None) -> pd.DataFrame:
    """items: [(sha256, path_atau_fileobj_factory)]. Hanya sha256 yang belum ada di cache diproses."""
    cache = pd.read_csv(cache_path) if cache_path.exists() else pd.DataFrame(columns=DET_COLUMNS)
    done = set(cache["sha256"])
    todo = []
    seen = set()
    for sha, src in items:
        if sha not in done and sha not in seen:
            todo.append((sha, src))
            seen.add(sha)
    if not todo:
        return cache
    det = EyeDetector(device=device)
    rows = []
    for s in tqdm(range(0, len(todo), batch), desc=f"deteksi mata ({det.device})"):
        chunk = todo[s : s + batch]
        ims = [open_rgb(src() if callable(src) else src) for _, src in chunk]
        for (sha, _), im, bx in zip(chunk, ims, det(ims)):
            rows.append({"sha256": sha, "W": im.size[0], "H": im.size[1], "n_boxes": len(bx),
                         "boxes": ";".join(",".join(map(str, b)) for b in bx)})
        if len(rows) >= 400:  # simpan bertahap (tahan putus)
            cache = pd.concat([cache, pd.DataFrame(rows)], ignore_index=True)
            cache.to_csv(cache_path, index=False)
            rows = []
    cache = pd.concat([cache, pd.DataFrame(rows)], ignore_index=True)
    cache.to_csv(cache_path, index=False)
    return cache


def parse_boxes(s) -> np.ndarray:
    if not isinstance(s, str) or not s:
        return np.zeros((0, 5))
    return np.array([[float(v) for v in b.split(",")] for b in s.split(";")])


def select_box(boxes: np.ndarray, W: int, H: int, rcfg: dict, dataset: str) -> tuple[np.ndarray | None, str]:
    """Pilih kotak mata. Kembalikan (score,x0,y0,x1,y1) atau None + alasan."""
    if len(boxes) == 0:
        return None, "tidak ada kandidat kotak"
    w = boxes[:, 3] - boxes[:, 1]
    h = boxes[:, 4] - boxes[:, 2]
    area = w * h / (W * H)
    aspect = w / np.maximum(h, 1e-6)
    max_area = rcfg["max_box_area_frac"].get(dataset, 1.0)
    lo, hi = rcfg["aspect_range"]
    valid = (area <= max_area) & (aspect >= lo) & (aspect <= hi)
    if not valid.any():
        return None, "semua kandidat terlalu besar / bentuk tak wajar"
    idx = np.flatnonzero(valid)
    b = idx[np.argmax(boxes[idx, 0])]
    best = boxes[b]
    # perluasan: hanya pupil terdeteksi -> pakai kotak yang memuatnya bila skornya sebanding
    bx0, by0, bx1, by1 = best[1:]
    barea = (bx1 - bx0) * (by1 - by0)
    ix = np.clip(np.minimum(boxes[:, 3], bx1) - np.maximum(boxes[:, 1], bx0), 0, None)
    iy = np.clip(np.minimum(boxes[:, 4], by1) - np.maximum(boxes[:, 2], by0), 0, None)
    contains = ix * iy / max(barea, 1e-6) >= 0.9
    ratio = (w * h) / max(barea, 1e-6)
    grow = valid & contains & (ratio > 1.3) & (ratio <= rcfg["grow_max_area_ratio"]) & (
        boxes[:, 0] >= rcfg["grow_min_score_ratio"] * best[0])
    if grow.any():
        g = np.flatnonzero(grow)
        best = boxes[g[np.argmax(ratio[g])]]
    return best, ""


# ------------------------------------------------------------------ crop + masker
def circle_mask(size: int) -> np.ndarray:
    yy, xx = np.mgrid[:size, :size]
    c = (size - 1) / 2
    return ((xx - c) ** 2 + (yy - c) ** 2) <= (size / 2) ** 2


def crop_eye(im: Image.Image, box, margin: float, out_size: int) -> tuple[Image.Image, Image.Image]:
    """Crop persegi berpusat di mata. Kembalikan (crop_tanpa_masker_resolusi_asli, hasil_bermasker)."""
    _, x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    side = max(x1 - x0, y1 - y0) * (1 + margin)
    l, t = round(cx - side / 2), round(cy - side / 2)
    s = max(1, round(side))
    canvas = Image.new("RGB", (s, s), (0, 0, 0))  # pad hitam bila keluar batas citra
    canvas.paste(im.crop((max(l, 0), max(t, 0), min(l + s, im.width), min(t + s, im.height))),
                 (max(-l, 0), max(-t, 0)))
    small = canvas.resize((out_size, out_size), Image.LANCZOS)
    arr = np.asarray(small).copy()
    arr[~circle_mask(out_size)] = 0
    return canvas, Image.fromarray(arr)


def quality_metrics(masked: Image.Image) -> dict:
    """Metrik pada area di dalam lingkaran (citra 224 bermasker -> skala seragam)."""
    import cv2

    arr = np.asarray(masked)
    m = circle_mask(arr.shape[0])
    inner = m.copy()
    # erosi tepi lingkaran agar batas masker tidak terhitung sebagai tepi tajam
    yy, xx = np.mgrid[: arr.shape[0], : arr.shape[1]]
    c = (arr.shape[0] - 1) / 2
    inner = ((xx - c) ** 2 + (yy - c) ** 2) <= (arr.shape[0] / 2 - 6) ** 2
    gray = cv2.cvtColor(arr, cv2.COLOR_RGB2GRAY).astype(np.float32)
    lap = cv2.Laplacian(gray, cv2.CV_32F, ksize=3)
    g = gray[m]
    return {
        "sharpness_lapvar": float(lap[inner].var()),
        "brightness_mean": float(g.mean()),
        "clip_dark_frac": float((g <= 5).mean()),
        "clip_bright_frac": float((g >= 250).mean()),
        "contrast_std": float(g.std()),
    }


# ------------------------------------------------------------------ penyaringan
def _thr(q: dict, key: str, dataset: str, default):
    """Ambang boleh skalar atau dict per dataset {MFED: .., FFE: ..}."""
    v = q.get(key, default)
    return v.get(dataset, default) if isinstance(v, dict) else v


def apply_quality_rules(row: dict, rcfg: dict) -> list[tuple[str, str]]:
    """Kembalikan daftar (kriteria, keterangan nilai vs ambang) yang dilanggar."""
    q = rcfg["quality"]
    ds = row["dataset"]
    bad = []
    score = row.get("box_score")
    if score is None or not np.isfinite(score):
        bad.append(("mata_tidak_terdeteksi", row.get("det_note") or "tanpa kotak valid"))
        return bad
    t = _thr(q, "min_det_score", ds, 0.0)
    if score < t:
        bad.append(("mata_tidak_terdeteksi", f"skor deteksi {score:.3f} < {t}"))
    t = _thr(q, "min_eye_side_px", ds, 0)
    if row["eye_side_px"] < t:
        bad.append(("mata_terlalu_kecil", f"sisi mata {row['eye_side_px']:.0f}px < {t}px"))
    t = _thr(q, "min_sharpness", ds, 0.0)
    if row["sharpness_lapvar"] < t:
        bad.append(("buram", f"variansi Laplacian {row['sharpness_lapvar']:.1f} < {t}"))
    t = _thr(q, "min_brightness", ds, 0.0)
    if row["brightness_mean"] < t:
        bad.append(("terlalu_gelap", f"kecerahan rata-rata {row['brightness_mean']:.1f} < {t}"))
    t = _thr(q, "max_brightness", ds, 255.0)
    if row["brightness_mean"] > t:
        bad.append(("terlalu_terang", f"kecerahan rata-rata {row['brightness_mean']:.1f} > {t}"))
    t = _thr(q, "max_clip_frac", ds, 1.0)
    if row["clip_bright_frac"] > t:
        bad.append(("over_exposure", f"piksel jenuh putih {row['clip_bright_frac']:.1%} > {t:.0%}"))
    if row["clip_dark_frac"] > t:
        bad.append(("under_exposure", f"piksel jenuh hitam {row['clip_dark_frac']:.1%} > {t:.0%}"))
    return bad


def build_rois(df: pd.DataFrame, det: pd.DataFrame, cfg: dict, dataset: str) -> pd.DataFrame:
    """Untuk setiap citra unik: pilih kotak, buat crop bermasker, hitung metrik kualitas."""
    rcfg = cfg["roi"]
    out_dir = roi_dir(cfg) / dataset
    out_dir.mkdir(parents=True, exist_ok=True)
    det = det.drop_duplicates("sha256").set_index("sha256")
    size = cfg["image_size"]
    recs = []
    for _, r in tqdm(df.iterrows(), total=len(df), desc=f"crop+masker {dataset}"):
        rec = {"sha256": r["sha256"], "dataset": dataset, "eye_path": "", "box_score": np.nan,
               "det_note": "", "eye_side_px": np.nan}
        if r["sha256"] not in det.index:
            rec["det_note"] = "belum dideteksi"
            recs.append(rec)
            continue
        d = det.loc[r["sha256"]]
        box, note = select_box(parse_boxes(d["boxes"]), int(d["W"]), int(d["H"]), rcfg, dataset)
        if box is None:
            rec["det_note"] = note
            recs.append(rec)
            continue
        im = open_rgb(r["path"])
        _, masked = crop_eye(im, box, rcfg["margin"], size)
        p = out_dir / f"{r['sha256'][:20]}.png"
        masked.save(p)
        rec.update({
            "eye_path": str(p), "box_score": float(box[0]),
            "box_x0": box[1], "box_y0": box[2], "box_x1": box[3], "box_y1": box[4],
            "eye_side_px": float(max(box[3] - box[1], box[4] - box[2])),
            **quality_metrics(masked),
        })
        recs.append(rec)
    return pd.DataFrame(recs)
