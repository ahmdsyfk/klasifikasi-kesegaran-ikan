# Log Eksperimen — Bahan Bab IV

Catatan kronologis seluruh langkah, keputusan, dan angka yang dihasilkan. Setiap angka di sini
berasal dari berkas hasil di repo (`data/splits/`, `data/processed/eye_roi/`, `outputs/`).
Format: tanggal/jam (WIB) — langkah — hasil — berkas bukti.

## 0. Lingkungan

| Item | Nilai |
|---|---|
| Mesin pelatihan | Mac mini Apple M1 (8 core), RAM 16 GB, macOS |
| Akselerator | CPU (TensorFlow 2.21 di macOS tidak memakai GPU Apple) — terdeteksi otomatis oleh `platform_info.py` |
| Perangkat lunak | Python 3.13, TensorFlow 2.21.0, Keras 3.15.1 (env `uv`, lihat `uv.lock`) |
| Deteksi mata | PyTorch + transformers (OWLv2), dijalankan di GPU Apple (MPS) |
| Mixed precision | `auto` → nonaktif (hanya aktif bila GPU CUDA) |

Catatan untuk Bab IV: proposal 3.2.1 menyebut laptop RTX 4060. Pelatihan dilakukan di Mac mini M1
(CPU), sehingga **waktu pelatihan** tidak setara dengan GPU; metrik klasifikasi, jumlah parameter,
ukuran berkas, dan benchmark inferensi CPU 4 thread tetap sebanding. Kode mendeteksi platform
(macOS/Windows/Linux/WSL2) dan akselerator secara otomatis; hasil deteksi disimpan di
`outputs/<run>/seed42/<arsitektur>/train_meta.json` (kunci `platform`).

## 1. Pengumpulan data (2026-11-25)

| Dataset | Sumber | Berkas | Isi |
|---|---|---|---|
| MFED v2 | Mendeley Data 10.17632/67nmx3mhwh.2 | `Multistage Fish Eyes Dataset.zip` (3,7 GB) | 4.800 citra PNG: 1.600 Highly Fresh, 1.600 Fresh, 1.600 Not Fresh; 3.985 citra 1000×1000 px, 811 citra 500×500 px, 4 citra ±996–998 px |
| FFE v1 | Mendeley Data 10.17632/xzyx7pbr3w.1 | 24 folder `<Spesies> - <Kelas>`, diunduh per berkas dengan verifikasi checksum | **4.390 citra** (sama dengan listing API Mendeley; deskripsi dataset menyebut 4.392 — selisih 2 berasal dari sumber). 1.764 Highly Fresh, 1.320 Fresh, 1.306 Not Fresh; 8 spesies |

Observasi visual: citra MFED berupa **kepala ikan utuh** dengan latar; mata hanya ±15–25 % lebar
citra dan posisinya bervariasi. Citra FFE sebagian besar sudah **dipotong rapat ke area mata**.
Perbedaan pembingkaian ini menjadi alasan skenario "fokus mata" (bagian 4).

### Komposisi FFE per spesies

| Spesies | Highly Fresh | Fresh | Not Fresh | Total |
|---|---|---|---|---|
| Chanos chanos | 168 | 162 | 170 | 500 |
| Eleutheronema tetradactylum | 80 | 80 | 80 | 240 |
| Johnius trachycephalus | 80 | 80 | 80 | 240 |
| Nibea albiflora | 173 | 125 | 121 | 419 |
| Oreochromis mossambicus | 289 | 174 | 162 | 625 |
| Oreochromis niloticus | 328 | 231 | 246 | 805 |
| Rastrelliger faughni | 336 | 216 | 217 | 769 |
| Upeneus moluccensis | 310 | 252 | 230 | 792 |
| **Total** | **1.764** | **1.320** | **1.306** | **4.390** |

Pemeriksaan FFE: 0 berkas rusak, 0 duplikat identik (SHA-256). FFE tidak seimbang antar kelas →
metrik utama F1 macro (proposal 3.7.2).

## 2. Pemeriksaan dan pembersihan data MFED (proposal 3.5)

Perintah: `uv run fishfresh prepare`

| Pemeriksaan | Metode | Hasil MFED |
|---|---|---|
| Berkas rusak / tak terbaca | `PIL.Image.verify()` + decode ulang | 0 dari 4.800 |
| Label tidak dikenali | label dari nama folder | 0 |
| Duplikat identik | SHA-256 isi berkas | 0 |
| Konflik label | SHA-256 sama, label berbeda | 0 |
| Hampir-duplikat | pHash 64-bit, jarak Hamming ≤ 4 | 77 pasangan → 4.725 grup; 6 grup berisi label berbeda (ikan yang sama pada tahap kesegaran berbeda — wajar untuk dataset *multistage*, tidak dibuang) |

