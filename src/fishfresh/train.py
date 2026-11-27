"""Pelatihan dua fase (feature extraction -> fine-tuning) dengan pencatatan waktu pelatihan."""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np
import pandas as pd

from .config import run_dir, save_json


class _EpochTimer:
    """Callback pencatat waktu: sejak epoch pertama dimulai hingga pelatihan berhenti (3.7.3)."""

    def __new__(cls):
        import keras

        class T(keras.callbacks.Callback):
            def __init__(self):
                super().__init__()
                self.t0 = None
                self.epoch_times: list[float] = []
                self._e = 0.0

            def on_epoch_begin(self, epoch, logs=None):
                self._e = time.perf_counter()
                if self.t0 is None:
                    self.t0 = self._e

            def on_epoch_end(self, epoch, logs=None):
                self.epoch_times.append(time.perf_counter() - self._e)

        return T()


def _setup(cfg: dict, seed: int) -> dict:
    """Seed, determinisme, dan deteksi platform (macOS/Windows/Linux/WSL2 + CPU/CUDA/Metal)."""
    import keras
    import tensorflow as tf

    from .platform_info import configure, describe

    keras.utils.set_random_seed(seed)
    if cfg["train"].get("deterministic"):
        tf.config.experimental.enable_op_determinism()
    info = configure(cfg)
    print(describe(info), flush=True)
    return info


def _class_weights(labels: np.ndarray, n_cls: int) -> dict[int, float]:
    counts = np.bincount(labels, minlength=n_cls)
    w = len(labels) / (n_cls * np.maximum(counts, 1))
    return {i: float(w[i]) for i in range(n_cls)}


def _compile(model, lr: float, cfg: dict):
    import keras

    name = cfg["train"]["optimizer"]
    kw = {}
    if name == "adamw" and cfg["train"].get("weight_decay") is not None:
        kw["weight_decay"] = float(cfg["train"]["weight_decay"])
    opt = {"adam": keras.optimizers.Adam, "sgd": keras.optimizers.SGD, "adamw": keras.optimizers.AdamW}[name](
        learning_rate=lr, **kw
    )
    ls = cfg["train"].get("label_smoothing", 0.0)
    loss = (
        keras.losses.CategoricalCrossentropy(label_smoothing=ls)
        if ls
        else keras.losses.SparseCategoricalCrossentropy()
    )
    model.compile(optimizer=opt, loss=loss, metrics=["accuracy"])
    return ls > 0


