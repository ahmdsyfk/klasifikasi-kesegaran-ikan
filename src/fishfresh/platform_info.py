"""Deteksi platform (macOS / Windows / Linux / WSL2) dan akselerator, lalu sesuaikan pengaturan TF.

Ringkasan perilaku:
- macOS (Apple Silicon/Intel): TensorFlow resmi hanya CPU. GPU Apple hanya tersedia bila plugin
  `tensorflow-metal` terpasang dan kompatibel. Mixed precision dimatikan (lambat di CPU).
- Windows native: TensorFlow >= 2.11 tidak mendukung GPU NVIDIA secara native -> CPU.
  Untuk RTX 4060 (proposal 3.2.1) gunakan WSL2 + `uv sync --extra cuda`.
- Linux / WSL2 dengan CUDA: memory growth diaktifkan; mixed precision boleh (mode `auto`).
"""

from __future__ import annotations

import os
import platform
import subprocess
from pathlib import Path


def _cpu_name() -> str:
    system = platform.system()
    try:
        if system == "Darwin":
            return subprocess.run(
                ["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True, check=True
            ).stdout.strip()
        if system == "Windows":
            import winreg  # type: ignore[import-not-found]

            k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
            return str(winreg.QueryValueEx(k, "ProcessorNameString")[0]).strip()
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.lower().startswith("model name"):
                return line.split(":", 1)[1].strip()
    except Exception:  # noqa: BLE001, S110 - nama CPU hanya informatif
        pass
    return platform.processor() or platform.machine()


def _is_wsl() -> bool:
    try:
        return "microsoft" in Path("/proc/version").read_text().lower()
    except OSError:
        return False


def detect() -> dict:
    """Kumpulkan informasi OS, CPU, RAM, dan perangkat TensorFlow."""
    import psutil
    import tensorflow as tf

    system = platform.system()
    os_name = {"Darwin": "macos", "Windows": "windows"}.get(system, "linux")
    if os_name == "linux" and _is_wsl():
        os_name = "wsl2"

    gpus = tf.config.list_physical_devices("GPU")
    built_cuda = tf.test.is_built_with_cuda()
    if gpus and built_cuda:
        accel = "cuda"
    elif gpus and os_name == "macos":
        accel = "metal"
    elif gpus:
        accel = "gpu"
    else:
        accel = "cpu"

    gpu_names = []
    for g in gpus:
        try:
            gpu_names.append(tf.config.experimental.get_device_details(g).get("device_name", g.name))
        except Exception:  # noqa: BLE001
            gpu_names.append(g.name)

    return {
        "os": os_name,
        "os_version": platform.platform(),
        "machine": platform.machine(),
        "cpu": _cpu_name(),
        "cpu_cores_logical": os.cpu_count(),
        "cpu_cores_physical": psutil.cpu_count(logical=False),
        "ram_gb": round(psutil.virtual_memory().total / 2**30, 1),
        "python": platform.python_version(),
        "tensorflow": tf.__version__,
        "tf_built_with_cuda": built_cuda,
        "accelerator": accel,
        "gpus": gpu_names,
    }


def configure(cfg: dict) -> dict:
    """Deteksi platform lalu terapkan pengaturan yang sesuai. Mengembalikan info + keputusan."""
    import keras
    import tensorflow as tf

    info = detect()
    notes: list[str] = []
    accel = info["accelerator"]

    for gpu in tf.config.list_physical_devices("GPU"):
        try:
            tf.config.experimental.set_memory_growth(gpu, True)
        except RuntimeError:
            pass

    # mixed precision: true/false/"auto" (auto = hanya di GPU CUDA)
    mp_cfg = cfg["train"].get("mixed_precision", "auto")
    if mp_cfg == "auto":
        mixed = accel == "cuda"
    else:
        mixed = bool(mp_cfg)
        if mixed and accel != "cuda":
            notes.append("mixed_precision diminta tetapi tidak ada GPU CUDA -> dimatikan (lambat di CPU/Metal)")
            mixed = False
    keras.mixed_precision.set_global_policy("mixed_float16" if mixed else "float32")

    if info["os"] == "windows" and accel == "cpu":
        notes.append(
            "Windows native: TensorFlow >= 2.11 tidak memakai GPU NVIDIA. "
            "Untuk RTX 4060 jalankan di WSL2 dengan `uv sync --extra cuda`."
        )
    if info["os"] == "macos" and accel == "cpu":
        notes.append(
            "macOS: TensorFlow berjalan di CPU (tanpa GPU Apple). Waktu pelatihan tidak setara "
            "dengan GPU RTX 4060 di proposal; metrik klasifikasi tetap sebanding."
        )
    if accel == "cpu":
        # Pakai seluruh core fisik untuk pelatihan (benchmark inferensi tetap dibatasi 4 thread)
        n = info["cpu_cores_physical"] or info["cpu_cores_logical"] or 1
        try:
            tf.config.threading.set_intra_op_parallelism_threads(n)
            tf.config.threading.set_inter_op_parallelism_threads(2)
        except RuntimeError:
            pass

    info["mixed_precision"] = mixed
    info["notes"] = notes
    return info


def describe(info: dict) -> str:
    gpu = ", ".join(info["gpus"]) or "-"
    lines = [
        f"[platform] OS={info['os']} ({info['os_version']})",
        f"[platform] CPU={info['cpu']} | {info['cpu_cores_physical']} core fisik | RAM {info['ram_gb']} GB",
        (
            f"[platform] TF {info['tensorflow']} | akselerator={info['accelerator']} | GPU={gpu} "
            f"| mixed_precision={info['mixed_precision']}"
        ),
    ]
    lines += [f"[platform] catatan: {n}" for n in info["notes"]]
    return "\n".join(lines)