Penanganan hampir-duplikat: tidak dibuang, tetapi **dikelompokkan** sehingga satu grup selalu
berada di bagian yang sama (latih/validasi/uji) → mencegah kebocoran data.

Semua citra yang dibuang dicatat di `data/splits/data_dibuang.csv` (kolom `kriteria`,
`keterangan`); ringkasan per kriteria di `data/splits/laporan_pemeriksaan_data.json`.

## 3. Pembagian data (proposal 3.5, Tabel 3)

`StratifiedGroupKFold` 20 fold × 5 % (seed 42): 14 fold latih, 3 fold validasi, 3 fold uji.

| Bagian | Highly Fresh | Fresh | Not Fresh | Total | % |
|---|---|---|---|---|---|
| Latih | 1.120 | 1.120 | 1.120 | 3.360 | 70 % |
| Validasi | 240 | 240 | 240 | 720 | 15 % |
| Uji | 240 | 240 | 240 | 720 | 15 % |
| Total | 1.600 | 1.600 | 1.600 | 4.800 | 100 % |

Verifikasi: tidak ada grup hampir-duplikat yang terpecah lintas bagian.
Split ditetapkan **sebelum** filter kualitas (bagian 5) supaya pembagian identik untuk semua
skenario; filter hanya menghapus baris. Salinan split sebelum filter:
`data/splits/mfed_split_seed42_sebelum_filter.csv`.

## 4. Skenario fokus mata (tambahan atas proposal 3.6, keputusan pemilik 2026-11-25)

Tujuan: model hanya mempertimbangkan area mata, bukan kulit/insang/latar di sekitarnya.

1. **Lokalisasi mata zero-shot** — OWLv2 (`google/owlv2-base-patch16-ensemble`), prompt
   "a photo of a fish eye" dan "an eye"; tanpa anotasi manual. Uji awal 18 sampel: 17 kotak benar.
2. **Pemilihan kotak** — buang kandidat yang terlalu besar (MFED > 45 % luas citra = kepala;
   FFE > 98 %) atau rasio sisi di luar 0,5–2; ambil skor tertinggi; bila hanya pupil terdeteksi
   dan ada kotak pemuat (≤ 4× luas, skor ≥ 0,5× terbaik), kotak pemuat dipakai agar iris tercakup.
3. **Crop + masker** — crop persegi berpusat di mata, sisi = max(w, h) × 1,10; resize 224×224
   (LANCZOS); piksel di luar lingkaran yang menyinggung tepi persegi diisi hitam (RGB 0).
4. **Masker ulang setelah augmentasi** (lapisan `CircularMask`) agar noise/kecerahan tidak
   menghidupkan kembali area luar mata.
5. **Validasi fokus** — Grad-CAM: persentase energi heatmap di dalam area mata
   (`gradcam/atensi_mata.json`), dibandingkan dengan baseline.

Spesifikasi crop yang sama dipakai aplikasi Android (sesi pengembangan aplikasi) agar praproses di
perangkat identik.

## 5. Filter citra tidak berguna

Kriteria (dicatat per citra di `data_dibuang.csv`):

| Kriteria | Ukuran | Ambang |
|---|---|---|
| rusak_tidak_terbaca | gagal decode | — |
| label_tidak_dikenali | nama folder | — |
| konflik_label | SHA-256 | — |
| duplikat_identik | SHA-256 | — |
| mata_tidak_terdeteksi | tidak ada kotak valid / skor OWLv2 | skor ≥ 0 (tidak dibatasi; lihat kalibrasi) |
| mata_terlalu_kecil | sisi kotak mata (px citra asli) | < 48 px |
| buram | variansi Laplacian area mata (crop 224 px, tepi masker dikecualikan) | < 30 |
| terlalu_gelap / terlalu_terang | rata-rata kecerahan (grayscale) area mata | < 15 / > 235 |
| over/under_exposure | fraksi piksel jenuh (≥ 250 / ≤ 5) di area mata | > 50 % |

### Kalibrasi ambang (2026-11-25, 2.800 citra MFED pertama yang sudah terdeteksi)

Distribusi metrik (persentil 0 / 1 / 5 / 50 / 99 / 100):

