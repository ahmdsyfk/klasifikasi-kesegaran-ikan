"""Pemeriksaan, deduplikasi, pembagian data, dan pipeline tf.data (proposal 3.5 & 3.6)."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image, ImageOps
from tqdm import tqdm

IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}

# Urutan penting: "not fresh"/"highly fresh" harus dicek sebelum "fresh".
_LABEL_PATTERNS = [
    ("not_fresh", re.compile(r"\b(not|non|un)[\s_\-]*fresh\b|tidak[\s_\-]*segar|busuk")),
    ("highly_fresh", re.compile(r"\b(highly|very)[\s_\-]*fresh\b|sangat[\s_\-]*segar")),
    ("fresh", re.compile(r"\bfresh\b|\bsegar\b")),
]


def infer_label(path: Path, root: Path) -> str | None:
    """Ambil label dari nama folder (dari terdalam ke atas), mis. 'Chanos Chanos - Not Fresh'."""
    parts = path.relative_to(root).parts[:-1]
    for part in reversed(parts):
        s = re.sub(r"\s+", " ", part.lower())
        for label, pat in _LABEL_PATTERNS:
            if pat.search(s):
                return label
    return None


def infer_species(path: Path, root: Path) -> str:
    """Spesies dari pola '<Spesies> - <Kelas>' (FFE) atau folder di atas folder kelas."""
    parts = path.relative_to(root).parts[:-1]
    for part in reversed(parts):
        if " - " in part:
            return re.sub(r"\s+", " ", part.split(" - ")[0]).strip().title()
    for i in range(len(parts) - 1, -1, -1):
        if infer_label(Path(parts[i]) / "x", Path()) is not None and i > 0:
            return parts[i - 1].strip().title()
    return "unknown"


def scan_dataset(root: str | Path, name: str) -> pd.DataFrame:
    root = Path(root)
    if not root.exists():
        raise FileNotFoundError(f"Folder dataset {name} tidak ditemukan: {root}")
    rows = []
    for p in sorted(root.rglob("*")):
        if p.suffix.lower() in IMG_EXT and p.is_file() and not p.name.startswith("."):
            if "__MACOSX" in p.parts:
                continue
            rows.append(
                {
                    "dataset": name,
                    "path": str(p),
                    "label": infer_label(p, root),
                    "species": infer_species(p, root),
                }
            )
    df = pd.DataFrame(rows)
    if df.empty:
        raise RuntimeError(f"Tidak ada citra di {root}")
    return df


def _phash_int(img: Image.Image) -> int:
    import imagehash

    return int(str(imagehash.phash(img)), 16)


def verify_and_hash(df: pd.DataFrame) -> pd.DataFrame:
    """Cek citra bisa dibuka & tidak rusak, catat ukuran, SHA-256, dan pHash."""
    out = []
    for path in tqdm(df["path"], desc="verifikasi"):
        rec = {"ok": False, "error": "", "width": 0, "height": 0, "mode": "", "sha256": "", "phash": 0}
        try:
            data = Path(path).read_bytes()
            rec["sha256"] = hashlib.sha256(data).hexdigest()
            with Image.open(path) as im:
                im.verify()
            with Image.open(path) as im:
                im = ImageOps.exif_transpose(im)
                rec["mode"] = im.mode
                im = im.convert("RGB")
                rec["width"], rec["height"] = im.size
                rec["phash"] = _phash_int(im)
            rec["ok"] = True
        except Exception as e:  # noqa: BLE001 - catat semua jenis kerusakan
            rec["error"] = f"{type(e).__name__}: {e}"
        out.append(rec)
    res = pd.concat([df.reset_index(drop=True), pd.DataFrame(out)], axis=1)
    res["phash"] = res["phash"].astype("uint64")
    return res


def _popcount64(x: np.ndarray) -> np.ndarray:
    x = x.astype(np.uint64)
    cnt = np.zeros(x.shape, dtype=np.uint8)
    for shift in range(0, 64, 8):
        cnt += _POP8[((x >> np.uint64(shift)) & np.uint64(0xFF)).astype(np.uint8)]
    return cnt


_POP8 = np.array([(i).bit_count() for i in range(256)], dtype=np.uint8)


def near_duplicate_pairs(h1: np.ndarray, h2: np.ndarray | None, thr: int, chunk: int = 512):
    """Pasangan (i, j) dengan jarak Hamming pHash <= thr. h2=None -> dalam satu himpunan."""
    same = h2 is None
    h2 = h1 if same else h2
    pairs = []
    for s in range(0, len(h1), chunk):
        block = h1[s : s + chunk, None] ^ h2[None, :]
        d = _popcount64(block)
        ii, jj = np.nonzero(d <= thr)
        ii = ii + s
        if same:
            m = ii < jj
            ii, jj = ii[m], jj[m]
        pairs.append(np.stack([ii, jj], 1))
    return np.concatenate(pairs) if pairs else np.empty((0, 2), int)


def _union_find_groups(n: int, pairs: np.ndarray) -> np.ndarray:
    parent = np.arange(n)

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b in pairs:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra
    return np.array([find(i) for i in range(n)])


def _drop_rows(df: pd.DataFrame, kriteria: str, keterangan) -> pd.DataFrame:
    cols = [c for c in ["dataset", "path", "label", "species", "sha256"] if c in df]
    d = df[cols].copy()
    d["kriteria"] = kriteria
    d["keterangan"] = keterangan if not isinstance(keterangan, str) else [keterangan] * len(d)
    return d


def deduplicate(df: pd.DataFrame, cfg: dict) -> tuple[pd.DataFrame, dict]:
    """Tangani duplikat identik & konflik label; bentuk grup hampir-duplikat untuk split.

    `report["_dibuang"]` berisi baris yang dibuang beserta kriteria yang menyaringnya.
    """
    dcfg = cfg["dedup"]
    report = {"n_input": len(df)}
    dropped = []
    bad = df[~df["ok"]]
    if len(bad):
        dropped.append(_drop_rows(bad, "rusak_tidak_terbaca", bad["error"].tolist()))
    nolab = df[df["ok"] & df["label"].isna()]
    if len(nolab):
        dropped.append(_drop_rows(nolab, "label_tidak_dikenali", "nama folder tidak memuat kelas"))
    df = df[df["ok"] & df["label"].notna()].copy()
    report["n_valid"] = len(df)

    # konflik label: hash identik tapi label berbeda
    nlab = df.groupby("sha256")["label"].nunique()
    conflict = set(nlab[nlab > 1].index)
    report["n_label_conflict_files"] = int(df["sha256"].isin(conflict).sum())
    if dcfg["drop_label_conflicts"] and conflict:
        c = df[df["sha256"].isin(conflict)]
        dropped.append(_drop_rows(c, "konflik_label", "berkas identik (SHA-256) berlabel berbeda"))
        df = df[~df["sha256"].isin(conflict)]

    dup_mask = df.duplicated("sha256", keep="first")
    report["n_exact_duplicates"] = int(dup_mask.sum())
    if dcfg["drop_exact_duplicates"] and dup_mask.any():
        first = df.drop_duplicates("sha256").set_index("sha256")["path"]
        d = df[dup_mask]
        dropped.append(_drop_rows(d, "duplikat_identik",
                                  [f"SHA-256 sama dengan {first[h]}" for h in d["sha256"]]))
        df = df[~dup_mask]
    df = df.reset_index(drop=True)

    pairs = near_duplicate_pairs(df["phash"].to_numpy(np.uint64), None, dcfg["phash_hamming_threshold"])
    report["n_near_duplicate_pairs"] = len(pairs)
    groups = _union_find_groups(len(df), pairs)
    df["group"] = groups
    gl = df.groupby("group")["label"].nunique()
    report["n_near_dup_groups_mixed_label"] = int((gl > 1).sum())
    report["n_groups"] = int(df["group"].nunique())
    report["n_output"] = len(df)
    report["_dibuang"] = pd.concat(dropped, ignore_index=True) if dropped else pd.DataFrame()
    return df, report


def apply_quality_filter(df: pd.DataFrame, cfg: dict, dataset: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Gabungkan hasil ROI mata (eye_path + metrik) lalu buang citra tak berguna.

    Kembalikan (df_tersaring_dengan_eye_path, baris_dibuang_dengan_kriteria).
    """
    from .eye_roi import apply_quality_rules

    mpath = Path(cfg["paths"]["roi_dir"]) / f"roi_{dataset.lower()}.csv"
    if not mpath.exists():
        print(f"[peringatan] {mpath} belum ada -> filter kualitas & ROI mata dilewati "
              "(jalankan: uv run --extra roi fishfresh roi)")
        return df, pd.DataFrame()
    roi = pd.read_csv(mpath).drop_duplicates("sha256").drop(columns=["dataset"], errors="ignore")
    df = df.merge(roi, on="sha256", how="left")
    if not cfg["roi"].get("quality_filter", True):
        return df, pd.DataFrame()
    reasons = []
    for _, r in df.iterrows():
        rr = r.to_dict()
        rr["dataset"] = dataset
        bad = apply_quality_rules(rr, cfg["roi"])
        reasons.append(bad)
    mask = np.array([len(b) > 0 for b in reasons], bool)
    d = df[mask]
    rows = []
    for (_, r), b in zip(d.iterrows(), [x for x in reasons if x]):
        rows.append({"dataset": dataset, "path": r["path"], "label": r["label"],
                     "species": r.get("species"), "sha256": r["sha256"],
                     "kriteria": "+".join(k for k, _ in b), "keterangan": "; ".join(v for _, v in b),
                     "eye_path": r.get("eye_path", "")})
    return df[~mask].reset_index(drop=True), pd.DataFrame(rows)


