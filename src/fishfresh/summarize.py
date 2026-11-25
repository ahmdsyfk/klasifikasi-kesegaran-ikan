"""Rekap semua hasil ke tabel perbandingan + uji McNemar antar-arsitektur."""

from __future__ import annotations

import itertools
import json
from pathlib import Path

import pandas as pd
from scipy.stats import binomtest


def _load(p: Path) -> dict | None:
    return json.loads(p.read_text()) if p.exists() else None


def collect(cfg: dict) -> pd.DataFrame:
    base = Path(cfg["paths"]["outputs_dir"]) / cfg["run_name"]
    rows = []
    for seed_dir in sorted(base.glob("seed*")):
        for arch_dir in sorted(p for p in seed_dir.iterdir() if p.is_dir()):
            r = {"seed": int(seed_dir.name[4:]), "arch": arch_dir.name}
            meta = _load(arch_dir / "train_meta.json")
            if meta is None:
                continue
            r.update(
                params_total=meta["params_final"]["total"],
                model_file_mb=meta["model_file_mb"],
                train_time_min=meta["train_time_s"] / 60,
                epochs=meta["epochs_phase1"] + meta["epochs_phase2"],
            )
            for ds in ["mfed_test", "ffe"]:
                m = _load(arch_dir / f"metrics_{ds}.json")
                if m:
                    for k in ["accuracy", "precision_macro", "recall_macro", "f1_macro"]:
                        r[f"{ds}_{k}"] = m[k]
            g = _load(arch_dir / "generalization.json")
            if g:
                r["delta_f1"] = g["delta_f1"]
            e = _load(arch_dir / "efficiency.json")
            if e:
                r["cpu4_latency_ms"] = e["mean"]["latency_ms_mean"]
                r["cpu4_peak_rss_mb"] = e["mean"]["peak_rss_inference_mb"]
                r["model_memory_delta_mb"] = e["mean"]["model_memory_delta_mb"]
            a = _load(arch_dir / "gradcam" / "atensi_mata.json")
            if a:
                r["gradcam_atensi_di_mata"] = a["atensi_di_mata_mean"]
                r["gradcam_rasio_fokus"] = a["rasio_fokus"]
            rows.append(r)
    return pd.DataFrame(rows)


def mcnemar(cfg: dict, dataset: str = "mfed_test") -> pd.DataFrame:
    """Uji McNemar exact (binomial) per seed antar pasangan arsitektur."""
    base = Path(cfg["paths"]["outputs_dir"]) / cfg["run_name"]
    rows = []
    for seed_dir in sorted(base.glob("seed*")):
        preds = {}
        for p in seed_dir.glob(f"*/pred_{dataset}.csv"):
            d = pd.read_csv(p).sort_values("path")
            preds[p.parent.name] = (d["pred"] == d["label"]).to_numpy()
        for a, b in itertools.combinations(sorted(preds), 2):
            ca, cb = preds[a], preds[b]
            n01, n10 = int((ca & ~cb).sum()), int((~ca & cb).sum())
            pval = binomtest(n01, n01 + n10, 0.5).pvalue if n01 + n10 else 1.0
            rows.append({"seed": seed_dir.name, "dataset": dataset, "A": a, "B": b,
                         "A_benar_B_salah": n01, "A_salah_B_benar": n10, "p_value": pval})
    return pd.DataFrame(rows)


def summarize(cfg: dict) -> Path:
    base = Path(cfg["paths"]["outputs_dir"]) / cfg["run_name"]
    df = collect(cfg)
    if df.empty:
        raise RuntimeError(f"Belum ada hasil di {base}")
    df.to_csv(base / "ringkasan_per_seed.csv", index=False)
    num = df.drop(columns=["seed"]).groupby("arch")
    agg = num.mean(numeric_only=True).round(4)
    if df["seed"].nunique() > 1:
        std = num.std(numeric_only=True).round(4).add_suffix("_std")
        agg = agg.join(std)
    agg.to_csv(base / "ringkasan.csv")
    tests = pd.concat([mcnemar(cfg, "mfed_test"), mcnemar(cfg, "ffe")], ignore_index=True)
    if not tests.empty:
        tests.to_csv(base / "uji_mcnemar.csv", index=False)
    with pd.option_context("display.width", 200, "display.max_columns", 50):
        print(agg.T)
        if not tests.empty:
            print(tests)
    return base / "ringkasan.csv"


