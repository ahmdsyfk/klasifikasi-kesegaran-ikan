"""Model transfer learning VGG16 / ResNet50 / InceptionV3 (proposal 2.5, 2.6, 3.6).

Model menerima citra RGB float32 [0,255] ukuran 224x224. Normalisasi `preprocess_input`
dibuat sebagai lapisan di dalam model sehingga berkas .keras siap dipakai langsung di perangkat:
- VGG16 & ResNet50 ("caffe"): RGB->BGR lalu dikurangi rata-rata ImageNet.
- InceptionV3 ("tf"): skala ke [-1, 1].
Backbone dibangun "flat" (input_tensor) agar lapisan konvolusi terakhir bisa diakses Grad-CAM.
"""

from __future__ import annotations

import keras
from keras import layers, ops

ARCHS = {
    "vgg16": {
        "fn": "VGG16",
        "mode": "caffe",
        "last_conv": "block5_conv3",
    },
    "resnet50": {
        "fn": "ResNet50",
        "mode": "caffe",
        "last_conv": "conv5_block3_out",
    },
    "inceptionv3": {
        "fn": "InceptionV3",
        "mode": "tf",
        "last_conv": "mixed10",
    },
}

_IMAGENET_BGR_MEAN = (103.939, 116.779, 123.68)


@keras.saving.register_keras_serializable(package="fishfresh")
class ImagenetPreprocess(layers.Layer):
    """Setara keras.applications.<arch>.preprocess_input, tetapi dapat diserialisasi."""

    def __init__(self, mode: str, **kw):
        super().__init__(**kw)
        if mode not in ("caffe", "tf"):
            raise ValueError(mode)
        self.mode = mode

    def call(self, x):
        if self.mode == "tf":
            return x / 127.5 - 1.0
        x = ops.flip(x, axis=-1)  # RGB -> BGR
        return x - ops.convert_to_tensor(_IMAGENET_BGR_MEAN, dtype=x.dtype)

    def get_config(self):
        return {**super().get_config(), "mode": self.mode}


def build_model(arch: str, cfg: dict) -> tuple[keras.Model, list[layers.Layer]]:
    """Kembalikan (model, daftar_lapisan_backbone)."""
    if arch not in ARCHS:
        raise ValueError(f"Arsitektur tidak dikenal: {arch}. Pilih dari {list(ARCHS)}")
    spec = ARCHS[arch]
    size = cfg["image_size"]
    n_cls = len(cfg["classes"])
    mcfg = cfg["model"]

    inputs = keras.Input((size, size, 3), name="image_rgb_0_255")
    x = ImagenetPreprocess(spec["mode"], name="preprocess")(inputs)
    app = getattr(keras.applications, spec["fn"])
    base = app(include_top=False, weights=mcfg["weights"], input_tensor=x)
    backbone_layers = [l for l in base.layers if not isinstance(l, layers.InputLayer)]

    y = layers.GlobalAveragePooling2D(name="gap")(base.output)
    y = layers.Dropout(mcfg["dropout"], name="dropout_1")(y)
    if mcfg.get("head_dense_units"):
        y = layers.Dense(mcfg["head_dense_units"], activation="relu", name="fc")(y)
        y = layers.Dropout(mcfg["dropout"], name="dropout_2")(y)
    # softmax float32 agar aman saat mixed precision
    outputs = layers.Dense(n_cls, activation="softmax", dtype="float32", name="pred")(y)
    model = keras.Model(inputs, outputs, name=f"{arch}_fishfresh")
    return model, backbone_layers


def set_backbone_trainable(backbone_layers: list[layers.Layer], unfreeze_ratio: float) -> int:
    """Bekukan backbone; buka `unfreeze_ratio` lapisan teratas. BatchNorm tetap beku."""
    n = len(backbone_layers)
    start = n - round(n * unfreeze_ratio)
    n_open = 0
    for i, l in enumerate(backbone_layers):
        trainable = i >= start and not isinstance(l, layers.BatchNormalization)
        l.trainable = trainable
        n_open += int(trainable and bool(l.weights))
    return n_open


def count_params(model: keras.Model) -> dict:
    trainable = sum(int(ops.size(w)) for w in model.trainable_weights)
    non_trainable = sum(int(ops.size(w)) for w in model.non_trainable_weights)
    return {
        "total": int(model.count_params()),
        "trainable": trainable,
        "non_trainable": non_trainable,
    }
