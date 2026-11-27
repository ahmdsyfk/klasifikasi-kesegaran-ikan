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

### Jumlah final citra dibuang (2026-11-26 08:46, `prepare -c configs/eye_mask.yaml`)

Deteksi OWLv2 selesai untuk 9.190 citra unik (MFED 4.800 + FFE 4.390). Sebanyak 33 citra FFE tidak menghasilkan
kotak sama sekali.

| Kriteria | MFED | FFE |
|---|---|---|
| mata_tidak_terdeteksi | 0 | 39 |
| mata_terlalu_kecil | 0 | 3 |
| buram | 16 | 54 |
| mata_terlalu_kecil + buram | 0 | 21 |
| terlalu_gelap/terlalu_terang/exposure | 0 | 0 |
| **Total dibuang** | **16 (0,33 %)** | **117 (2,67 %)** |
| Sisa | 4.784 | 4.273 |

Skenario baseline mengikuti proposal tanpa filter kualitas: MFED 4.800 dan FFE 4.390 citra utuh. Tidak ada duplikat
identik maupun hampir-duplikat antara MFED dan FFE. Split MFED eye_mask seed 42 setelah filter: latih 3.351, validasi
718, uji 715, dengan kelas tetap seimbang (±1/3). Pemeriksaan otomatis memastikan assignment split baseline identik
dengan split sebelum filter (4.800 = 4.800 baris, sama = True), jadi data eye_mask adalah himpunan bagian dari split
baseline.
FFE yang dipakai eye_mask: 4.273 citra (Sangat Segar 1.717, Segar 1.278, Tidak Segar 1.278).

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
| — | K8 mixed precision | **dihapus** (27 Nov 2026, keputusan pemilik): skenario khusus GPU RTX/CUDA, tidak relevan untuk pelatihan di CPU | — |
| — | K9 FFE tanpa tumpang-tindih, K10 per spesies | analisis dari `pred_ffe.csv` (tanpa latih ulang), `scripts/analisis_k9_k10.py`; hasil di 8.8 | — |
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
| 2026-11-26 09:13 | F1 lintas dataset FFE baseline hanya 0,32–0,37, mendekati tebakan acak (0,33). Dicek: pemetaan folder FFE ke label benar (crosstab 1:1), uji MFED wajar (80–91 %, tanpa indikasi bocor). | Bukan bug. Ini temuan domain shift (spesies, kamera, dan latar FFE berbeda dari MFED). Dicatat sebagai hasil utama generalisasi. Skenario eye_mask menguji apakah fokus ke mata mengurangi penurunan ini. |
| 2026-11-26 14:20 | VGG16 eye_mask fase 1 berhenti dini di epoch 8. Loss latih epoch 1 sangat besar (5,11): kepala klasifikasi baru menerima fitur VGG16 tanpa normalisasi. val_loss epoch 1 (0,965) kebetulan menjadi yang terendah, sehingga ES patience 7 mengembalikan bobot epoch 1 (val_acc 51 %) sebagai awal fine-tuning. Di baseline, fase 1 VGG16 berjalan penuh 30 epoch. | Tidak diulang sekarang, karena protokol ES identik di kedua skenario dan fase 2 tetap konvergen (85,5 %). Dicatat sebagai faktor pengganggu perbandingan VGG16 baseline vs eye_mask. Opsi lanjutan (bila waktu cukup): ulang VGG16 eye_mask dengan `EarlyStopping(start_from_epoch=3)` di fase 1 untuk kedua skenario. |
| 2026-11-26 17:20 | Latensi eye_mask 1,6–1,7× lebih cepat daripada baseline untuk model dan input yang sama. Benchmark baseline (09:13) tidak stabil antar ulangan (ResNet50 61,5 / 77,6 / 75,9 ms), sedangkan eye_mask stabil (±0,1 ms). Benchmark baseline terganggu beban lain di mesin. | Pelatihan K1 dijeda (SIGSTOP) selama 333 s, lalu benchmark baseline dan eye_mask diulang berurutan di mesin kosong. Hasilnya identik (selisih < 0,1 ms; VGG16 122,6, ResNet50 41,7, InceptionV3 36,7 ms). Ringkasan dibuat ulang. Catatan: `train_time` K1 seed 123 VGG16 bertambah ±5,5 menit karena jeda ini. |
| 2026-11-26 20:20 | Epoch K1 VGG16 seed 123 melambat dari ±355 s menjadi 572 s. Load average mesin 48–60 karena proyek lain pemilik (`~/Proyek/wt-keamanan2`: server API dan uji Playwright/Chromium headless) serta OrbStack berjalan bersamaan. | Proses milik proyek lain tidak dihentikan. Waktu latih K1 (seed 123/2026) dianggap tidak sebanding dengan seed 42 dan hanya dilaporkan sebagai indikasi. Efisiensi resmi tetap memakai benchmark seed 42 di mesin kosong (8.3). Benchmark K1 diulang di mesin kosong bila diperlukan. |
| 2026-11-27 03:20 | ResNet50 baseline seed 123 hanya mencapai val_acc 79,2 % (seed 42: 90,7 %). Pada fase 2, loss latih turun 0,62 → 0,20, tetapi val_loss berosilasi naik (0,50 → 0,77) sejak epoch 7, sehingga ES berhenti di epoch 13. Ini overfitting cepat dan sensitif terhadap seed. Dugaan: lapisan BatchNorm ResNet50 yang ikut dibuka saat fine-tuning (lr 1e-5, batch 32) membuat statistik BN bergeser. | Tidak diintervensi. Variasi antar seed adalah tujuan K1, dan protokol harus sama untuk semua seed. Dicatat untuk pembahasan stabilitas (mean ± std). Saran perbaikan untuk penelitian lanjutan: bekukan BN saat fine-tuning. |
| 2026-11-27 11:30 | Overfitting ringan di fase 2 K1: VGG16 seed 2026 akurasi latih 92,8 % vs validasi 86,0 % (val_loss terbaik 0,317 di epoch 19, lalu naik ke 0,34–0,36); ResNet50 seed 123 val_acc 79,2 %. BatchNorm ternyata sudah beku saat fine-tuning (`set_backbone_trainable`), sehingga dugaan BN pada baris 03:20 gugur. | K1 **tidak diubah** (harus memakai konfigurasi yang sama dengan seed 42 agar mean ± std sah); early stopping + restore_best_weights sudah menjaga bobot terbaik. Ditambahkan skenario **K14 treatment** (`configs/skenario/k14_regularisasi.yaml`: augmentasi lebih kuat, label smoothing 0,1, AdamW weight decay 0,05; `train.weight_decay` kini bisa diatur) dan dijadwalkan lewat `scripts/antrian_lanjutan.sh` setelah antrean utama selesai. |