def stratified_group_split(df: pd.DataFrame, cfg: dict, seed: int) -> pd.Series:
    """Split 70:15:15 terstratifikasi; grup hampir-duplikat tidak dipecah."""
    from sklearn.model_selection import StratifiedGroupKFold, train_test_split

    s = cfg["split"]
    if not np.isclose(s["train"] + s["val"] + s["test"], 1.0):
        raise ValueError("Proporsi split harus berjumlah 1")
    split = pd.Series("train", index=df.index)
    if s.get("group_near_duplicates", True) and df["group"].nunique() < len(df):
        # 20 fold x 5% -> test/val = kelipatan 5% (15% = 3 fold)
        n_folds = 20
        sgkf = StratifiedGroupKFold(n_splits=n_folds, shuffle=True, random_state=seed)
        fold = np.empty(len(df), int)
        for k, (_, idx) in enumerate(sgkf.split(df, df["label"], df["group"])):
            fold[idx] = k
        n_test = round(s["test"] * n_folds)
        n_val = round(s["val"] * n_folds)
        split[fold < n_test] = "test"
        split[(fold >= n_test) & (fold < n_test + n_val)] = "val"
    else:
        idx = df.index.to_numpy()
        _tr, tmp = train_test_split(
            idx, test_size=s["val"] + s["test"], stratify=df["label"], random_state=seed
        )
        va, te = train_test_split(
            tmp,
            test_size=s["test"] / (s["val"] + s["test"]),
            stratify=df.loc[tmp, "label"],
            random_state=seed,
        )
        split[va] = "val"
        split[te] = "test"
    return split


