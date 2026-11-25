"""Pemuatan konfigurasi YAML + override dari CLI."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / "configs" / "default.yaml"


def _deep_update(base: dict, upd: dict) -> dict:
    for k, v in upd.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _deep_update(base[k], v)
        else:
            base[k] = v
    return base


def _parse_value(raw: str) -> Any:
    try:
        return yaml.safe_load(raw)
    except yaml.YAMLError:
        return raw


def load_config(path: str | Path | None = None, overrides: list[str] | None = None) -> dict:
    """Muat default.yaml, lalu file `path` (opsional), lalu override `a.b=nilai`."""
    cfg = yaml.safe_load(DEFAULT_CONFIG.read_text())
    if path and Path(path).resolve() != DEFAULT_CONFIG:
        _deep_update(cfg, yaml.safe_load(Path(path).read_text()) or {})
    for item in overrides or []:
        key, _, raw = item.partition("=")
        if not _:
            raise ValueError(f"Override harus berbentuk kunci=nilai, dapat: {item!r}")
        node = cfg
        parts = key.strip().split(".")
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = _parse_value(raw)
    # path relatif terhadap root repo
    for k, v in cfg["paths"].items():
        p = Path(v)
        cfg["paths"][k] = str(p if p.is_absolute() else ROOT / p)
    return cfg


def run_dir(cfg: dict, arch: str | None = None, seed: int | None = None) -> Path:
    seed = cfg["seed"] if seed is None else seed
    d = Path(cfg["paths"]["outputs_dir"]) / cfg["run_name"] / f"seed{seed}"
    if arch:
        d = d / arch
    d.mkdir(parents=True, exist_ok=True)
    return d


def save_json(obj: Any, path: str | Path) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(obj, indent=2, ensure_ascii=False, default=str))


def snapshot(cfg: dict) -> dict:
    return copy.deepcopy(cfg)
