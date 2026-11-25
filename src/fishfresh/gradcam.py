"""Visualisasi Grad-CAM (proposal 2.9) pada lapisan konvolusi terakhir tiap arsitektur."""

from __future__ import annotations

import numpy as np
import pandas as pd

from .config import run_dir


def gradcam_heatmap(model, image: np.ndarray, layer_name: str, class_idx: int | None = None):
    import keras
    import tensorflow as tf

    grad_model = keras.Model(model.inputs, [model.get_layer(layer_name).output, model.output])
    x = tf.convert_to_tensor(image[None], tf.float32)
    with tf.GradientTape() as tape:
        conv, pred = grad_model(x, training=False)
        if class_idx is None:
            class_idx = int(tf.argmax(pred[0]))
        score = pred[:, class_idx]
    grads = tape.gradient(score, conv)
    weights = tf.reduce_mean(grads, axis=(1, 2))  # global average gradien per feature map
    cam = tf.nn.relu(tf.reduce_sum(conv[0] * weights[0], axis=-1))
    cam = cam / (tf.reduce_max(cam) + 1e-8)
    return cam.numpy(), class_idx, pred[0].numpy()


def run_gradcam(arch: str, cfg: dict, seed: int, n_per_class: int = 4) -> None:
    import keras
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import tensorflow as tf

    from . import augment, models  # noqa: F401
    from .data import load_split
    from .models import ARCHS

    out = run_dir(cfg, arch, seed) / "gradcam"
    out.mkdir(exist_ok=True)
    model = keras.models.load_model(run_dir(cfg, arch, seed) / "model.keras", compile=False)
    layer = ARCHS[arch]["last_conv"]
    classes, names = cfg["classes"], cfg["class_names_id"]
    size = cfg["image_size"]

    df = load_split(cfg, seed)
    test = df[df.split == "test"]
    sample = pd.concat(
        [g.sample(min(n_per_class, len(g)), random_state=seed) for _, g in test.groupby("label")]
    )
    fig, axes = plt.subplots(len(sample), 2, figsize=(5, 2.5 * len(sample)))
    axes = np.atleast_2d(axes)
    for ax, (_, row) in zip(axes, sample.iterrows()):
        img = tf.io.decode_image(tf.io.read_file(row.path), channels=3, expand_animations=False)
        img = tf.image.resize(img, [size, size], antialias=True).numpy()
        cam, ci, prob = gradcam_heatmap(model, img, layer)
        cam = tf.image.resize(cam[..., None], [size, size]).numpy()[..., 0]
        ax[0].imshow(img.astype("uint8"))
        ax[0].set_title(f"aktual: {names[classes.index(row.label)]}", fontsize=8)
        ax[1].imshow(img.astype("uint8"))
        ax[1].imshow(cam, cmap="jet", alpha=0.45)
        ax[1].set_title(f"pred: {names[ci]} ({prob[ci]:.0%})", fontsize=8)
        for a in ax:
            a.axis("off")
    fig.suptitle(f"Grad-CAM {arch} ({layer})", fontsize=10)
    fig.tight_layout()
    fig.savefig(out / "gradcam_grid.png", dpi=150)
    plt.close(fig)
    eye_attention(model, layer, test, cfg, out)
    keras.backend.clear_session()


def _eye_region(row, cfg: dict, size: int) -> np.ndarray | None:
    """Masker area mata pada citra input model (size x size)."""
    from .eye_roi import circle_mask

    if cfg.get("roi", {}).get("mode") == "eye_mask":
        return circle_mask(size)
    need = ["box_x0", "box_y0", "box_x1", "box_y1", "width", "height"]
    if any(k not in row or pd.isna(row[k]) for k in need):
        return None
    sx, sy = size / row["width"], size / row["height"]  # resize non-proporsional ke 224x224
    cx = (row["box_x0"] + row["box_x1"]) / 2 * sx
    cy = (row["box_y0"] + row["box_y1"]) / 2 * sy
    side = max(row["box_x1"] - row["box_x0"], row["box_y1"] - row["box_y0"]) * (1 + cfg["roi"]["margin"])
    rx, ry = side / 2 * sx, side / 2 * sy
    yy, xx = np.mgrid[:size, :size]
    return ((xx + 0.5 - cx) / rx) ** 2 + ((yy + 0.5 - cy) / ry) ** 2 <= 1


def eye_attention(model, layer: str, test: pd.DataFrame, cfg: dict, out, max_n: int = 300) -> dict:
    """Fraksi energi Grad-CAM di dalam area mata (1.0 = seluruh perhatian di mata).

    Pembanding `porsi_luas_mata` = luas area mata / luas citra (nilai bila perhatian merata).
    """
    import tensorflow as tf

    from .config import save_json

    size = cfg["image_size"]
    rows = []
    sample = test.sample(min(max_n, len(test)), random_state=0)
    for _, r in sample.iterrows():
        m = _eye_region(r, cfg, size)
        if m is None:
            continue
        img = tf.io.decode_image(tf.io.read_file(r.path), channels=3, expand_animations=False)
        img = tf.image.resize(img, [size, size], antialias=True).numpy()
        cam, ci, _ = gradcam_heatmap(model, img, layer)
        cam = tf.image.resize(cam[..., None], [size, size]).numpy()[..., 0]
        tot = float(cam.sum()) + 1e-8
        rows.append({"path": r.path, "label": r.label, "pred": cfg["classes"][ci],
                     "atensi_di_mata": float(cam[m].sum()) / tot, "porsi_luas_mata": float(m.mean())})
    if not rows:
        print("[gradcam] kotak mata tidak tersedia -> atensi mata dilewati (jalankan `fishfresh roi`)")
        return {}
    d = pd.DataFrame(rows)
    d.to_csv(out / "atensi_mata.csv", index=False)
    summ = {
        "n": len(d),
        "atensi_di_mata_mean": float(d["atensi_di_mata"].mean()),
        "atensi_di_mata_median": float(d["atensi_di_mata"].median()),
        "porsi_luas_mata_mean": float(d["porsi_luas_mata"].mean()),
        "rasio_fokus": float(d["atensi_di_mata"].mean() / max(d["porsi_luas_mata"].mean(), 1e-8)),
        "per_kelas": d.groupby("label")["atensi_di_mata"].mean().to_dict(),
        "mode_roi": cfg.get("roi", {}).get("mode", "none"),
    }
    save_json(summ, out / "atensi_mata.json")
    print(f"[gradcam] atensi di mata: {summ['atensi_di_mata_mean']:.1%} "
          f"(luas mata {summ['porsi_luas_mata_mean']:.1%}, rasio fokus {summ['rasio_fokus']:.2f}x)")
    return summ