## 8. Hasil

*Diisi otomatis dari `outputs/<run>/ringkasan.csv` setelah evaluasi.*

### 8.1 Pelatihan baseline (seed 42)

| Arsitektur | Epoch fase 1 | Epoch fase 2 (ES) | Epoch terbaik fase 2 | val_loss terbaik | val_acc terbaik | Waktu latih | Rata-rata/epoch | Param total / dilatih akhir | Ukuran `.keras` |
|---|---|---|---|---|---|---|---|---|---|
| InceptionV3 | 30 (tanpa ES, val_loss 0,755, val_acc 63,6 %) | 17 (berhenti dini) | 10 | 0,500 | 78,8 % | 89,4 menit | 114 s | 22,33 jt / 13,73 jt (26 lapisan berbobot dibuka) | 86,2 MB |
| ResNet50 | 22 (berhenti dini; terbaik epoch 15: val_loss 0,613, val_acc 72,1 %) | 23 (berhenti dini) | 16 | 0,274 | 90,7 % | 109,9 menit | 146 s | 24,11 jt / 17,71 jt (16 lapisan berbobot dibuka) | 92,6 MB |
| VGG16 | 30 (tanpa ES; val_loss 0,906, val_acc 65,6 %) | 30 (tanpa ES, batas maks.) | 29 | 0,229 | 91,5 % | 419,1 menit | 419 s | 14,85 jt / 9,57 jt (4 lapisan berbobot dibuka) | 56,7 MB |


