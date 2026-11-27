#!/usr/bin/env bash
# Antrean sisa: antrean utama (antrian_pelatihan.sh) terhenti 2026-11-27 ~13:10 saat K1 baseline
# ResNet50 seed 2026 (fase 2 epoch 12) tanpa sempat mencatat GAGAL. Skrip ini melanjutkan sisanya
# setelah antrean lanjutan (K14) selesai.
#   nohup scripts/antrian_sisa.sh <PID_antrian_lanjutan> &
set -u
cd "$(dirname "$0")/.."
LOG=outputs/antrian_status.log
log() { echo "$(date '+%F %T') | $*" | tee -a "$LOG"; }
run() { local name=$1; shift; local f="outputs/log_${name}.txt"; log "MULAI  $name :: $*"
  if caffeinate -i "$@" >>"$f" 2>&1; then log "SELESAI $name"; else log "GAGAL  $name (lihat $f)"; fi; }
trap 'log "ANTREAN SISA DIHENTIKAN (sinyal)"; exit 1' INT TERM HUP
EYE=configs/eye_mask.yaml
if [ -n "${1:-}" ]; then log "antrean sisa menunggu antrean lanjutan (PID $1)"; while kill -0 "$1" 2>/dev/null; do sleep 60; done; fi

# 4. K1 baseline: lengkapi seed 2026 (VGG16 sudah), lalu evaluasi seed 123 & 2026 + ringkasan 3 seed
run k1_base_2026   uv run fishfresh train --seeds 2026 --archs resnet50 inceptionv3
run k1_base_eval   uv run fishfresh evaluate --seeds 123 2026
run k1_base_cam    uv run fishfresh gradcam --seeds 123 2026
run k1_base_sum    uv run fishfresh summarize --seeds 42 123 2026
# K1 eye_mask
run k1_eye         uv run fishfresh all -c $EYE --seeds 123 2026
run k1_eye_sum     uv run fishfresh summarize -c $EYE --seeds 42 123 2026

# 5. Ablasi (seed 42)
run k2_fe          uv run fishfresh all -c configs/skenario/k2_feature_extraction.yaml
run k4_noaug       uv run fishfresh all -c configs/skenario/k4_tanpa_augmentasi.yaml
run k3_inc299      uv run fishfresh all -c configs/skenario/k3_inception299.yaml --archs inceptionv3
run k6_ft60        uv run fishfresh all -c configs/skenario/k6_finetune_dalam.yaml
run k5_cw_ls       uv run fishfresh all -c configs/skenario/k5_class_weight.yaml

# 6. K7 skala thread inferensi (model baseline seed 42)
for t in 1 2 8; do run "k7_thread$t" uv run fishfresh benchmark --set efficiency.threads=$t; done
log "K8 dilewati: mixed precision hanya untuk GPU CUDA (mesin ini CPU)"
log "ANTREAN SISA SELESAI"
