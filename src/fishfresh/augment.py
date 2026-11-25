"""Augmentasi data latih (proposal 2.7, Gambar 4): rotasi, gamma, noise, perspektif (+flip, brightness).

Bekerja pada batch float32 rentang [0, 255]; hanya dipakai pada data latih.
"""

from __future__ import annotations

import keras
from keras import layers, ops


@keras.saving.register_keras_serializable(package="fishfresh")
class RandomGamma(layers.Layer):
    def __init__(self, gamma_range=(0.8, 1.2), seed=None, **kw):
        super().__init__(**kw)
        self.gamma_range = tuple(gamma_range)
        self.seed = seed
        self._gen = keras.random.SeedGenerator(seed)

    def call(self, x, training=None):
        if not training:
            return x
        b = ops.shape(x)[0]
        g = keras.random.uniform((b, 1, 1, 1), *self.gamma_range, seed=self._gen)
        return 255.0 * ops.power(ops.clip(x / 255.0, 0.0, 1.0), g)

    def get_config(self):
        return {**super().get_config(), "gamma_range": self.gamma_range, "seed": self.seed}


@keras.saving.register_keras_serializable(package="fishfresh")
class RandomGaussianNoise255(layers.Layer):
    """Noise Gaussian dengan std relatif terhadap rentang 0-1, lalu di-clip ke [0,255]."""

    def __init__(self, std=0.03, seed=None, **kw):
        super().__init__(**kw)
        self.std = std
        self.seed = seed
        self._gen = keras.random.SeedGenerator(seed)

    def call(self, x, training=None):
        if not training or self.std <= 0:
            return x
        n = keras.random.normal(ops.shape(x), stddev=self.std * 255.0, seed=self._gen)
        return ops.clip(x + n, 0.0, 255.0)

    def get_config(self):
        return {**super().get_config(), "std": self.std, "seed": self.seed}


@keras.saving.register_keras_serializable(package="fishfresh")
class CircularMask(layers.Layer):
    """Menghitamkan area di luar lingkaran yang menyinggung tepi citra (mode roi=eye_mask).

    Dipasang di akhir augmentasi agar noise/brightness/rotasi tidak "menghidupkan" lagi
    area di luar mata; geometri lingkaran berpusat sehingga tetap sejajar dengan mata.
    """

    def call(self, x):
        size = ops.shape(x)[1]
        r = ops.cast(size, "float32") / 2.0
        c = (ops.cast(size, "float32") - 1.0) / 2.0
        idx = ops.cast(ops.arange(size), "float32")
        yy, xx = ops.meshgrid(idx, idx, indexing="ij")
        m = ops.cast(((xx - c) ** 2 + (yy - c) ** 2) <= r**2, x.dtype)
        return x * m[None, :, :, None]


def build_augmenter(cfg: dict, seed: int) -> keras.Sequential:
    a = cfg["augment"]
    seq: list[layers.Layer] = []
    if a.get("horizontal_flip"):
        seq.append(layers.RandomFlip("horizontal", seed=seed))
    if a.get("rotation_deg"):
        seq.append(layers.RandomRotation(a["rotation_deg"] / 360.0, fill_mode="reflect", seed=seed + 1))
    if a.get("perspective"):
        seq.append(layers.RandomPerspective(factor=1.0, scale=a["perspective"], seed=seed + 2))
    if a.get("brightness"):
        seq.append(layers.RandomBrightness(a["brightness"], value_range=(0, 255), seed=seed + 3))
    if a.get("gamma_range"):
        seq.append(RandomGamma(a["gamma_range"], seed=seed + 4))
    if a.get("noise_std"):
        seq.append(RandomGaussianNoise255(a["noise_std"], seed=seed + 5))
    if cfg.get("roi", {}).get("mode") == "eye_mask":
        seq.append(CircularMask(name="masker_mata"))
    return keras.Sequential(seq, name="augmentasi")


_DESKRIPSI = {
    "random_flip": "Flip horizontal acak (mata kiri/kanan ikan setara secara visual).",
    "random_rotation": "Rotasi acak; tepi diisi pantulan (reflect). Meniru kemiringan kamera/ikan.",
    "random_perspective": "Transformasi perspektif acak; meniru sudut pengambilan foto berbeda.",
    "random_brightness": "Perubahan kecerahan acak; meniru variasi pencahayaan.",
    "random_gamma": "Koreksi gamma acak; meniru perbedaan respons kamera/eksposur.",
    "random_gaussian_noise255": "Noise Gaussian; meniru noise sensor kamera HP.",
    "masker_mata": "Masker lingkaran ulang setelah augmentasi agar area di luar mata tetap hitam.",
}