def cross_dataset_overlap(mfed: pd.DataFrame, ffe: pd.DataFrame, thr: int) -> pd.DataFrame:
    exact = set(mfed["sha256"]) & set(ffe["sha256"])
    pairs = near_duplicate_pairs(
        ffe["phash"].to_numpy(np.uint64), mfed["phash"].to_numpy(np.uint64), thr
    )
    ffe = ffe.copy()
    ffe["overlap_exact"] = ffe["sha256"].isin(exact)
    near_idx = set(ffe.index[pairs[:, 0]]) if len(pairs) else set()
    ffe["overlap_near"] = ffe.index.isin(near_idx)
    return ffe


# ---------------------------------------------------------------- tf.data
def class_index(cfg: dict) -> dict[str, int]:
    return {c: i for i, c in enumerate(cfg["classes"])}


def make_dataset(df: pd.DataFrame, cfg: dict, training: bool, seed: int, batch_size: int | None = None):
    """Citra -> RGB -> resize 224 -> float32 [0,255]. Normalisasi ada di dalam model."""
    import tensorflow as tf

    from .augment import build_augmenter

    size = cfg["image_size"]
    bs = batch_size or cfg["train"]["batch_size"]
    cidx = class_index(cfg)
    paths = df["path"].to_numpy()
    labels = df["label"].map(cidx).to_numpy().astype("int32")

    def load(path, label):
        raw = tf.io.read_file(path)
        img = tf.io.decode_image(raw, channels=3, expand_animations=False)
        img.set_shape([None, None, 3])
        img = tf.image.resize(img, [size, size], method="bilinear", antialias=True)
        return tf.cast(img, tf.float32), label

    ds = tf.data.Dataset.from_tensor_slices((paths, labels))
    if training:
        ds = ds.shuffle(len(df), seed=seed, reshuffle_each_iteration=True)
    ds = ds.map(load, num_parallel_calls=tf.data.AUTOTUNE)
    if training:
        ds = ds.cache()  # cache setelah resize (hemat RAM: ~600 KB/citra)
    ds = ds.batch(bs)
    if training and cfg["augment"]["enabled"]:
        aug = build_augmenter(cfg, seed)
        ds = ds.map(lambda x, y: (aug(x, training=True), y), num_parallel_calls=tf.data.AUTOTUNE)
    return ds.prefetch(tf.data.AUTOTUNE)


def load_split(cfg: dict, seed: int) -> pd.DataFrame:
    p = Path(cfg["paths"]["splits_dir"]) / f"mfed_split_seed{seed}.csv"
    if not p.exists():
        raise FileNotFoundError(f"{p} belum ada. Jalankan: uv run fishfresh prepare --seed {seed}")
    return _apply_roi_mode(pd.read_csv(p), cfg)


def load_ffe(cfg: dict, exclude_overlap: bool = False) -> pd.DataFrame:
    p = Path(cfg["paths"]["splits_dir"]) / "ffe_manifest.csv"
    if not p.exists():
        raise FileNotFoundError(f"{p} belum ada. Jalankan: uv run fishfresh prepare")
    df = pd.read_csv(p)
    if exclude_overlap and "overlap_near" in df:
        df = df[~(df["overlap_near"] | df["overlap_exact"])]
    return _apply_roi_mode(df, cfg)


def _apply_roi_mode(df: pd.DataFrame, cfg: dict) -> pd.DataFrame:
    """mode eye_mask -> kolom `path` diganti citra mata bermasker; `orig_path` menyimpan aslinya."""
    if cfg.get("roi", {}).get("mode", "none") != "eye_mask":
        return df
    if "eye_path" not in df or df["eye_path"].isna().any():
        raise RuntimeError("roi.mode=eye_mask tetapi eye_path belum lengkap. Jalankan `fishfresh roi` "
                           "lalu `fishfresh prepare` ulang.")
    df = df.copy()
    df["orig_path"] = df["path"]
    df["path"] = df["eye_path"]
    return df
