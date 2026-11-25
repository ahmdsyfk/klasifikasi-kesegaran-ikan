"""Laporan visual ROI mata: distribusi metrik kualitas + grid QA (untuk menetapkan ambang filter)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image, ImageDraw

from .config import save_json
from .eye_roi import open_rgb, roi_dir

METRICS = ["box_score", "eye_side_px", "sharpness_lapvar", "brightness_mean",
           "clip_bright_frac", "clip_dark_frac"]


def _tile(row, size: int = 200) -> Image.Image:
    """Kiri: citra asli + kotak terpilih; kanan: hasil crop bermasker."""
    t = Image.new("RGB", (size * 2, size + 28), "white")
    try:
        im = open_rgb(row["path"])
        if isinstance(row.get("eye_path"), str) and row["eye_path"]:
            d = ImageDraw.Draw(im)
            d.rectangle([row["box_x0"], row["box_y0"], row["box_x1"], row["box_y1"]],
                        outline=(0, 255, 0), width=max(2, im.width // 120))
            t.paste(Image.open(row["eye_path"]).resize((size, size)), (size, 0))
        im.thumbnail((size, size))
        t.paste(im, (0, 0))
    except Exception as e:  # noqa: BLE001
        ImageDraw.Draw(t).text((4, 4), str(e)[:40], fill="red")
    txt = (f"{row.get('label', '')} s={row.get('box_score', np.nan):.2f} "
           f"sh={row.get('sharpness_lapvar', np.nan):.0f} b={row.get('brightness_mean', np.nan):.0f}")
    ImageDraw.Draw(t).text((4, size + 8), txt, fill="black")
    return t


def qa_grid(df: pd.DataFrame, path: Path, cols: int = 4) -> None:
    if df.empty:
        return
    tiles = [_tile(r) for _, r in df.iterrows()]
    w, h = tiles[0].size
    rows = (len(tiles) + cols - 1) // cols
    g = Image.new("RGB", (w * cols, h * rows), "white")
    for i, t in enumerate(tiles):
        g.paste(t, ((i % cols) * w, (i // cols) * h))
    g.save(path, quality=85)


def roi_report(cfg: dict, n: int = 24, seed: int = 0) -> dict:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    rd = roi_dir(cfg)
    qa = rd / "qa"
    qa.mkdir(exist_ok=True)
    sdir = Path(cfg["paths"]["splits_dir"])
    stats = {}
    frames = []
    for name in ["MFED", "FFE"]:
        p = rd / f"roi_{name.lower()}.csv"
        v = sdir / f"{name.lower()}_verified.pkl"
        if not p.exists() or not v.exists():
            continue
        meta = pd.read_pickle(v)[["sha256", "path", "label"]].drop_duplicates("sha256")
        df = pd.read_csv(p).merge(meta, on="sha256", how="left")
        df["dataset"] = name
        frames.append(df)
        ok = df[df["eye_path"].notna() & (df["eye_path"] != "")]
        stats[name] = {
            "n": len(df), "n_roi": len(ok), "n_tanpa_roi": len(df) - len(ok),
            "persentil": {m: ok[m].quantile([0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99]).round(3).to_dict()
                          for m in METRICS if m in ok},
        }
        qa_grid(ok.sample(min(n, len(ok)), random_state=seed), qa / f"{name}_acak.jpg")
        qa_grid(ok.nsmallest(n, "box_score"), qa / f"{name}_skor_terendah.jpg")
        qa_grid(ok.nsmallest(n, "sharpness_lapvar"), qa / f"{name}_paling_buram.jpg")
        qa_grid(ok.nsmallest(n // 2, "brightness_mean"), qa / f"{name}_paling_gelap.jpg")
        qa_grid(ok.nlargest(n // 2, "brightness_mean"), qa / f"{name}_paling_terang.jpg")
        qa_grid(ok.nsmallest(n, "eye_side_px"), qa / f"{name}_mata_terkecil.jpg")
        qa_grid(df[~df.index.isin(ok.index)].head(n), qa / f"{name}_tanpa_roi.jpg")

    if frames:
        allf = pd.concat(frames, ignore_index=True)
        fig, axes = plt.subplots(2, 3, figsize=(13, 7))
        for m, ax in zip(METRICS, axes.ravel()):
            for name, g in allf.groupby("dataset"):
                ax.hist(g[m].dropna(), bins=50, alpha=0.55, label=name)
            ax.set_title(m)
            ax.legend()
        fig.tight_layout()
        fig.savefig(qa / "distribusi_metrik.png", dpi=120)
        plt.close(fig)
    save_json(stats, rd / "roi_statistik.json")
    return stats
