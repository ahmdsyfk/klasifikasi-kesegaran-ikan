#!/usr/bin/env bash
# Antrean lanjutan: dijalankan setelah scripts/antrian_pelatihan.sh selesai (tidak menyunting skrip yang sedang jalan).
#   nohup scripts/antrian_lanjutan.sh <PID_antrian_utama> &
set -u
cd "$(dirname "$0")/.."
LOG=outputs/antrian_status.log
log() { echo "$(date '+%F %T') | $*" | tee -a "$LOG"; }
run() { local name=$1; shift; local f="outputs/log_${name}.txt"; log "MULAI  $name :: $*"
  if caffeinate -i "$@" >>"$f" 2>&1; then log "SELESAI $name"; else log "GAGAL  $name (lihat $f)"; fi; }
if [ -n "${1:-}" ]; then log "antrean lanjutan menunggu antrean utama (PID $1)"; while kill -0 "$1" 2>/dev/null; do sleep 60; done; fi
# K14 treatment overfitting (seed 42), dibandingkan dengan baseline seed 42
run k14_reg uv run fishfresh all -c configs/skenario/k14_regularisasi.yaml
log "ANTREAN LANJUTAN SELESAI"