Catatan: fase 1 (backbone beku) masih turun pelan sampai epoch 30; fine-tuning menaikkan val_acc ±15 poin.
Akurasi latih fase 2 (85–90 %) > validasi (±78 %) → indikasi overfitting ringan yang dihentikan early stopping.
ResNet50: fine-tuning menaikkan val_acc dari 72 % → 90,7 % (akurasi latih 95 %); sejauh ini arsitektur terbaik di validasi.
VGG16: fase 1 (beku) paling lemah (65,6 %), tetapi fine-tuning menaikkan val_acc ke 91,5 % (setara ResNet50). Fase 2 mencapai batas 30 epoch dengan val_loss masih turun (terbaik epoch 29), jadi VGG16 kemungkinan belum konvergen penuh. Ini keterbatasan batas epoch proposal, bukan kegagalan. Waktu latih VGG16 4,7× InceptionV3 di CPU M1.

### 8.2 Evaluasi baseline seed 42 (2026-11-26 09:13): uji MFED dan lintas dataset FFE tanpa pelatihan ulang

| Arsitektur | MFED Acc | MFED P | MFED R | MFED F1 | FFE Acc | FFE F1 | ΔF1 | Penurunan relatif |
|---|---|---|---|---|---|---|---|---|
| VGG16 | 90,69 % | 0,907 | 0,907 | **0,906** | 38,77 % | 0,339 | 0,567 | 62,6 % |
| ResNet50 | 89,58 % | 0,898 | 0,896 | 0,895 | 40,75 % | **0,365** | 0,530 | 59,2 % |
| InceptionV3 | 80,42 % | 0,806 | 0,804 | 0,800 | 34,15 % | 0,315 | **0,485** | 60,6 % |

Uji MFED: n = 720. Uji FFE: n = 4.390. P, R, dan F1 memakai rata-rata makro.
Subset bersama, yaitu citra yang juga lolos filter eye_mask (MFED 715, FFE 4.273), memberi angka hampir identik
(selisih F1 ≤ 0,003) sehingga bisa dipakai sebagai pembanding adil terhadap eye_mask:
- VGG16: 0,905 / 0,338
- ResNet50: 0,897 / 0,365
- InceptionV3: 0,803 / 0,316

Confusion matrix FFE ResNet50 (baris = label sebenarnya [Sangat Segar, Segar, Tidak Segar]):
[[1050, 566, 148], [605, 567, 148], [553, 581, 172]]. Model cenderung memprediksi "Sangat Segar" dan "Segar".
Recall "Tidak Segar" hanya 13 %.

### 8.3 Efisiensi baseline (CPU Apple M1, 4 thread, batch 1, 200 citra × 3 ulangan, warm-up 10)

| Arsitektur | Parameter | Ukuran `.keras` | Latensi rata-rata | p95 | Throughput | Memori puncak (RSS) | Δ memori model |
|---|---|---|---|---|---|---|---|
| VGG16 | 14,85 jt | 56,7 MB | 122,6 ms | 126,6 ms | 8,2 citra/s | 806 MB | 158 MB |
| ResNet50 | 24,11 jt | 92,6 MB | 41,7 ms | 42,9 ms | 24,0 citra/s | 791 MB | 260 MB |
| InceptionV3 | 22,33 jt | 86,2 MB | **36,7 ms** | 38,2 ms | 27,2 citra/s | 810 MB | 260 MB |

*Diperbarui 2026-11-26 17:30. Benchmark pertama (09:13) terganggu beban lain di mesin (latensi 1,6–1,7× lebih lambat,
ulangan tidak stabil) dan diulang saat mesin kosong; lihat 7b. Data lama disimpan sebagai `efficiency_kontaminasi_1126.json`.*

Ringkasan: VGG16 memiliki F1 MFED tertinggi, tetapi 2,9× lebih lambat dari ResNet50 dan 3,3× dari InceptionV3. ResNet50
memberi keseimbangan terbaik (F1 MFED 0,895, F1 FFE tertinggi, latensi 41,7 ms). InceptionV3 paling cepat, tetapi
akurasinya paling rendah. Memori puncak proses hampir sama (≈ 0,8 GB) karena didominasi runtime TensorFlow. Selisih
memori yang ditambahkan model: VGG16 158 MB, ResNet50/InceptionV3 260 MB.

### 8.4 Grad-CAM dan uji McNemar baseline seed 42 (2026-11-26 09:27)

