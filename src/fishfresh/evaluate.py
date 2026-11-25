"""Evaluasi kinerja (3.7.1 MFED) dan lintas dataset (3.7.2 FFE)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    precision_recall_fscore_support,
)

from .config import run_dir, save_json


def compute_metrics(y_true: np.ndarray, y_pred: np.ndarray, classes: list[str]) -> dict:
    labels = list(range(len(classes)))
    p, r, f, _ = precision_recall_fscore_support(
        y_true, y_pred, labels=labels, average="macro", zero_division=0
    )
    pw, rw, fw, _ = precision_recall_fscore_support(
        y_true, y_pred, labels=labels, average="weighted", zero_division=0
    )
    return {
        "n": len(y_true),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision_macro": float(p),
        "recall_macro": float(r),
        "f1_macro": float(f),
        "precision_weighted": float(pw),
        "recall_weighted": float(rw),
        "f1_weighted": float(fw),
        "per_class": classification_report(
            y_true, y_pred, labels=labels, target_names=classes, output_dict=True, zero_division=0
        ),
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=labels).tolist(),
    }


def plot_confusion(cm: np.ndarray, names: list[str], path: Path, title: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    cm = np.asarray(cm)
    norm = cm / np.maximum(cm.sum(1, keepdims=True), 1)
    fig, ax = plt.subplots(figsize=(5, 4.3))
    im = ax.imshow(norm, cmap="Blues", vmin=0, vmax=1)
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            ax.text(j, i, f"{cm[i, j]}\n{norm[i, j]:.0%}", ha="center", va="center",
                    color="white" if norm[i, j] > 0.5 else "black", fontsize=9)
    ax.set_xticks(range(len(names)), names, rotation=20)
    ax.set_yticks(range(len(names)), names)
    ax.set_xlabel("Prediksi")
    ax.set_ylabel("Aktual")
    ax.set_title(title)
    fig.colorbar(im, ax=ax, fraction=0.046)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def predict(model, df: pd.DataFrame, cfg: dict, seed: int) -> np.ndarray:
    from .data import make_dataset

    ds = make_dataset(df, cfg, training=False, seed=seed, batch_size=64)
    return model.predict(ds, verbose=0)


def evaluate_arch(arch: str, cfg: dict, seed: int, exclude_ffe_overlap: bool = False) -> dict:
    import keras

    from . import augment, models  # noqa: F401 - registrasi custom layer
    from .data import class_index, load_ffe, load_split

    out = run_dir(cfg, arch, seed)
    model = keras.models.load_model(out / "model.keras")
    classes, names = cfg["classes"], cfg["class_names_id"]
    cidx = class_index(cfg)
    results = {}

    df = load_split(cfg, seed)
    sets = {"mfed_test": df[df.split == "test"].reset_index(drop=True)}
    try:
        sets["ffe"] = load_ffe(cfg, exclude_overlap=exclude_ffe_overlap).reset_index(drop=True)
    except FileNotFoundError as e:
        print(f"[lewati FFE] {e}")

    for name, d in sets.items():
        prob = predict(model, d, cfg, seed)
        y = d["label"].map(cidx).to_numpy()
        yp = prob.argmax(1)
        m = compute_metrics(y, yp, classes)
        pred = d[["path", "label", "species"]].copy()
        pred["pred"] = [classes[i] for i in yp]
        for i, c in enumerate(classes):
            pred[f"p_{c}"] = prob[:, i]
        pred.to_csv(out / f"pred_{name}.csv", index=False)
        if name == "ffe":
            m["per_species"] = {
                sp: compute_metrics(y[g.index], yp[g.index], classes) | {"per_class": None}
                for sp, g in d.groupby("species")
            }
        plot_confusion(np.array(m["confusion_matrix"]), names, out / f"cm_{name}.png",
                       f"{arch} - {name}")
        save_json(m, out / f"metrics_{name}.json")
        results[name] = m

    if "ffe" in results:
        f_m, f_f = results["mfed_test"]["f1_macro"], results["ffe"]["f1_macro"]
        results["generalization"] = {
            "f1_mfed": f_m,
            "f1_ffe": f_f,
            "delta_f1": f_m - f_f,  # penurunan absolut (3.7.2)
            "relative_drop_pct": 100 * (f_m - f_f) / f_m if f_m else None,
        }
        save_json(results["generalization"], out / "generalization.json")
    keras.backend.clear_session()
    return results