def augmentation_notes(cfg: dict, seed: int, n_train: int) -> dict:
    """Catatan augmentasi yang disimpan per run (augmentasi.json + .md)."""
    a = cfg["augment"]
    aug = build_augmenter(cfg, seed) if a["enabled"] else None
    steps = []
    for i, l in enumerate(aug.layers if aug else [], 1):
        c = l.get_config()
        params = {k: v for k, v in c.items() if k not in ("name", "trainable", "dtype", "seed",
                                                           "data_format", "interpolation")}
        steps.append({"urutan": i, "lapisan": type(l).__name__, "nama": l.name, "parameter": params,
                      "tujuan": _DESKRIPSI.get(_key(type(l).__name__), "")})
    return {
        "aktif": bool(a["enabled"]),
        "mode_roi": cfg.get("roi", {}).get("mode", "none"),
        "diterapkan_pada": "hanya data latih (validasi & uji tanpa augmentasi acak; proposal 2.7)",
        "cara": ("online/on-the-fly: setiap epoch tiap citra latih mendapat transformasi acak baru; "
                 "jumlah berkas citra tidak bertambah, variasi bertambah per epoch"),
        "jumlah_citra_latih": n_train,
        "urutan_pipeline": "baca -> RGB -> resize -> cache -> batch -> augmentasi -> model(preprocess_input)",
        "rentang_nilai": "float32 [0,255]; normalisasi per arsitektur dilakukan di dalam model",
        "seed": seed,
        "konfigurasi": a,
        "langkah": steps,
    }


def _key(cls_name: str) -> str:
    import re

    k = re.sub(r"(?<!^)(?=[A-Z])", "_", cls_name).lower()
    return "masker_mata" if k == "circular_mask" else k


def notes_markdown(n: dict) -> str:
    lines = [
        "# Catatan Augmentasi Data", "",
        f"- Aktif: **{n['aktif']}** | mode ROI: **{n['mode_roi']}** | seed: {n['seed']}",
        f"- Diterapkan pada: {n['diterapkan_pada']}",
        f"- Cara: {n['cara']}",
        f"- Jumlah citra latih: {n['jumlah_citra_latih']}",
        f"- Urutan pipeline: `{n['urutan_pipeline']}`",
        f"- Rentang nilai: {n['rentang_nilai']}", "",
        "| # | Lapisan | Parameter | Tujuan |", "|---|---|---|---|",
    ]
    for s in n["langkah"]:
        lines.append(f"| {s['urutan']} | {s['lapisan']} | `{s['parameter']}` | {s['tujuan']} |")
    lines += ["", "Contoh visual: `contoh_augmentasi.png` (kolom 1 = asli, kolom lain = hasil acak)."]
    return "\n".join(lines) + "\n"


def save_augmentation_samples(ds_plain, cfg: dict, seed: int, path, n_img: int = 4, n_aug: int = 5):
    """Simpan grid: citra asli + n_aug variasi augmentasi acak (untuk catatan & laporan)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    aug = build_augmenter(cfg, seed)
    x, y = next(iter(ds_plain.take(1)))
    x = x[:n_img]
    names = cfg["class_names_id"]
    fig, axes = plt.subplots(len(x), n_aug + 1, figsize=(2 * (n_aug + 1), 2 * len(x)))
    axes = np.atleast_2d(axes)
    for i in range(len(x)):
        axes[i, 0].imshow(np.clip(np.asarray(x[i]), 0, 255).astype("uint8"))
        axes[i, 0].set_title(f"asli ({names[int(y[i])]})", fontsize=7)
    for j in range(1, n_aug + 1):
        xa = np.asarray(aug(x, training=True))
        for i in range(len(x)):
            axes[i, j].imshow(np.clip(xa[i], 0, 255).astype("uint8"))
            axes[i, j].set_title(f"augmentasi {j}", fontsize=7)
    for a in axes.ravel():
        a.axis("off")
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)