Atensi Grad-CAM di area mata dihitung pada 300 citra uji MFED. Area mata berbentuk elips dari kotak OWLv2, dan
rata-rata hanya mencakup 8,8 % luas citra. Rasio fokus = porsi energi CAM di mata ÷ porsi luas mata; nilai 1
berarti atensi tersebar merata.

| Arsitektur | Atensi di mata (rata-rata) | Median | Rasio fokus | Sangat Segar | Segar | Tidak Segar |
|---|---|---|---|---|---|---|
| VGG16 | 25,8 % | 22,9 % | 2,94× | 26,8 % | 29,7 % | 20,5 % |
| ResNet50 | 22,8 % | 19,9 % | 2,60× | 23,5 % | 20,3 % | 24,4 % |
| InceptionV3 | 15,1 % | 12,9 % | 1,72× | 13,4 % | 15,1 % | 17,1 % |

Tafsiran: ketiga model memang lebih memperhatikan mata daripada rata-rata area lain (rasio > 1). Namun 74–85 %
atensi tetap jatuh di luar mata, misalnya di kulit, insang, dan latar. Ini mendukung dugaan bahwa model baseline
memanfaatkan petunjuk non-mata khas MFED, yang tidak berlaku di FFE (domain shift, lihat 8.2). Skenario eye_mask
menguji hal ini.

Uji McNemar berpasangan pada citra uji yang sama (α = 0,05):

| Pasangan | Uji | A benar, B salah | A salah, B benar | p | Signifikan |
|---|---|---|---|---|---|
| InceptionV3 vs ResNet50 | MFED | 37 | 103 | 2,2·10⁻⁸ | ya (ResNet50 lebih baik) |
| InceptionV3 vs VGG16 | MFED | 27 | 101 | 3,0·10⁻¹¹ | ya (VGG16 lebih baik) |
| ResNet50 vs VGG16 | MFED | 41 | 49 | 0,461 | **tidak**, setara |
| InceptionV3 vs ResNet50 | FFE | 566 | 856 | 1,5·10⁻¹⁴ | ya (ResNet50 lebih baik) |
| InceptionV3 vs VGG16 | FFE | 772 | 975 | 1,3·10⁻⁶ | ya (VGG16 lebih baik) |
| ResNet50 vs VGG16 | FFE | 688 | 601 | 0,017 | ya (ResNet50 lebih baik) |

Kesimpulan baseline: di MFED, perbedaan VGG16 dan ResNet50 tidak signifikan. Di FFE, ResNet50 signifikan lebih baik,
sementara latensinya 2,7× lebih cepat dari VGG16. File: `outputs/baseline/ringkasan.csv`, `uji_mcnemar.csv`,
`seed42/<arsitektur>/gradcam/`.

### 8.5 Pelatihan eye_mask seed 42

Data latih 3.351 citra, validasi 718 (split baseline dikurangi citra yang tersaring).

| Arsitektur | Epoch fase 1 | Epoch fase 2 | Epoch terbaik fase 2 | val_loss terbaik | val_acc terbaik | Waktu latih | Rata-rata/epoch | Ukuran |
|---|---|---|---|---|---|---|---|---|
| VGG16 | 8 (ES; terbaik epoch 1, val_acc 51,3 %) | 30 (batas maks., tanpa ES) | 27 | 0,354 | 85,5 % | 281,1 menit | 444 s | 56,7 MB |
| ResNet50 | 30 (tanpa ES; terbaik epoch 29, val_loss 0,610, val_acc 73,8 %) | 22 (ES) | 15 | 0,358 | 85,0 % | 115,5 menit | 133 s | 92,6 MB |
| InceptionV3 | 17 (ES; terbaik epoch 10) | 13 (ES) | 6 | 0,647 | 70,2 % | 44,4 menit | 89 s | 86,2 MB |

Dibanding baseline VGG16 (val_acc 91,5 %), versi eye_mask 6 poin lebih rendah di validasi MFED.
ResNet50 eye_mask: 85,0 % vs baseline 90,7 % (−5,7 poin). Fase 1 stabil (bukan masalah ES seperti VGG16). Akurasi latih fase 2 93 % → celah overfitting lebih besar daripada baseline. Dugaan: crop mata saja memuat lebih sedikit petunjuk khas MFED, sehingga akurasi in-domain turun. Pengaruhnya pada FFE akan terlihat di evaluasi.


