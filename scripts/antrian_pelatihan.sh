#!/usr/bin/env bash
# Antrean seluruh skenario pelatihan (docs/skenario_eksperimen.md), dijalankan berurutan.
# Setiap tahap dicatat di outputs/antrian_status.log; kegagalan satu skenario tidak menghentikan antrean.
# Benchmark efisiensi selalu dijalankan saat tidak ada pelatihan lain (latensi valid).
#
#   nohup scripts/antrian_pelatihan.sh [PID_baseline_yang_sedang_jalan] &
set -u
cd "$(dirname "$0")/.."
LOG=outputs/antrian_status.log
mkdir -p outputs
log() { echo "$(date '+%F %T') | $*" | tee -a "$LOG"; }
run() {  # run <nama> <perintah...>
  local name=$1; shift
  local f="outputs/log_${name}.txt"
  log "MULAI  $name :: $*"
  if caffeinate -i "$@" >>"$f" 2>&1; then log "SELESAI $name"; else log "GAGAL  $name (lihat $f)"; fi
}
EYE=configs/eye_mask.yaml
SEEDS_EXTRA="123 2026"

# 0. Tunggu pelatihan baseline yang sedang berjalan & deteksi mata
if [ -n "${1:-}" ]; then
  log "menunggu pelatihan baseline (PID $1)"
  while kill -0 "$1" 2>/dev/null; do sleep 60; done
fi
while pgrep -f "det_zip.py|eye_roi import run_detection" >/dev/null; do sleep 60; done
log "deteksi mata selesai"

# 1. ROI mata + prepare (baseline: tanpa filter; eye_mask: dengan filter). Split identik per seed.
run roi            uv run --extra roi fishfresh roi -c $EYE
run prepare_base   uv run fishfresh prepare --seeds 42 $SEEDS_EXTRA
run prepare_eye    uv run fishfresh prepare -c $EYE --seeds 42 $SEEDS_EXTRA
uv run python - <<'PY' >>"$LOG" 2>&1
import pandas as pd
a = pd.read_csv("data/splits/mfed_split_seed42_sebelum_filter.csv")
b = pd.read_csv("data/splits/mfed_split_seed42.csv")
same = a[["sha256", "split"]].merge(b[["sha256", "split"]], on="sha256")
print(f"cek split baseline: {len(a)} vs {len(b)} baris, assignment sama = {(same.split_x == same.split_y).all()}")
PY

# 2. Skenario utama baseline: evaluasi MFED+FFE, benchmark, Grad-CAM, ringkasan
run base_eval      uv run fishfresh evaluate
run base_bench     uv run fishfresh benchmark
run base_gradcam   uv run fishfresh gradcam
run base_summary   uv run fishfresh summarize

# 3. Skenario utama fokus mata (eye_mask)
run eye_all        uv run fishfresh all -c $EYE --seeds 42

# 4. K1 multi-seed (stabilitas, mean +- std) untuk kedua skenario utama
run k1_base        uv run fishfresh all --seeds $SEEDS_EXTRA
run k1_base_sum    uv run fishfresh summarize --seeds 42 $SEEDS_EXTRA
run k1_eye         uv run fishfresh all -c $EYE --seeds $SEEDS_EXTRA
run k1_eye_sum     uv run fishfresh summarize -c $EYE --seeds 42 $SEEDS_EXTRA

# 5. Ablasi (seed 42)
run k2_fe          uv run fishfresh all -c configs/skenario/k2_feature_extraction.yaml
run k4_noaug       uv run fishfresh all -c configs/skenario/k4_tanpa_augmentasi.yaml
run k3_inc299      uv run fishfresh all -c configs/skenario/k3_inception299.yaml --archs inceptionv3
run k6_ft60        uv run fishfresh all -c configs/skenario/k6_finetune_dalam.yaml
run k5_cw_ls       uv run fishfresh all -c configs/skenario/k5_class_weight.yaml

# 6. K7 skala thread inferensi (model baseline seed 42)
for t in 1 2 8; do run "k7_thread$t" uv run fishfresh benchmark --set efficiency.threads=$t; done

log "ANTREAN SELESAI"