| Metrik | p0 | p1 | p5 | p50 | p99 | p100 |
|---|---|---|---|---|---|---|
| skor deteksi OWLv2 | 0,040 | 0,096 | 0,166 | 0,355 | 0,612 | 0,761 |
| sisi mata (px) | 59 | 115 | 131 | 257 | 458 | 678 |
| ketajaman (var. Laplacian) | 19 | 47 | 96 | 931 | 11.694 | 17.318 |
| kecerahan rata-rata | 21,5 | 33,0 | 40,3 | 75,8 | 134,6 | 156,1 |
| fraksi piksel jenuh putih | 0 | 0 | 0 | 0,007 | 0,096 | 0,186 |

Pemeriksaan visual grid QA (kotak terpilih + hasil crop):
- **Skor deteksi terendah** (16 citra, skor 0,04–0,08): semuanya tepat di mata → skor tidak dipakai
  sebagai filter untuk MFED.
- **Kotak terbesar**: 1 citra kotaknya mencakup seluruh kepala (43 % luas citra, sisi 678 px);
  kotak mata benar terbesar = 33 % (citra 500 px yang diambil dekat). Batas luas kotak MFED
  diturunkan 45 % → 38 %; citra tersebut **tidak dibuang** melainkan otomatis beralih ke kandidat
  berikutnya yang tepat di mata (skor 0,38, sisi 216 px).
- **Paling buram** (var. Laplacian 19–39): kabur karena gerak/fokus; 2 di antaranya kotak hanya
  menangkap pupil. Ambang < 30 membuang ±0,3 % citra.
- **Paling gelap** (kecerahan 22–29): mata masih jelas terlihat → ambang gelap dibuat sangat rendah
  (< 15) agar hanya citra nyaris hitam yang dibuang.

*Jumlah final citra yang dibuang per kriteria dicatat setelah deteksi seluruh MFED dan FFE selesai.*

## 6. Augmentasi (proposal 2.7, Gambar 4)

Hanya pada data latih, secara *online* (tiap epoch transformasi acak baru; jumlah berkas tidak
bertambah). Urutan: flip horizontal → rotasi ±15° (reflect) → perspektif (skala 0,1) →
kecerahan ±10 % → gamma 0,8–1,2 → noise Gaussian σ = 0,03 → (mode fokus mata) masker lingkaran.
Catatan lengkap per model: `outputs/<run>/seed42/<arsitektur>/augmentasi.md` dan
`contoh_augmentasi.png`.

## 7. Pelatihan

Hiperparameter (sama untuk ketiga arsitektur): input 224×224, bobot ImageNet, head
GAP → Dropout 0,5 → Dense 256 ReLU → Dropout 0,5 → Dense 3 softmax; Adam; batch 32.
- Fase 1 (feature extraction): backbone beku, lr 1e-3, maks 30 epoch.
- Fase 2 (fine-tuning): 30 % lapisan teratas dibuka (BatchNorm tetap beku), lr 1e-5, maks 30 epoch.
- Early stopping: pantau `val_loss`, patience 7, bobot terbaik dikembalikan;
  ReduceLROnPlateau faktor 0,2 patience 3; checkpoint terbaik per `val_loss`.
- Waktu pelatihan diukur dari awal epoch pertama sampai pelatihan berhenti (proposal 3.7.3).

Estimasi kecepatan di M1 (diukur 2026-11-25, batch 32, saat proses lain berjalan):

| Arsitektur | Fase 1 (s/batch) | Fase 2 (s/batch) | ±menit/epoch (F1 / F2) |
|---|---|---|---|
| VGG16 | 4,37 | 4,96 | 9 / 10 |
| ResNet50 | 1,02 | 1,90 | 2,5 / 4 |
| InceptionV3 | 0,70 | 1,02 | 1,6 / 2,2 |

### Desain data per skenario

- **Keluarga baseline** (baseline, K1–K6): prosedur proposal — cek berkas + deduplikasi, tanpa
  filter kualitas mata. Split: `data/splits/`.
- **Keluarga fokus mata** (eye_mask, K1 eye_mask): crop mata + masker + filter kualitas.
  Split: `data/splits_eye/` = split baseline (seed sama, ditetapkan sebelum filter) dikurangi citra
  yang tersaring → tidak ada citra yang berpindah bagian.
- **Pembanding adil**: setiap model juga dievaluasi pada **subset bersama** (citra uji MFED & FFE
  yang lolos filter) → `metrics_mfed_test_subset.json`, `metrics_ffe_subset.json`.
- Benchmark efisiensi selalu dijalankan saat tidak ada pelatihan lain.

### Antrean otomatis (`scripts/antrian_pelatihan.sh`, status: `outputs/antrian_status.log`)