### 8.6 Evaluasi eye_mask seed 42 dan perbandingan dengan baseline (2026-11-26 17:07)

Perbandingan adil memakai citra uji yang sama (subset bersama: MFED 715, FFE 4.273).

| Arsitektur | F1 MFED baseline | F1 MFED eye_mask | Δ | F1 FFE baseline | F1 FFE eye_mask | Δ | ΔF1 (MFED−FFE) baseline → eye |
|---|---|---|---|---|---|---|---|
| VGG16 | 0,905 | 0,838 | −0,067 | 0,338 | 0,350 | +0,012 | 0,567 → 0,488 |
| ResNet50 | 0,897 | 0,826 | −0,071 | 0,365 | 0,313 | −0,052 | 0,530 → 0,513 |
| InceptionV3 | 0,803 | 0,736 | −0,067 | 0,316 | 0,336 | +0,020 | 0,485 → 0,401 |

Accuracy eye_mask: MFED 83,8 / 82,9 / 73,9 %, FFE 36,5 / 36,8 / 34,4 % (VGG16 / ResNet50 / InceptionV3).

Recall per kelas di FFE (Sangat Segar / Segar / Tidak Segar):
- VGG16: 16 / 62 / 38 %
- ResNet50: 53 / **6** / 46 %
- InceptionV3: 20 / 57 / 32 %

ResNet50 eye_mask hampir tidak pernah memprediksi "Segar" di FFE.

Grad-CAM eye_mask: lingkaran masker mencakup 78,6 % luas citra.

| Arsitektur | Atensi di area mata | Rasio fokus |
|---|---|---|
| VGG16 | 96,7 % | 1,23× |
| ResNet50 | 92,5 % | 1,18× |
| InceptionV3 | 85,0 % | 1,08× |

Model memang hanya bisa melihat mata.

Uji McNemar eye_mask:
- MFED: ResNet50 vs VGG16 tidak signifikan (p = 0,67). InceptionV3 kalah signifikan dari keduanya (p < 10⁻⁶).
- FFE: ResNet50 vs VGG16 tidak signifikan (p = 0,76). InceptionV3 berbeda signifikan dari keduanya (p ≈ 0,01–0,015).

Efisiensi identik dengan baseline (arsitektur dan input 224 px sama): VGG16 122,5 ms, ResNet50 41,7 ms,
InceptionV3 36,7 ms.

**Kesimpulan sementara (seed 42).**
- Memaksa model hanya melihat mata menurunkan F1 in-domain MFED sekitar 0,07 di ketiga arsitektur. Sebagian petunjuk
  yang dipakai baseline memang berasal dari luar mata, misalnya kulit, insang, atau latar.
- Fokus ke mata **tidak** memperbaiki generalisasi ke FFE secara berarti. F1 FFE tetap di sekitar tebakan acak
  (0,31–0,35) dan perubahannya tidak konsisten antar arsitektur (+0,01, −0,05, +0,02). ΔF1 mengecil terutama karena
  F1 MFED turun.
- Jadi penyebab utama domain shift tidak hanya latar, tetapi juga perbedaan tampilan mata antar spesies, kamera, dan
  pencahayaan. Stabilitasnya akan diuji di K1 (seed 123 dan 2026).

### 8.7 K1 multi-seed: pelatihan (seed 123 dan 2026)

Waktu latih K1 tidak sebanding dengan seed 42 karena mesin ikut dibebani proyek lain (lihat 7b).

| Skenario | Seed | Arsitektur | Epoch F1/F2 | Epoch terbaik F2 | val_loss | val_acc | Waktu latih |
|---|---|---|---|---|---|---|---|
| baseline | 123 | VGG16 | 30 / 30 (tanpa ES) | 26 | 0,291 | 88,9 % | 531,6 menit* |
| baseline | 123 | ResNet50 | 21 / 13 (ES di kedua fase) | 6 | 0,499 | 79,2 % | 76,8 menit |
| baseline | 123 | InceptionV3 | 30 / 17 (ES di F2) | 10 | 0,549 | 75,7 % | 70,3 menit |
| baseline | 2026 | VGG16 | 30 / 26 (ES di F2) | 19 | 0,317 | 86,7 % | 431,3 menit* |

