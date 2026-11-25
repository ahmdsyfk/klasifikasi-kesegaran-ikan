"""Ekspor model terlatih ke LiteRT/TFLite untuk aplikasi Android (mobile/), per SKENARIO.

Kontrak I/O model (sama dengan models.py):
- input  : float32 [1, S, S, 3], RGB rentang 0-255 (preprocess ada DI DALAM model); S = image_size
- output : float32 [1, 3] softmax, urutan = cfg["classes"]
- input_variant "eye_crop" (run dengan roi.mode=eye_mask) -> HP memotong & memasker mata dulu;
  "full" -> citra utuh di-resize.

ID model = <run_name>-<arch>-s<seed>, mis. eye_mask-resnet50-s42. Untuk setiap model ditulis
<id>.tflite + <id>.json ke --out (default mobile/build/models, tidak di-commit). Metrik (F1 MFED,
F1 FFE, ΔF1, latensi) diambil dari outputs/<run_name>/seed<seed>/<arch>/.

Contoh:
  uv run python -m fishfresh.export_mobile                                   # baseline, 3 arsitektur
  uv run python -m fishfresh.export_mobile -c configs/eye_mask.yaml --publish --default resnet50
  uv run python -m fishfresh.export_mobile -c configs/eye_mask.yaml --bundle resnet50  # model bawaan APK
  uv run python -m fishfresh.export_mobile --demo                            # model dummy (debug)

--publish menerbitkan model ke GitHub Releases repo publik (default ahmdsyfk/klasifikasi-kesegaran-ikan,
ubah lewat --repo atau IKANSEGAR_MODELS_REPO) dan memperbarui registry.json di rilis `registry`.
Aplikasi membaca registry itu dan mengunduh model tanpa akun. Butuh `gh` dengan akses tulis ke repo.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import time
from datetime import UTC, datetime
from pathlib import Path

import numpy as np

DEFAULT_OUT = Path("mobile/build/models")
BUNDLE_OUT = Path("mobile/androidApp/src/main/assets/models")
DEMO_OUT = Path("mobile/androidApp/src/main/assets/models")
ARCH_NAMES = {"vgg16": "VGG16", "resnet50": "ResNet50", "inceptionv3": "InceptionV3"}
ARCH_ORDER = {"vgg16": 0, "resnet50": 1, "inceptionv3": 2}
SCENARIO_ORDER = ["eye_mask", "baseline", "k2_feature_extraction", "k3_inception299",
                  "k4_tanpa_augmentasi", "k5_class_weight", "k6_finetune_dalam", "k8_mixed_precision"]
# Label pendek untuk UI; deskripsi diambil dari baris komentar pertama berkas config.
SCENARIO_NAMES = {
    "baseline": "Baseline (citra utuh)",
    "eye_mask": "Crop mata",
    "k2_feature_extraction": "K2 · Tanpa fine-tuning",
    "k3_inception299": "K3 · Inception 299 px",
    "k4_tanpa_augmentasi": "K4 · Tanpa augmentasi",
    "k5_class_weight": "K5 · Class weight",
    "k6_finetune_dalam": "K6 · Fine-tuning 60%",
    "k8_mixed_precision": "K8 · Mixed precision",
}


def _convert(model, quant: str) -> bytes:
    import tensorflow as tf

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    if quant == "float16":
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.target_spec.supported_types = [tf.float16]
    elif quant == "dynamic":
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
    elif quant != "none":
        raise ValueError(f"quant tidak dikenal: {quant}")
    return conv.convert()


def _parity(model, tflite_bytes: bytes, size: int, n: int = 8, seed: int = 0) -> dict:
    """Bandingkan keluaran Keras vs TFLite pada input acak 0-255 (cek konversi tidak merusak)."""
    import tensorflow as tf

    rng = np.random.default_rng(seed)
    x = rng.uniform(0, 255, (n, size, size, 3)).astype(np.float32)
    ref = model.predict(x, verbose=0)
    itp = tf.lite.Interpreter(model_content=tflite_bytes, num_threads=4)
    itp.allocate_tensors()
    i_det, o_det = itp.get_input_details()[0], itp.get_output_details()[0]
    out, times = [], []
    for k in range(n):
        itp.set_tensor(i_det["index"], x[k : k + 1])
        t0 = time.perf_counter()
        itp.invoke()
        times.append((time.perf_counter() - t0) * 1000)
        out.append(itp.get_tensor(o_det["index"])[0])
    out = np.stack(out)
    return {
        "n_random_inputs": n,
        "max_abs_diff": float(np.abs(out - ref).max()),
        "top1_agreement": float((out.argmax(1) == ref.argmax(1)).mean()),
        "desktop_tflite_ms_median": float(np.median(times)),
    }


def _read_json(p: Path) -> dict:
    return json.loads(p.read_text()) if p.exists() else {}


def collect_metrics(arch_dir: Path) -> dict:
    """Ringkas metrik penelitian yang ditampilkan di pemilih model aplikasi."""
    m = _read_json(arch_dir / "metrics_mfed_test.json")
    g = _read_json(arch_dir / "generalization.json")
    e = _read_json(arch_dir / "efficiency.json").get("mean", {})
    t = _read_json(arch_dir / "train_meta.json")
    out = {
        "acc_mfed": m.get("accuracy"),
        "f1_mfed": m.get("f1_macro", g.get("f1_mfed")),
        "f1_ffe": g.get("f1_ffe"),
        "delta_f1": g.get("delta_f1"),
        "latency_ms_cpu4": e.get("latency_ms_mean"),
        "params": (t.get("params_final") or {}).get("total"),
        "train_time_s": t.get("train_time_s"),
    }
    return {k: v for k, v in out.items() if v is not None}


def scenario_info(cfg: dict, config_path: str) -> tuple[str, str, str]:
    run = cfg["run_name"]
    desc = ""
    p = Path(config_path)
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.startswith("#"):
                desc = line.lstrip("# ").strip()
                break
    fixed = {
        "baseline": "Sesuai proposal: citra utuh di-resize 224 px.",
        "eye_mask": "Hanya area mata: crop persegi berpusat di mata + masker lingkaran, 224 px.",
    }
    desc = fixed.get(run, desc)
    return run, SCENARIO_NAMES.get(run, run), desc


def input_variant(cfg: dict) -> str:
    return "eye_crop" if (cfg.get("roi") or {}).get("mode") == "eye_mask" else "full"


def _write(out_dir: Path, model_id: str, tflite: bytes, meta: dict) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{model_id}.tflite"
    path.write_bytes(tflite)
    meta["file_mb"] = round(len(tflite) / 2**20, 2)
    meta["file_sha256"] = hashlib.sha256(tflite).hexdigest()
    (out_dir / f"{model_id}.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(f"  -> {path} ({meta['file_mb']} MB)")
    return path


def export_trained(cfg: dict, config_path: str, archs: list[str], seed: int, quant: str,
                   out_dir: Path) -> list[tuple[Path, dict]]:
    import keras

    from . import models as _models  # noqa: F401 - registrasi ImagenetPreprocess
    from .config import run_dir

    scenario, scen_name, scen_desc = scenario_info(cfg, config_path)
    done = []
    for arch in archs:
        arch_dir = run_dir(cfg, arch, seed)
        src = arch_dir / "model_inference.keras"
        if not src.exists():
            print(f"[lewati] {arch}: {src} belum ada (latih dulu)")
            continue
        model_id = f"{scenario}-{arch}-s{seed}"
        print(f"[{model_id}] memuat {src}")
        model = keras.models.load_model(src, compile=False)
        tfl = _convert(model, quant)
        meta = {
            "id": model_id,
            "name": ARCH_NAMES.get(arch, arch),
            "arch": arch,
            "scenario": scenario,
            "scenario_name": scen_name,
            "scenario_desc": scen_desc,
            "seed": seed,
            "input_variant": input_variant(cfg),
            "input_size": int(cfg["image_size"]),
            "input_range": "0-255",
            "input_color": "RGB",
            "classes": list(cfg["classes"]),
            "class_names_id": list(cfg["class_names_id"]),
            "quantization": quant,
            "version": datetime.now(UTC).isoformat(timespec="seconds"),
            "trained_on": "MFED (train split)",
            "metrics": collect_metrics(arch_dir),
            "parity": _parity(model, tfl, int(cfg["image_size"])),
            "demo": False,
        }
        if meta["input_variant"] == "eye_crop":
            roi = cfg.get("roi") or {}
            meta["crop"] = {"margin": roi.get("margin", 0.10), "resize": "lanczos",
                            "mask": "circle", "reference": "src/fishfresh/eye_roi.py::crop_eye"}
        print(f"  paritas Keras vs TFLite: {meta['parity']}")
        done.append((_write(out_dir, model_id, tfl, meta), meta))
        keras.backend.clear_session()
    return done


MODELS_REPO = os.environ.get("IKANSEGAR_MODELS_REPO", "ahmdsyfk/klasifikasi-kesegaran-ikan")
REGISTRY_TAG = "registry"


def _gh(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", *args], check=check, capture_output=True, text=True)


def _release_notes(items: list[tuple[Path, dict]]) -> str:
    m0 = items[0][1]
    rows = ["| Arsitektur | F1 MFED | F1 FFE | ΔF1 | Latensi CPU 4 thread | Ukuran |", "|---|---|---|---|---|---|"]
    for _, m in items:
        x = m["metrics"]

        def f(k, d=4, x=x):
            return "-" if x.get(k) is None else f"{x[k]:.{d}f}"

        rows.append(f"| {m['name']} | {f('f1_mfed')} | {f('f1_ffe')} | {f('delta_f1')} | "
                    f"{f('latency_ms_cpu4', 1)} ms | {m['file_mb']} MB |")
    return (f"Model {m0['scenario_name']} (seed {m0['seed']}), kuantisasi {m0['quantization']}, "
            f"input {m0['input_variant']} {m0['input_size']} px.\n\n" + "\n".join(rows) +
            "\n\nDiunduh otomatis oleh aplikasi IkanSegar lewat registry.json di rilis `registry`.")


def publish(items: list[tuple[Path, dict]], default_arch: str | None, repo: str = MODELS_REPO) -> None:
    """Terbitkan model ke GitHub Releases repo publik, lalu perbarui registry.json yang dibaca aplikasi.

    - Rilis `model-<skenario>-s<seed>` berisi <id>.tflite tiap arsitektur.
    - Rilis `registry` berisi registry.json (daftar semua model + URL unduhan + SHA-256).
    Butuh `gh` yang login dengan akses tulis ke repo.
    """
    import tempfile

    if not items:
        print("[publish] tidak ada model untuk diterbitkan")
        return
    m0 = items[0][1]
    tag = f"model-{m0['scenario']}-s{m0['seed']}"
    if _gh("release", "view", tag, "--repo", repo, check=False).returncode != 0:
        _gh("release", "create", tag, "--repo", repo, "--latest=false",
            "--title", f"Model {m0['scenario_name']} · seed {m0['seed']}", "--notes", _release_notes(items))
    else:
        _gh("release", "edit", tag, "--repo", repo, "--notes", _release_notes(items))
    _gh("release", "upload", tag, "--repo", repo, "--clobber", *[str(p) for p, _ in items])
    print(f"  rilis {repo}@{tag}: {len(items)} model")

    with tempfile.TemporaryDirectory() as td:
        reg_path = Path(td) / "registry.json"
        if _gh("release", "view", REGISTRY_TAG, "--repo", repo, check=False).returncode != 0:
            _gh("release", "create", REGISTRY_TAG, "--repo", repo, "--latest=false", "--title", "Registry model",
                "--notes", "registry.json: daftar model yang dibaca aplikasi IkanSegar. Jangan dihapus.")
            registry = {"schema": 1, "models": []}
        elif _gh("release", "download", REGISTRY_TAG, "--repo", repo, "-p", "registry.json", "-D", td,
                 check=False).returncode == 0:
            registry = json.loads(reg_path.read_text())
        else:
            registry = {"schema": 1, "models": []}

        rows = {r["id"]: r for r in registry.get("models", [])}
        for path, meta in items:
            is_default = default_arch == meta["arch"]
            if is_default:
                for r in rows.values():
                    r["is_default"] = False
            rows[meta["id"]] = {
                "id": meta["id"], "scenario": meta["scenario"], "scenario_name": meta["scenario_name"],
                "scenario_desc": meta["scenario_desc"], "arch": meta["arch"], "arch_name": meta["name"],
                "seed": meta["seed"], "input_variant": meta["input_variant"], "input_size": meta["input_size"],
                "classes": meta["classes"], "quantization": meta["quantization"], "version": meta["version"],
                "file_url": f"https://github.com/{repo}/releases/download/{tag}/{path.name}",
                "file_mb": meta["file_mb"], "file_sha256": meta["file_sha256"], "metrics": meta["metrics"],
                "sort_order": 10 * (SCENARIO_ORDER.index(meta["scenario"])
                                    if meta["scenario"] in SCENARIO_ORDER else 50)
                              + ARCH_ORDER.get(meta["arch"], 9),
                "is_default": is_default or rows.get(meta["id"], {}).get("is_default", False),
                "active": True,
            }
            print(f"  registry: {meta['id']}{' (default)' if is_default else ''}")
        registry["models"] = sorted(rows.values(), key=lambda r: r["sort_order"])
        registry["updated"] = datetime.now(UTC).isoformat(timespec="seconds")
        registry["schema"] = 1
        reg_path.write_text(json.dumps(registry, indent=2, ensure_ascii=False))
        _gh("release", "upload", REGISTRY_TAG, "--repo", repo, "--clobber", str(reg_path))
    print(f"  registry.json diperbarui: https://github.com/{repo}/releases/download/{REGISTRY_TAG}/registry.json")


def bundle(items: list[tuple[Path, dict]], arch: str) -> None:
    """Salin satu model ke aset APK sebagai model bawaan (tersedia offline sejak instal)."""
    for path, meta in items:
        if meta["arch"] == arch:
            BUNDLE_OUT.mkdir(parents=True, exist_ok=True)
            for old in BUNDLE_OUT.glob("*"):
                old.unlink()
            shutil.copy2(path, BUNDLE_OUT / path.name)
            shutil.copy2(path.with_suffix(".json"), BUNDLE_OUT / f"{meta['id']}.json")
            print(f"  model bawaan APK: {BUNDLE_OUT / path.name}")
            return
    print(f"[bundle] arsitektur {arch} tidak ada di hasil ekspor")


def export_demo(cfg: dict, out_dir: Path) -> None:
    """CNN kecil berbobot acak — HANYA untuk menguji alur aplikasi, bukan hasil penelitian."""
    import keras
    from keras import layers

    s = cfg["image_size"]
    keras.utils.set_random_seed(0)
    inp = keras.Input((s, s, 3), name="image_rgb_0_255")
    x = layers.Rescaling(1 / 255.0)(inp)
    for f in (8, 16, 32):
        x = layers.Conv2D(f, 3, strides=2, activation="relu")(x)
    x = layers.GlobalAveragePooling2D()(x)
    out = layers.Dense(len(cfg["classes"]), activation="softmax")(x)
    tfl = _convert(keras.Model(inp, out), "none")
    meta = {
        "id": "demo", "name": "CNN kecil", "arch": "tiny-cnn", "scenario": "demo",
        "scenario_name": "Model demo", "scenario_desc": "Hanya untuk uji alur aplikasi.",
        "seed": 0, "input_variant": "eye_crop", "input_size": int(s), "input_range": "0-255",
        "input_color": "RGB", "classes": list(cfg["classes"]),
        "class_names_id": list(cfg["class_names_id"]), "quantization": "none",
        "version": datetime.now(UTC).isoformat(timespec="seconds"), "trained_on": "tidak dilatih",
        "metrics": {}, "demo": True,
    }
    _write(out_dir, "demo", tfl, meta)


def main(argv: list[str] | None = None) -> None:
    from .config import load_config

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("-c", "--config", default="configs/default.yaml")
    ap.add_argument("--models", nargs="+", default=None, help="arsitektur (default: semua di config)")
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--quant", choices=["float16", "dynamic", "none"], default="float16")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--publish", action="store_true", help="terbitkan ke GitHub Releases + perbarui registry.json")
    ap.add_argument("--repo", default=MODELS_REPO, help="repo publik tujuan rilis model")
    ap.add_argument("--default", dest="default_arch", default=None,
                    help="tandai arsitektur ini sebagai model bawaan di aplikasi (dengan --publish)")
    ap.add_argument("--bundle", default=None, help="salin arsitektur ini ke aset APK")
    ap.add_argument("--demo", action="store_true", help="ekspor model dummy ke aset debug")
    a = ap.parse_args(argv)

    cfg = load_config(a.config)
    if a.demo:
        export_demo(cfg, DEMO_OUT)
        return
    seed = cfg["seed"] if a.seed is None else a.seed
    items = export_trained(cfg, a.config, a.models or cfg["models"], seed, a.quant, a.out)
    if a.bundle:
        bundle(items, a.bundle)
    if a.publish:
        publish(items, a.default_arch, a.repo)


if __name__ == "__main__":
    main()
