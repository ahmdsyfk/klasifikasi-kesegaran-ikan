"""Uji jembatan aplikasi Android <-> pipeline: crop dari lingkaran HP identik dengan crop_eye."""

import numpy as np
from PIL import Image

from fishfresh.data import infer_label, infer_species
from fishfresh.eye_roi import crop_eye
from fishfresh.primer import class_dir, crop_from_circle


def test_crop_from_circle_sama_dengan_crop_eye():
    rng = np.random.default_rng(0)
    im = Image.fromarray(rng.integers(0, 255, (480, 640, 3), dtype=np.uint8))
    for box in [(0, 100.0, 120.0, 180.0, 190.0), (0, 600.0, 400.0, 660.0, 470.0)]:  # kedua: keluar tepi
        _, ref = crop_eye(im, box, 0.10, 224)
        _, x0, y0, x1, y1 = box
        r = max(x1 - x0, y1 - y0) * 1.10 / 2  # yang dikirim HP: crop_r = sisi/2 (termasuk margin)
        got = crop_from_circle(im, (x0 + x1) / 2, (y0 + y1) / 2, r)
        assert np.array_equal(np.asarray(ref), np.asarray(got))


def test_folder_primer_terbaca_pipeline(tmp_path):
    p = tmp_path / "primer_1" / "eye" / class_dir({"species": "nila  merah", "label": "not_fresh"}) / "a.png"
    p.parent.mkdir(parents=True)
    p.touch()
    assert infer_label(p, tmp_path) == "not_fresh"
    assert infer_species(p, tmp_path) == "Nila Merah"
