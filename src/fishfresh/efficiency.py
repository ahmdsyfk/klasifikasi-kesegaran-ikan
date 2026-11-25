"""Pengukuran efisiensi untuk inferensi di ponsel (proposal 3.7.3).

- Jumlah parameter: model.count_params()
- Ukuran berkas: model.keras (MB)
- Waktu inferensi: CPU saja (GPU disembunyikan), thread dibatasi 4, batch size 1,
  10 prediksi warm-up, rata-rata 200 citra uji (ms/citra)
- Memori puncak: RSS proses diukur psutil (sampling di thread latar)
- Setiap pengulangan dijalankan di proses baru (3x), lalu dirata-rata.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np

from .config import run_dir, save_json


def _limit_env(threads: int) -> dict:
    env = os.environ.copy()
    env.update(
        {
            "CUDA_VISIBLE_DEVICES": "-1",  # tanpa GPU
            "TF_NUM_INTRAOP_THREADS": str(threads),
            "TF_NUM_INTEROP_THREADS": "1",
            "OMP_NUM_THREADS": str(threads),
            "MKL_NUM_THREADS": str(threads),
            "OPENBLAS_NUM_THREADS": str(threads),
            "TF_CPP_MIN_LOG_LEVEL": "2",
        }
    )
    return env


class PeakRSS:
    def __init__(self, interval_ms: float = 5):
        import psutil

        self.proc = psutil.Process()
        self.interval = interval_ms / 1000
        self.peak = 0
        self._stop = threading.Event()

    def _run(self):
        while not self._stop.is_set():
            self.peak = max(self.peak, self.proc.memory_info().rss)
            time.sleep(self.interval)

    def __enter__(self):
        self.peak = self.proc.memory_info().rss
        self._t = threading.Thread(target=self._run, daemon=True)
        self._t.start()
        return self

    def __exit__(self, *a):
        self._stop.set()
        self._t.join()
        self.peak = max(self.peak, self.proc.memory_info().rss)


def worker(model_path: str, images_npy: str, threads: int, warmup: int, interval_ms: float) -> dict:
    """Dijalankan di subprocess bersih."""
    import psutil

    proc = psutil.Process()
    # Pin ke `threads` core bila OS mendukung (Linux/Windows); macOS tidak mendukung afinitas.
    affinity = None
    if hasattr(proc, "cpu_affinity"):
        try:
            cpus = proc.cpu_affinity()[:threads]
            proc.cpu_affinity(cpus)
            affinity = cpus
        except Exception:  # noqa: BLE001
            affinity = None

    import tensorflow as tf

    tf.config.threading.set_intra_op_parallelism_threads(threads)
    tf.config.threading.set_inter_op_parallelism_threads(1)
    tf.config.set_visible_devices([], "GPU")
    import keras

    from . import augment, models  # noqa: F401 - registrasi custom layer

    images = np.load(images_npy)
    rss_before_load = proc.memory_info().rss
    t = time.perf_counter()
    model = keras.models.load_model(model_path, compile=False)
    load_time = time.perf_counter() - t
    rss_after_load = proc.memory_info().rss

    @tf.function(reduce_retracing=True)
    def infer(x):
        return model(x, training=False)

    x0 = tf.constant(images[:1])
    for i in range(warmup):
        infer(tf.constant(images[i % len(images)][None])).numpy()

    times = []
    with PeakRSS(interval_ms) as mon:
        for img in images:
            x = tf.constant(img[None])
            t = time.perf_counter()
            infer(x).numpy()
            times.append((time.perf_counter() - t) * 1000)
    del x0
    times = np.array(times)
    return {
        "n_images": len(times),
        "latency_ms_mean": float(times.mean()),
        "latency_ms_std": float(times.std()),
        "latency_ms_median": float(np.median(times)),
        "latency_ms_p95": float(np.percentile(times, 95)),
        "throughput_img_s": float(1000 / times.mean()),
        "model_load_s": load_time,
        "rss_before_load_mb": rss_before_load / 2**20,
        "rss_after_load_mb": rss_after_load / 2**20,
        "peak_rss_inference_mb": mon.peak / 2**20,
        "model_memory_delta_mb": (rss_after_load - rss_before_load) / 2**20,
        "intra_op_threads": tf.config.threading.get_intra_op_parallelism_threads(),
        "cpu_affinity": affinity,
        "visible_gpus": len(tf.config.get_visible_devices("GPU")),
    }


def _prepare_images(cfg: dict, seed: int, n: int, path: Path) -> None:
    from .data import load_split

    if path.exists():
        return
    import tensorflow as tf

    df = load_split(cfg, seed)
    test = df[df.split == "test"].sample(n=min(n, (df.split == "test").sum()), random_state=seed)
    size = cfg["image_size"]
    arr = []
    for p in test["path"]:
        img = tf.io.decode_image(tf.io.read_file(p), channels=3, expand_animations=False)
        arr.append(tf.image.resize(img, [size, size], antialias=True).numpy().astype("float32"))
    np.save(path, np.stack(arr))


def benchmark_arch(arch: str, cfg: dict, seed: int) -> dict:
    e = cfg["efficiency"]
    out = run_dir(cfg, arch, seed)
    model_path = out / "model_inference.keras"
    if not model_path.exists():
        raise FileNotFoundError(f"{model_path} belum ada; latih model dulu.")
    img_path = run_dir(cfg, None, seed) / f"bench_images_{e['n_images']}.npy"
    _prepare_images(cfg, seed, e["n_images"], img_path)

    runs = []
    for r in range(e["repeats"]):
        cmd = [
            sys.executable, "-m", "fishfresh.efficiency",
            str(model_path), str(img_path), str(e["threads"]), str(e["warmup"]),
            str(e["memory_sample_interval_ms"]),
        ]
        res = subprocess.run(cmd, env=_limit_env(e["threads"]), capture_output=True, text=True, check=False)
        if res.returncode != 0:
            raise RuntimeError(f"Benchmark gagal ({arch}, ulangan {r + 1}):\n{res.stderr[-3000:]}")
        line = [ln for ln in res.stdout.splitlines() if ln.startswith("@@RESULT ")][-1]
        runs.append(json.loads(line[len("@@RESULT "):]))
        print(f"  {arch} ulangan {r + 1}: {runs[-1]['latency_ms_mean']:.2f} ms/citra, "
              f"peak RSS {runs[-1]['peak_rss_inference_mb']:.0f} MB")

    import keras

    from . import augment, models  # noqa: F401 - registrasi custom layer

    model = keras.models.load_model(model_path, compile=False)
    keys = [k for k, v in runs[0].items() if isinstance(v, (int, float)) and not isinstance(v, bool)]
    summary = {
        "arch": arch,
        "params_total": int(model.count_params()),
        "model_file_mb": model_path.stat().st_size / 2**20,
        "repeats": len(runs),
        "mean": {k: float(np.mean([r[k] for r in runs])) for k in keys},
        "std": {k: float(np.std([r[k] for r in runs])) for k in keys},
        "runs": runs,
        "settings": e,
    }
    meta_p = out / "train_meta.json"
    if meta_p.exists():
        summary["train_time_s"] = json.loads(meta_p.read_text())["train_time_s"]
    # thread=4 (proposal) -> efficiency.json; skenario lain -> efficiency_t<n>.json
    fname = "efficiency.json" if e["threads"] == 4 else f"efficiency_t{e['threads']}.json"
    save_json(summary, out / fname)
    keras.backend.clear_session()
    return summary


if __name__ == "__main__":
    model_path, images, threads, warmup, interval = sys.argv[1:6]
    r = worker(model_path, images, int(threads), int(warmup), float(interval))
    print("@@RESULT " + json.dumps(r))
