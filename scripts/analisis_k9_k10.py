"""K9 (tumpang-tindih FFE-MFED) dan K10 (spesies terlihat vs tak terlihat) tanpa latih ulang.

K9 : jarak Hamming pHash minimum tiap citra FFE ke seluruh MFED (sensitivitas ambang 4/8/10/12).
K10: F1 makro FFE dipisah menjadi spesies air tawar yang kemungkinan juga ada di MFED
     (Oreochromis niloticus/nila & O. mossambicus/mujair) vs 6 spesies laut yang tak pernah dilihat.
     Dihitung dari pred_ffe.csv semua model yang sudah dievaluasi.

    uv run python scripts/analisis_k9_k10.py
Keluaran: outputs/k9_overlap_ffe.csv, outputs/k10_spesies.csv, outputs/k10_per_spesies.csv
"""
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, f1_score

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "outputs"
SEEN = {"Oreochromis Niloticus", "Oreochromis Mossambicus"}


def k9() -> pd.DataFrame:
    ffe = pd.read_pickle(ROOT / "data/splits/ffe_verified.pkl")
    mfed = pd.read_pickle(ROOT / "data/splits/mfed_verified.pkl")
    a = ffe.loc[ffe["ok"], "phash"].to_numpy(np.uint64)
    b = mfed.loc[mfed["ok"], "phash"].to_numpy(np.uint64)
    dmin = np.empty(len(a), dtype=np.int64)
    for i in range(0, len(a), 256):  # popcount XOR, per blok agar hemat memori
        x = np.bitwise_xor(a[i:i + 256, None], b[None, :])
        bits = np.unpackbits(x.view(np.uint8).reshape(*x.shape, 8), axis=-1).sum(-1)
        dmin[i:i + 256] = bits.min(1)
    rows = [{"ambang_hamming": t, "n_ffe_tumpang_tindih": int((dmin <= t).sum()), "n_ffe": len(a)}
            for t in (0, 4, 8, 10, 12)]
    rows.append({"ambang_hamming": "min_jarak", "n_ffe_tumpang_tindih": int(dmin.min()), "n_ffe": len(a)})
    rows.append({"ambang_hamming": "median_jarak", "n_ffe_tumpang_tindih": float(np.median(dmin)),
                 "n_ffe": len(a)})
    return pd.DataFrame(rows)


def _m(d: pd.DataFrame) -> dict:
    return {"n": len(d), "acc": accuracy_score(d.label, d.pred),
            "f1_macro": f1_score(d.label, d.pred, average="macro", zero_division=0)}


def k10() -> tuple[pd.DataFrame, pd.DataFrame]:
    grp, per = [], []
    for p in sorted(OUT.glob("*/seed*/*/pred_ffe.csv")):
        skenario, seed, arch = p.parts[-4], p.parts[-3].removeprefix("seed"), p.parts[-2]
        d = pd.read_csv(p)
        d["kelompok"] = np.where(d.species.isin(SEEN), "terlihat (nila/mujair)", "tak terlihat (6 laut)")
        key = {"skenario": skenario, "seed": int(seed), "arsitektur": arch}
        grp.append(key | {"kelompok": "semua"} | _m(d))
        for k, g in d.groupby("kelompok"):
            grp.append(key | {"kelompok": k} | _m(g))
        for s, g in d.groupby("species"):
            per.append(key | {"spesies": s, "terlihat": s in SEEN} | _m(g))
    return pd.DataFrame(grp), pd.DataFrame(per)


if __name__ == "__main__":
    t9 = k9()
    t9.to_csv(OUT / "k9_overlap_ffe.csv", index=False)
    print("K9\n", t9.to_string(index=False))
    g, s = k10()
    g.to_csv(OUT / "k10_spesies.csv", index=False)
    s.to_csv(OUT / "k10_per_spesies.csv", index=False)
    pv = g.pivot_table(index=["skenario", "seed", "arsitektur"], columns="kelompok", values="f1_macro")
    pv["selisih_terlihat-tak"] = pv["terlihat (nila/mujair)"] - pv["tak terlihat (6 laut)"]
    print("\nK10 F1 makro\n", pv.round(3).to_string())
    print("\nK10 per spesies (rerata F1 lintas model)\n",
          s.groupby(["skenario", "spesies"]).f1_macro.mean().unstack(0).round(3).to_string())