def train_arch(arch: str, cfg: dict, seed: int) -> dict:
    import keras
    import tensorflow as tf

    from .data import class_index, load_split, make_dataset
    from .models import build_model, count_params, set_backbone_trainable

    platform_info = _setup(cfg, seed)
    out = run_dir(cfg, arch, seed)
    tcfg = cfg["train"]
    df = load_split(cfg, seed)
    tr, va = df[df.split == "train"], df[df.split == "val"]
    n_cls = len(cfg["classes"])

    ds_tr = make_dataset(tr, cfg, training=True, seed=seed)
    ds_va = make_dataset(va, cfg, training=False, seed=seed)
    _augmentation_notes(tr, cfg, seed, out)

    model, backbone = build_model(arch, cfg)
    set_backbone_trainable(backbone, 0.0)
    onehot = _compile(model, tcfg["phase1_lr"], cfg)
    if onehot:
        ds_tr = ds_tr.map(lambda x, y: (x, tf.one_hot(y, n_cls)))
        ds_va = ds_va.map(lambda x, y: (x, tf.one_hot(y, n_cls)))

    cw = None
    if tcfg.get("class_weight"):
        cw = _class_weights(tr["label"].map(class_index(cfg)).to_numpy(), n_cls)

    best_path = out / "model.keras"
    timer = _EpochTimer()

    def callbacks(phase: str, best_so_far: float | None = None):
        return [
            timer,
            keras.callbacks.ModelCheckpoint(
                best_path, monitor=tcfg["monitor"], save_best_only=True, mode="auto",
                initial_value_threshold=best_so_far,
            ),
            keras.callbacks.EarlyStopping(
                monitor=tcfg["monitor"],
                patience=tcfg["early_stopping_patience"],
                restore_best_weights=True,
            ),
            keras.callbacks.ReduceLROnPlateau(
                monitor=tcfg["monitor"], factor=0.2, patience=tcfg["reduce_lr_patience"], min_lr=1e-7
            ),
            keras.callbacks.CSVLogger(out / f"history_{phase}.csv"),
        ]

    params_fe = count_params(model)
    h1 = model.fit(
        ds_tr, validation_data=ds_va, epochs=tcfg["phase1_epochs"], class_weight=cw,
        callbacks=callbacks("phase1"), verbose=2,
    )
    epochs1 = len(h1.history["loss"])
    epochs2 = 0
    n_open = 0
    if tcfg.get("fine_tune") and tcfg["phase2_epochs"] > 0:
        n_open = set_backbone_trainable(backbone, tcfg["unfreeze_ratio"])
        _compile(model, tcfg["phase2_lr"], cfg)
        # Checkpoint fase 2 hanya menimpa bila lebih baik dari terbaik fase 1
        best1 = h1.history[tcfg["monitor"]]
        best1 = float(np.min(best1) if "loss" in tcfg["monitor"] else np.max(best1))
        cbs = callbacks("phase2", best1)
        h2 = model.fit(
            ds_tr, validation_data=ds_va, epochs=tcfg["phase2_epochs"], class_weight=cw,
            callbacks=cbs, verbose=2,
        )
        epochs2 = len(h2.history["loss"])
    train_time = time.perf_counter() - timer.t0

    if not best_path.exists():
        model.save(best_path)
    # Salinan khusus inferensi (tanpa state optimizer) -> dipakai untuk ukuran berkas & benchmark
    from . import augment, models  # noqa: F401 - registrasi custom layer

    infer_path = out / "model_inference.keras"
    keras.models.load_model(best_path, compile=False).save(infer_path)
    meta = {
        "arch": arch,
        "seed": seed,
        "n_train": len(tr),
        "n_val": len(va),
        "epochs_phase1": epochs1,
        "epochs_phase2": epochs2,
        "unfrozen_layers_with_weights": n_open,
        "train_time_s": train_time,
        "mean_epoch_time_s": float(np.mean(timer.epoch_times)),
        "params_feature_extraction": params_fe,
        "params_final": count_params(model),
        "model_file_mb": infer_path.stat().st_size / 2**20,
        "checkpoint_file_mb": best_path.stat().st_size / 2**20,
        "devices": [d.name for d in tf.config.list_physical_devices()],
        "platform": platform_info,
        "early_stopping": {
            "monitor": tcfg["monitor"],
            "patience": tcfg["early_stopping_patience"],
            "stopped_early_phase1": epochs1 < tcfg["phase1_epochs"],
            "stopped_early_phase2": bool(epochs2) and epochs2 < tcfg["phase2_epochs"],
        },
        "config": cfg,
    }
    save_json(meta, out / "train_meta.json")
    _merge_history(out)
    keras.backend.clear_session()
    return meta


def _augmentation_notes(tr: pd.DataFrame, cfg: dict, seed: int, out: Path) -> None:
    """Catatan proses augmentasi per run: augmentasi.json, augmentasi.md, contoh_augmentasi.png."""
    from .augment import augmentation_notes, notes_markdown, save_augmentation_samples
    from .data import make_dataset

    notes = augmentation_notes(cfg, seed, len(tr))
    notes["distribusi_kelas_latih"] = tr["label"].value_counts().to_dict()
    save_json(notes, out / "augmentasi.json")
    (out / "augmentasi.md").write_text(notes_markdown(notes))
    if cfg["augment"]["enabled"]:
        sample = pd.concat([g.sample(min(2, len(g)), random_state=seed) for _, g in tr.groupby("label")])
        ds = make_dataset(sample, cfg, training=False, seed=seed, batch_size=len(sample))
        save_augmentation_samples(ds, cfg, seed, out / "contoh_augmentasi.png", n_img=len(sample))


def _merge_history(out: Path) -> None:
    parts = []
    for i, ph in enumerate(["phase1", "phase2"], 1):
        p = out / f"history_{ph}.csv"
        if p.exists() and p.stat().st_size:
            d = pd.read_csv(p)
            d["phase"] = i
            parts.append(d)
    if parts:
        h = pd.concat(parts, ignore_index=True)
        h["global_epoch"] = np.arange(1, len(h) + 1)
        h.to_csv(out / "history.csv", index=False)
        _plot_history(h, out / "kurva_pelatihan.png")


def _plot_history(h: pd.DataFrame, path: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(1, 2, figsize=(11, 4))
    for k, a in zip(["loss", "accuracy"], ax):
        a.plot(h["global_epoch"], h[k], label="latih")
        a.plot(h["global_epoch"], h[f"val_{k}"], label="validasi")
        if (h["phase"] == 2).any():
            a.axvline(h.loc[h.phase == 2, "global_epoch"].min() - 0.5, ls="--", c="gray", label="mulai fine-tune")
        a.set_xlabel("epoch")
        a.set_title(k)
        a.legend()
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
