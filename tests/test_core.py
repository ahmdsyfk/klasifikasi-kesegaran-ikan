from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from fishfresh.config import load_config
from fishfresh.data import _popcount64, infer_label, infer_species, stratified_group_split


@pytest.mark.parametrize(
    "rel,label,species",
    [
        ("Chanos Chanos - Not Fresh/a.jpg", "not_fresh", "Chanos Chanos"),
        ("Nibea Albiflora -  Highly Fresh/a.jpg", "highly_fresh", "Nibea Albiflora"),
        ("Upeneus Moluccensis - Fresh/a.jpg", "fresh", "Upeneus Moluccensis"),
        ("MFED/Nila/Highly_Fresh/a.jpg", "highly_fresh", "Nila"),
        ("MFED/tilapia/not-fresh/a.jpg", "not_fresh", "Tilapia"),
        ("lain/a.jpg", None, "unknown"),
    ],
)
def test_label_species(rel, label, species):
    root = Path("/r")
    assert infer_label(root / rel, root) == label
    assert infer_species(root / rel, root) == species


def test_popcount():
    x = np.array([0, 1, 0xFF, 2**64 - 1], dtype=np.uint64)
    assert _popcount64(x).tolist() == [0, 1, 8, 64]


def test_split_ratio_and_groups():
    cfg = load_config()
    rng = np.random.default_rng(0)
    n = 600
    df = pd.DataFrame({"label": rng.choice(cfg["classes"], n), "group": np.arange(n) // 2})
    s = stratified_group_split(df, cfg, 42)
    frac = s.value_counts(normalize=True)
    assert abs(frac["train"] - 0.70) < 0.03 and abs(frac["test"] - 0.15) < 0.03
    # satu grup tidak boleh terpecah ke beberapa split
    assert (df.assign(s=s).groupby("group")["s"].nunique() == 1).all()


@pytest.mark.parametrize("arch,app", [("vgg16", "vgg16"), ("resnet50", "resnet50"), ("inceptionv3", "inception_v3")])
def test_preprocess_matches_keras(arch, app):
    import keras

    from fishfresh.models import ARCHS, ImagenetPreprocess

    x = np.random.default_rng(1).uniform(0, 255, (2, 8, 8, 3)).astype("float32")
    ref = getattr(keras.applications, app).preprocess_input(x.copy())
    out = keras.ops.convert_to_numpy(ImagenetPreprocess(ARCHS[arch]["mode"])(x))
    np.testing.assert_allclose(out, ref, atol=1e-4)