| # | Skenario | Isi | Estimasi durasi (M1 CPU) |
|---|---|---|---|
| 1 | baseline (S1) | InceptionV3 → ResNet50 → VGG16, seed 42 — **mulai 2026-11-25 22:25** | ±10 jam |
| 2 | ROI + prepare | crop mata, filter, split seed 42/123/2026 | ±0,5 jam |
| 3 | baseline S2–S4 | evaluasi MFED+FFE, benchmark CPU 4 thread, Grad-CAM, ringkasan | ±1 jam |
| 4 | eye_mask | 3 arsitektur + evaluasi/benchmark/Grad-CAM | ±10 jam |
| 5 | K1 multi-seed | seed 123 & 2026 untuk baseline dan eye_mask (12 model) | ±40 jam |
| 6 | K2 feature extraction | fase 1 saja | ±4 jam |
| 7 | K4 tanpa augmentasi | ablasi augmentasi | ±8 jam |
| 8 | K3 InceptionV3 299×299 | resolusi asli Inception | ±3 jam |
| 9 | K6 fine-tuning 60 % | sensitivitas lapisan yang dibuka | ±11 jam |
| 10 | K5 class weight + label smoothing | | ±10 jam |
| 11 | K7 skala thread 1/2/8 | benchmark saja | ±1 jam |
| — | K8 mixed precision | **dilewati**: hanya bermakna di GPU CUDA; di CPU dimatikan otomatis (hasil = baseline) | — |
| — | K9 FFE tanpa tumpang-tindih, K10 per spesies | analisis dari `pred_ffe.csv` + `per_species` (tanpa latih ulang) | — |
| — | K11–K13 | saran penelitian lanjutan (TFLite diekspor oleh modul aplikasi) | — |

Perkiraan total ±100 jam (±4–5 hari). Urutan mengikuti prioritas di `docs/skenario_eksperimen.md`
(★★★ dulu). Waktu aktual dicatat per tahap di `outputs/antrian_status.log`.

## 7b. Temuan & Intervensi selama eksperimen

| Waktu | Temuan | Tindakan |
|---|---|---|
| 2026-11-25 22:50 | OWLv2 memilih kotak seluruh kepala pada 1 citra MFED (43 % luas) | batas luas kotak MFED 45 % → 38 %; citra otomatis beralih ke kotak mata yang benar |
| 2026-11-25 22:50 | 2 citra buram: kotak hanya menangkap pupil | tersaring oleh kriteria `buram` (var. Laplacian < 30) |
| 2026-11-25 23:25 | Deteksi mata MFED selesai: 4.800/4.800 citra punya kandidat kotak (1 j 20 m di MPS). Rantai deteksi FFE macet karena skrip penunggu mendeteksi dirinya sendiri sebagai proses MFED | rantai dihentikan, deteksi FFE dijalankan langsung |
| 2026-11-25 23:05 | Pelatihan & ROI berjalan bersamaan; menulis ulang split saat ResNet50/VGG16 baseline belum mulai berisiko data latih tidak konsisten | split eye_mask dipisah ke `data/splits_eye/`; `prepare` baseline dijalankan setelah pelatihan baseline selesai |

## 8. Hasil

*Diisi otomatis dari `outputs/<run>/ringkasan.csv` setelah evaluasi.*

### 8.1 Pelatihan baseline (seed 42)

| Arsitektur | Epoch fase 1 | Epoch fase 2 (ES) | Epoch terbaik fase 2 | val_loss terbaik | val_acc terbaik | Waktu latih | Rata-rata/epoch | Param total / dilatih akhir | Ukuran `.keras` |
|---|---|---|---|---|---|---|---|---|---|
| InceptionV3 | 30 (tanpa ES, val_loss 0,755, val_acc 63,6 %) | 17 (berhenti dini) | 10 | 0,500 | 78,8 % | 89,4 menit | 114 s | 22,33 jt / 13,73 jt (26 lapisan berbobot dibuka) | 86,2 MB |
| ResNet50 | 22 (berhenti dini; terbaik epoch 15: val_loss 0,613, val_acc 72,1 %) | 23 (berhenti dini) | 16 | 0,274 | 90,7 % | 109,9 menit | 146 s | 24,11 jt / 17,71 jt (16 lapisan berbobot dibuka) | 92,6 MB |

Catatan: fase 1 (backbone beku) masih turun pelan sampai epoch 30; fine-tuning menaikkan val_acc ±15 poin.
Akurasi latih fase 2 (85–90 %) > validasi (±78 %) → indikasi overfitting ringan yang dihentikan early stopping.
ResNet50: fine-tuning menaikkan val_acc dari 72 % → 90,7 % (akurasi latih 95 %); sejauh ini arsitektur terbaik di validasi.