\* waktu latih tidak sebanding dengan seed 42 (beban mesin).

**Gangguan antrean (2026-11-27 ~13:10).** Antrean utama (`scripts/antrian_pelatihan.sh`) berhenti tanpa sempat mencatat
GAGAL ketika K1 baseline ResNet50 seed 2026 berada di fase 2 epoch 12 (val_acc 82,6 %, val_loss 0,464; fase 1 26 epoch
dengan ES). Penyebab tidak tercatat. Mesin saat itu sedang sangat sibuk (load average ≈ 25 karena proyek lain).
Antrean lanjutan langsung menjalankan K14 (13:13). Sisa antrean utama dipindahkan ke `scripts/antrian_sisa.sh` dan
berjalan setelah K14 selesai. ResNet50 dan InceptionV3 seed 2026 dilatih ulang dari awal dengan seed yang sama,
lalu dilanjutkan dengan evaluasi K1, K1 eye_mask, ablasi K2–K6, dan K7.

### 8.8 K9 dan K10: analisis tanpa latih ulang (2026-11-27)

Skrip: `scripts/analisis_k9_k10.py` → `outputs/k9_overlap_ffe.csv`, `outputs/k10_spesies.csv`,
`outputs/k10_per_spesies.csv`. Hanya memakai prediksi yang sudah ada (`pred_ffe.csv`), jadi tidak mengganggu antrean.

**K9 tumpang-tindih FFE–MFED.** Tidak ada citra FFE yang identik (SHA-256) maupun hampir identik (pHash Hamming ≤ 4,
ambang `prepare`) dengan MFED. Uji sensitivitas jarak Hamming minimum tiap citra FFE ke seluruh MFED:

| Ambang Hamming | Citra FFE di bawah ambang (dari 4.390) |
|---|---|
| 0 / 4 | 0 / 0 |
| 8 | 3 |
| 10 | 27 |
| 12 | 234 |

Jarak minimum 8, median 16. Jarak 8–12 pada pHash 64-bit lazim untuk foto berbeda dengan komposisi mirip, bukan
duplikat. Jadi `evaluate --exclude-ffe-overlap` identik dengan evaluasi FFE biasa, dan risiko R2 (hasil FFE terlalu
optimistis karena citra bocor) **tidak terjadi**.

**K10 spesies terlihat vs tak terlihat (seed 42).** "Terlihat" = nila dan mujair (MFED berisi dua spesies air tawar);
"tak terlihat" = 6 spesies laut.

| Skenario | Arsitektur | F1 FFE semua | F1 terlihat | F1 tak terlihat | Selisih |
|---|---|---|---|---|---|
| baseline | VGG16 | 0,339 | 0,400 | 0,298 | +0,102 |
| baseline | ResNet50 | 0,365 | 0,402 | 0,348 | +0,053 |
| baseline | InceptionV3 | 0,315 | 0,292 | 0,309 | −0,017 |
| eye_mask | VGG16 | 0,350 | 0,427 | 0,314 | +0,112 |
| eye_mask | ResNet50 | 0,313 | 0,390 | 0,275 | +0,115 |
| eye_mask | InceptionV3 | 0,336 | 0,375 | 0,319 | +0,056 |

Kesimpulan sementara:
- Di 5 dari 6 model, F1 spesies terlihat lebih tinggi 0,05–0,12 daripada spesies laut. Jadi *species shift* memang
  menyumbang penurunan.
- Namun spesies terlihat pun hanya mencapai F1 0,29–0,43, jauh di bawah uji MFED (0,8–0,9). Penyumbang terbesar tetap
  *domain shift* kamera, pencahayaan, dan latar, bukan spesies.
- Pada eye_mask, keunggulan spesies terlihat lebih konsisten (+0,06 s.d. +0,12). Masuk akal: tampilan mata nila/mujair
  di FFE lebih mirip dengan MFED daripada mata ikan laut.
- Baru seed 42. Analisis akan diulang setelah evaluasi K1 (seed 123 dan 2026) selesai; cukup jalankan ulang skrip.
