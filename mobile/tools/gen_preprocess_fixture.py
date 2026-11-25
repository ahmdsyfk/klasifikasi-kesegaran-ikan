"""Buat fixture uji paritas praproses Kotlin vs Python (Pillow LANCZOS + tf bilinear antialias).

  uv run python mobile/tools/gen_preprocess_fixture.py
"""
from pathlib import Path

import numpy as np
from PIL import Image

from fishfresh.eye_roi import crop_eye

OUT = Path("mobile/shared/src/commonTest/kotlin/id/fishfresh/PreprocessFixture.kt")
rng = np.random.default_rng(7)
W, H = 41, 31
arr = rng.integers(0, 256, (H, W, 3), dtype=np.uint8)
im = Image.fromarray(arr)
packed = (arr[..., 0].astype(np.int64) << 16) | (arr[..., 1].astype(np.int64) << 8) | arr[..., 2]

cases = []
for cx, cy, r, n in [(20.0, 15.0, 9.35, 24), (3.5, 27.0, 11.0, 24), (30.25, 10.5, 4.4, 17), (20.5, 15.5, 30.0, 32)]:
    _, masked = crop_eye(im, (None, cx - r, cy - r, cx + r, cy + r), 0.0, n)
    m = np.asarray(masked).astype(np.int64)
    cases.append((cx, cy, r, n, ((m[..., 0] << 16) | (m[..., 1] << 8) | m[..., 2]).ravel().tolist()))

import tensorflow as tf  # noqa: E402
tf_out = tf.image.resize(arr[None].astype(np.float32), [13, 17], method="bilinear", antialias=True)[0].numpy()

def kl(xs):
    return ", ".join(str(int(x)) for x in xs)

lines = ["// DIBUAT OTOMATIS oleh mobile/tools/gen_preprocess_fixture.py — jangan diedit manual.",
         "package id.fishfresh", "", "object PreprocessFixture {",
         f"    const val W = {W}", f"    const val H = {H}",
         f"    val src = intArrayOf({kl(packed.ravel())})",
         "    class Case(val cx: Float, val cy: Float, val r: Float, val n: Int, val expected: IntArray)",
         "    val cases = listOf("]
for cx, cy, r, n, exp in cases:
    lines.append(f"        Case({cx}f, {cy}f, {r}f, {n}, intArrayOf({kl(exp)})),")
lines += ["    )", "    const val TF_W = 17", "    const val TF_H = 13",
          "    val tfBilinear = floatArrayOf(" + ", ".join(f"{v:.6f}f" for v in tf_out.ravel()) + ")", "}", ""]
OUT.write_text("\n".join(lines))
print("ditulis", OUT)
