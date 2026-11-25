"""Antarmuka baris perintah: `uv run fishfresh <perintah> [opsi]`.

Urutan standar:
  download -> prepare -> [roi -> prepare] -> train -> evaluate -> benchmark -> gradcam -> summarize
atau sekaligus: `uv run fishfresh all`.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")


def _cfg(args):
    from .config import load_config

    cfg = load_config(args.config, args.set)
    if args.run_name:
        cfg["run_name"] = args.run_name
    return cfg


def _seeds(args, cfg) -> list[int]:
    return args.seeds or [cfg["seed"]]


def _archs(args, cfg) -> list[str]:
    return args.archs or cfg["models"]


def cmd_download(args, cfg):
    from .download import download_all

    download_all(cfg, args.which)


def _verified(cfg, name: str, root, force: bool):
    import pandas as pd

    from .data import scan_dataset, verify_and_hash

    sdir = Path(cfg["paths"]["splits_dir"])
    sdir.mkdir(parents=True, exist_ok=True)
    cache = sdir / f"{name.lower()}_verified.pkl"
    if cache.exists() and not force:
        return pd.read_pickle(cache)
    df = verify_and_hash(scan_dataset(root, name))
    df.to_pickle(cache)
    return df


def cmd_prepare(args, cfg):
    import json

    import pandas as pd

    from .config import save_json
    from .data import (
        _drop_rows,
        apply_quality_filter,
        cross_dataset_overlap,
        deduplicate,
        stratified_group_split,
    )

    sdir = Path(cfg["paths"]["splits_dir"])
    sdir.mkdir(parents=True, exist_ok=True)
    mfed = _verified(cfg, "MFED", cfg["paths"]["mfed_dir"], args.force)
    clean, report = deduplicate(mfed, cfg)
    dibuang = [report.pop("_dibuang")]
    # Split ditetapkan SEBELUM filter kualitas agar pembagian identik untuk semua skenario
    # (baseline tanpa ROI & eye_mask); filter hanya menghapus baris, tidak memindah citra antar-bagian.
    seeds = _seeds(args, cfg)
    for seed in seeds:
        clean[f"split_{seed}"] = stratified_group_split(clean, cfg, seed)
    report["n_sebelum_filter_kualitas"] = len(clean)
    clean, dq = apply_quality_filter(clean, cfg, "MFED")
    dibuang.append(dq)
    report["n_setelah_filter_kualitas"] = len(clean)
    report["label_counts"] = clean["label"].value_counts().to_dict()
    report["species_counts"] = clean["species"].value_counts().to_dict()

    for seed in seeds:
        clean["split"] = clean[f"split_{seed}"]
        cols = ["path", "label", "species", "group", "split", "width", "height", "sha256"]
        cols += [c for c in ["eye_path", "box_score", "box_x0", "box_y0", "box_x1", "box_y1"] if c in clean]
        clean[cols].to_csv(sdir / f"mfed_split_seed{seed}.csv", index=False)
        report[f"split_seed{seed}"] = (
            clean.groupby(["split", "label"]).size().unstack(fill_value=0).to_dict(orient="index")
        )

    ffe_dir = Path(cfg["paths"]["ffe_dir"])
    if ffe_dir.exists():
        ffe = _verified(cfg, "FFE", ffe_dir, args.force)
        bad = ffe[~ffe["ok"]]
        if len(bad):
            dibuang.append(_drop_rows(bad, "rusak_tidak_terbaca", bad["error"].tolist()))
        nolab = ffe[ffe["ok"] & ffe["label"].isna()]
        if len(nolab):
            dibuang.append(_drop_rows(nolab, "label_tidak_dikenali", "nama folder tidak memuat kelas"))
        ffe = ffe[ffe["ok"] & ffe["label"].notna()].reset_index(drop=True)
        ffe, dq = apply_quality_filter(ffe, cfg, "FFE")
        dibuang.append(dq)
        if cfg["dedup"]["check_cross_dataset"]:
            ffe = cross_dataset_overlap(clean, ffe, cfg["dedup"]["phash_hamming_threshold"])
            report["ffe_overlap_exact"] = int(ffe["overlap_exact"].sum())
            report["ffe_overlap_near"] = int(ffe["overlap_near"].sum())
        report["ffe_n"] = len(ffe)
        report["ffe_label_counts"] = ffe["label"].value_counts().to_dict()
        report["ffe_species_counts"] = ffe["species"].value_counts().to_dict()
        ffe.drop(columns=["phash"]).to_csv(sdir / "ffe_manifest.csv", index=False)
    else:
        print(f"[peringatan] FFE belum ada di {ffe_dir}")

    dibuang = [d for d in dibuang if len(d)]
    log = pd.concat(dibuang, ignore_index=True) if dibuang else pd.DataFrame(
        columns=["dataset", "path", "label", "species", "sha256", "kriteria", "keterangan"])
    log.to_csv(sdir / "data_dibuang.csv", index=False)
    report["dibuang_per_kriteria"] = (
        log.groupby(["dataset", "kriteria"]).size().unstack(fill_value=0).to_dict(orient="index")
        if len(log) else {}
    )
    report["ambang_filter_kualitas"] = cfg["roi"]["quality"]
    save_json(report, sdir / "laporan_pemeriksaan_data.json")
    print(json.dumps(report, indent=2, ensure_ascii=False, default=str))
    print(f"Data dibuang: {len(log)} baris -> {sdir / 'data_dibuang.csv'}")


def cmd_roi(args, cfg):
    """Deteksi mata (OWLv2) + crop bermasker + metrik kualitas untuk MFED & FFE."""
    from .eye_roi import build_rois, roi_dir, run_detection

    rd = roi_dir(cfg)
    sets = {"MFED": cfg["paths"]["mfed_dir"], "FFE": cfg["paths"]["ffe_dir"]}
    for name, root in sets.items():
        if not Path(root).exists():
            print(f"[peringatan] {name} belum ada di {root}, dilewati")
            continue
        df = _verified(cfg, name, root, args.force)
        df = df[df["ok"]].drop_duplicates("sha256")
        det = run_detection(list(zip(df["sha256"], df["path"])), rd / "detections.csv")
        roi = build_rois(df, det, cfg, name)
        roi.to_csv(rd / f"roi_{name.lower()}.csv", index=False)
        print(f"{name}: {roi['eye_path'].astype(bool).sum()}/{len(roi)} citra punya ROI mata")
    from .roi_report import roi_report

    roi_report(cfg)


def cmd_train(args, cfg):
    from .train import train_arch

    for seed in _seeds(args, cfg):
        for a in _archs(args, cfg):
            print(f"\n=== TRAIN {a} seed={seed} ===")
            m = train_arch(a, cfg, seed)
            print(f"selesai {a}: {m['train_time_s'] / 60:.1f} menit, {m['model_file_mb']:.1f} MB")


def cmd_evaluate(args, cfg):
    from .evaluate import evaluate_arch

    for seed in _seeds(args, cfg):
        for a in _archs(args, cfg):
            r = evaluate_arch(a, cfg, seed, exclude_ffe_overlap=args.exclude_ffe_overlap)
            line = f"{a} seed={seed} | MFED F1={r['mfed_test']['f1_macro']:.4f}"
            if "ffe" in r:
                line += f" | FFE F1={r['ffe']['f1_macro']:.4f} | dF1={r['generalization']['delta_f1']:.4f}"
            print(line)


def cmd_benchmark(args, cfg):
    from .efficiency import benchmark_arch

    for seed in _seeds(args, cfg):
        for a in _archs(args, cfg):
            s = benchmark_arch(a, cfg, seed)
            print(f"{a}: {s['mean']['latency_ms_mean']:.2f} ms/citra, "
                  f"peak {s['mean']['peak_rss_inference_mb']:.0f} MB, {s['model_file_mb']:.1f} MB")


def cmd_gradcam(args, cfg):
    from .gradcam import run_gradcam

    for seed in _seeds(args, cfg):
        for a in _archs(args, cfg):
            run_gradcam(a, cfg, seed, args.n_per_class)


def cmd_summarize(args, cfg):
    from .summarize import summarize

    print(f"Ringkasan: {summarize(cfg)}")


def cmd_all(args, cfg):
    cmd_prepare(args, cfg)
    cmd_train(args, cfg)
    cmd_evaluate(args, cfg)
    cmd_benchmark(args, cfg)
    cmd_gradcam(args, cfg)
    cmd_summarize(args, cfg)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(prog="fishfresh", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-c", "--config", default=None, help="YAML skenario (menimpa default.yaml)")
    common.add_argument("--set", nargs="*", default=[], metavar="KUNCI=NILAI",
                        help="override, mis. --set train.batch_size=16 image_size=299")
    common.add_argument("--seeds", nargs="*", type=int, help="mis. --seeds 42 123 2026")
    common.add_argument("--archs", nargs="*", choices=["vgg16", "resnet50", "inceptionv3"])
    common.add_argument("--run-name", default=None)
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("download", parents=[common], help="unduh MFED & FFE dari Mendeley")
    d.add_argument("--which", choices=["all", "mfed", "ffe"], default="all")
    d.set_defaults(fn=cmd_download)
    for name, fn, h in [
        ("prepare", cmd_prepare, "verifikasi, deduplikasi, filter kualitas, split 70:15:15"),
        ("roi", cmd_roi, "deteksi mata + crop bermasker + metrik kualitas (butuh --extra roi)"),
        ("train", cmd_train, "latih model"),
        ("evaluate", cmd_evaluate, "uji MFED & FFE"),
        ("benchmark", cmd_benchmark, "uji efisiensi CPU 4 thread"),
        ("gradcam", cmd_gradcam, "visualisasi Grad-CAM"),
        ("summarize", cmd_summarize, "rekap tabel perbandingan"),
        ("all", cmd_all, "jalankan semua tahap"),
    ]:
        sp = sub.add_parser(name, parents=[common], help=h)
        sp.add_argument("--force", action="store_true", help="ulang verifikasi citra (prepare)")
        sp.add_argument("--exclude-ffe-overlap", action="store_true",
                        help="buang citra FFE yang mirip/identik dengan MFED saat evaluasi")
        sp.add_argument("--n-per-class", type=int, default=4, help="jumlah citra Grad-CAM per kelas")
        sp.set_defaults(fn=fn)

    args = p.parse_args(argv)
    cfg = _cfg(args)
    args.fn(args, cfg)


if __name__ == "__main__":
    main()
