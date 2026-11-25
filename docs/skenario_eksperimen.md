# Skenario Eksperimen

Dokumen ini menerjemahkan Bab III proposal menjadi skenario yang bisa dijalankan dengan kode di repo ini,
lalu menambahkan **kemungkinan** (skenario tambahan, risiko, dan hipotesis hasil) yang belum ditetapkan
di proposal. Semua perintah dijalankan dari root repo.

## 0. Fakta data (hasil pengecekan Mendeley, 25 Nov 2026)

| | MFED v2 (utama) | FFE v1 (eksternal) |
|---|---|---|
| Isi | 4.800 PNG: 3 folder `Highly Fresh` / `Fresh` / `Not Fresh`, masing-masing 1.600 | 24 folder `<Spesies> - <Kelas>`, ±4.392 JPG |
| Spesies | Dua spesies air tawar. **Tidak ada label spesies di struktur folder** | 8 spesies: Chanos chanos, Eleutheronema tetradactylum, Johnius trachycephalus, Nibea albiflora, *Oreochromis mossambicus* (mujair), *Oreochromis niloticus* (nila), Rastrelliger faughni, Upeneus moluccensis |
| Distribusi | Seimbang (1/3 per kelas) | Tidak seimbang. Contoh: nila 328 HF / 231 F / 246 NF; Johnius 80/80/80 |
| Peran | Latih / validasi / uji (70:15:15) | Uji lintas dataset tanpa pelatihan ulang |

Label FFE langsung cocok dengan tiga kelas MFED (Highly Fresh → Sangat Segar, Fresh → Segar,
Not Fresh → Tidak Segar), jadi tidak perlu pemetaan manual. Satu folder FFE punya spasi ganda
(`Nibea Albiflora -  Highly Fresh`); hal ini sudah ditangani oleh downloader dan parser label.

---

## A. Skenario utama (sesuai proposal)

Semua skenario utama memakai `configs/default.yaml` (run `baseline`) dengan protokol yang sama untuk
ketiga arsitektur: split, praproses, augmentasi, head, optimizer, dan kriteria berhenti.

### S0. Pemeriksaan & pembagian data (3.5)

```bash
uv run fishfresh prepare --seeds 42 123 2026
```
- Setiap citra diperiksa dengan `PIL.verify()` lalu dibuka penuh. Citra rusak dicatat di `data/splits/mfed_bermasalah.csv`.
- **Duplikat identik** (SHA-256 sama) disimpan satu saja. Citra identik yang labelnya berbeda dibuang semuanya.
- **Hampir duplikat** (pHash, jarak Hamming ≤ 4) dikelompokkan, lalu split dibuat dengan
  `StratifiedGroupKFold` sehingga satu grup tidak pernah terpecah ke train/val/test. Ini mencegah
  kebocoran data, yang penting karena MFED bersifat *multistage*: ikan yang sama kemungkinan difoto
  berulang di beberapa tahap.
- Laporan lengkap ada di `data/splits/laporan_pemeriksaan_data.json`.

### S1. Kinerja pada data uji MFED (3.7.1)

```bash
uv run fishfresh train
uv run fishfresh evaluate
```
- Pelatihan dua fase. **Fase 1:** backbone ImageNet dibekukan (lr 1e-3, ≤ 30 epoch). **Fase 2:**
  30% lapisan teratas dibuka untuk fine-tuning (lr 1e-5, ≤ 30 epoch), dengan BatchNorm tetap beku.
- Model terbaik dipilih dari `val_loss` terendah. Ada EarlyStopping (patience 7) dan ReduceLROnPlateau.
- Metrik: accuracy, precision, recall, F1 **macro** (ditambah weighted dan per kelas), serta confusion
  matrix (`cm_mfed_test.png`). Analisis utama: seberapa sering kelas **Sangat Segar ↔ Segar** tertukar.

### S2. Uji lintas dataset FFE (3.7.2)

Dijalankan otomatis oleh `evaluate`.
- Model hasil MFED langsung diuji ke seluruh citra FFE dengan praproses yang sama, tanpa pelatihan ulang.
- Generalisasi diukur dengan **ΔF1 = F1macro(MFED) − F1macro(FFE)** (`generalization.json`).
  Model dengan ΔF1 terkecil dianggap paling stabil.
- Tambahan: metrik **per spesies** FFE (`metrics_ffe.json → per_species`).

### S3. Efisiensi pada perangkat terbatas (3.7.3)

```bash
uv run fishfresh benchmark
```

| Ukuran | Cara ukur |
|---|---|
| Jumlah parameter | `model.count_params()` (total, trainable, non-trainable) |
| Ukuran berkas | `model_inference.keras` (tanpa state optimizer) dalam MB |
| Waktu pelatihan | Dari awal epoch pertama sampai pelatihan berhenti (fase 1 + fase 2) |
| Waktu inferensi | CPU saja (GPU disembunyikan), `intra_op=4`, batch 1, 10 warm-up, rata-rata 200 citra uji (ms/citra) + median/p95 |
| Memori | RSS puncak selama inferensi (psutil, sampling 5 ms) + selisih RSS setelah model dimuat |
| Pengulangan | 3×, **masing-masing di proses Python baru**, lalu dirata-rata ± std |

Di Linux/WSL/Windows, proses juga di-pin ke 4 core (`cpu_affinity`). Di macOS pinning tidak
tersedia, jadi pembatasan hanya lewat thread TensorFlow.

### S4. Interpretabilitas Grad-CAM (2.9, Bab IV)

```bash
uv run fishfresh gradcam --n-per-class 4
```
Lapisan target: VGG16 `block5_conv3`, ResNet50 `conv5_block3_out`, InceptionV3 `mixed10`.
Pertanyaan yang ingin dijawab: apakah model benar-benar melihat **kornea/pupil**, atau justru
latar/kulit di sekitar mata?

### Rekap

```bash
uv run fishfresh summarize   # outputs/baseline/ringkasan.csv, ringkasan_per_seed.csv, uji_mcnemar.csv
```

---

## B. Kemungkinan skenario tambahan

Prioritas: **★★★** sangat disarankan (memperkuat validitas), **★★** menambah nilai analisis,
**★** opsional/saran penelitian lanjutan.

| Kode | Skenario | Perintah | Tujuan | Prioritas |
|---|---|---|---|---|
| K1 | **Multi-seed** (42, 123, 2026) | `uv run fishfresh all --seeds 42 123 2026` | Menghasilkan mean ± std dan menilai *kestabilan* (dijanjikan di Manfaat 1.5). Satu kali run bisa menyesatkan bila selisih antar-arsitektur kecil | ★★★ |
| K1b | **Uji signifikansi McNemar** | otomatis di `summarize` | Memastikan perbedaan akurasi antar-model memang bermakna, bukan kebetulan (p < 0,05) | ★★★ |
| K2 | Feature extraction saja | `-c configs/skenario/k2_feature_extraction.yaml` | Mengukur kontribusi fine-tuning dan menghemat waktu latih. Sangat relevan untuk isu efisiensi | ★★ |
| K3 | InceptionV3 @ 299×299 | `-c configs/skenario/k3_inception299.yaml --archs inceptionv3` | 299 adalah resolusi asli InceptionV3. Menguji apakah penyeragaman ke 224 merugikannya | ★★ |
| K4 | Tanpa augmentasi | `-c configs/skenario/k4_tanpa_augmentasi.yaml` | Ablasi: mengukur pengaruh augmentasi pada MFED dan pada generalisasi ke FFE | ★★ |
| K5 | Class weight + label smoothing | `-c configs/skenario/k5_class_weight.yaml` | MFED seimbang, tetapi bisa menjadi tidak seimbang setelah deduplikasi. Label smoothing membantu bila batas Sangat Segar/Segar kabur | ★ |
| K6 | Fine-tuning lebih dalam (60%) | `-c configs/skenario/k6_finetune_dalam.yaml` | Sensitivitas terhadap jumlah lapisan yang dibuka | ★ |
| K7 | Skala thread 1/2/4/8 | `for t in 1 2 8; do uv run fishfresh benchmark --set efficiency.threads=$t; done` | Kurva latensi terhadap jumlah core: perangkat murah bisa hanya punya 1–2 core | ★★ |
| K8 | Mixed precision FP16 | `-c configs/skenario/k8_mixed_precision.yaml` | Mempercepat pelatihan di RTX 4060. Waktu latih dilaporkan terpisah dari baseline | ★ |
| K9 | FFE tanpa citra tumpang-tindih | `uv run fishfresh evaluate --exclude-ffe-overlap` | Lihat risiko R2. Uji lintas dataset yang "bersih" | ★★★ |
| K10 | FFE: spesies terlihat vs tak terlihat | analisis `per_species` | Membandingkan nila/mujair (kemungkinan juga ada di MFED) dengan 6 spesies laut yang belum pernah dilihat. Ini memisahkan *domain shift* kamera dari *species shift* | ★★★ |
| K11 | Konversi TFLite / kuantisasi INT8 | (belum diimplementasi) | Langkah nyata menuju perangkat terbatas: ukuran turun ±4× dan latensi turun | ★ saran |
| K12 | Pembanding model ringan (MobileNetV2/EfficientNetB0) | (belum diimplementasi; cukup tambah entri di `ARCHS`) | Pembanding efisiensi yang adil karena ketiga model proposal tergolong berat | ★ saran |
| K13 | Validasi lapangan Pasar Natar | foto HP sendiri lalu `evaluate` | Uji generalisasi ke kondisi nyata yang menjadi motivasi proposal | ★ saran |

---

## C. Risiko & kendala yang mungkin terjadi

| # | Risiko | Dampak | Mitigasi di kode / saran |
|---|---|---|---|
| R1 | **GPU RTX 4060 tidak terpakai di Windows 11 native.** TensorFlow ≥ 2.11 hanya mendukung GPU lewat WSL2/Linux | Pelatihan 10–50× lebih lambat | Jalankan di WSL2 dengan `uv sync --extra cuda` (README). Waktu latih yang dilaporkan harus menyebut perangkatnya; `train_meta.json` mencatat `devices` |
| R2 | **Tumpang-tindih MFED–FFE.** FFE memuat nila & mujair, dan MFED juga berisi "dua spesies air tawar". Bila sumber/peneliti sama, citra bisa identik atau hampir identik | Hasil generalisasi FFE terlalu optimistis | `prepare` melaporkan `ffe_overlap_exact/near`. Jalankan K9 dan laporkan keduanya |
| R3 | **Kebocoran multistage.** Ikan yang sama muncul di beberapa tahap atau berupa frame berurutan | Akurasi uji MFED tinggi semu (mendekati 100%) | Pengelompokan pHash + split per grup. Bila perlu, turunkan atau naikkan ambang (`dedup.phash_hamming_threshold`) dan bandingkan |
| R4 | **Batas Sangat Segar vs Segar kabur** (label berbasis waktu penyimpanan, bukan visual murni) | Kebingungan dua kelas tersebut di confusion matrix | Laporkan per kelas, Grad-CAM, dan K5 (label smoothing) |
| R5 | **Pergeseran domain FFE** (kamera, pencahayaan, latar, 6 spesies laut) | F1 FFE bisa turun drastis (mis. ke 0,4–0,6) | Bukan kegagalan: inilah temuan penelitian. Analisis per spesies (K10) |
| R6 | MFED berformat PNG dan berukuran besar (3,5 GB) | RAM penuh bila semua citra di-cache sebagai float | Cache disimpan **setelah** resize 224 (±600 KB/citra × 3.360 ≈ 2 GB). Bila RAM 16 GB kurang, hapus `.cache()` atau turunkan `batch_size` |
| R7 | VRAM 8 GB tidak cukup untuk fine-tuning VGG16/ResNet50 dengan batch 32 | OOM | `--set train.batch_size=16` atau K8 (mixed precision) |
| R8 | Pengukuran latensi berfluktuasi (thermal throttling laptop, proses latar) | Std besar | 3× proses baru, laporkan median & p95, colokkan charger, tutup aplikasi lain |
| R9 | Simulasi CPU 4 thread ≠ Raspberry Pi (ARM, RAM 1–8 GB) | Klaim "perangkat terbatas" perlu dibatasi | Sesuai ruang lingkup 1.3 no. 6: sebut sebagai *simulasi*. K11 (TFLite) sebagai saran |
| R10 | Versi Keras 3.15.1 di proposal vs TensorFlow yang terpasang | Inkompatibilitas | Sudah dikunci: `tensorflow==2.21.0`, `keras==3.15.1`, Python 3.13 (`uv.lock`) |

---

## D. Hipotesis / kemungkinan hasil

Hipotesis ini dipakai untuk mengarahkan pembahasan, bukan klaim. Semuanya harus dibuktikan dengan
data dari eksperimen.

1. **Jumlah parameter ≠ kecepatan.** Tanpa lapisan FC asli, VGG16 justru memiliki parameter paling
   sedikit (±14,8 juta dengan head 256), dibanding InceptionV3 (±22,3 juta) dan ResNet50 (±24,1 juta).
   Namun beban komputasinya paling besar (±15,5 GFLOPs vs ±4,1 untuk ResNet50 dan ±3,2 untuk
   InceptionV3 pada 224×224), sehingga **latensi CPU VGG16 diperkirakan paling tinggi**.
   Uji coba awal pipeline di Mac mini M-series (4 thread, bobot acak; arsitekturnya sama sehingga
   latensinya representatif) menunjukkan VGG16 ±130 ms/citra vs ResNet50 ±44 ms dan InceptionV3
   ±40 ms. Angka final harus diukur ulang di laptop penelitian.
2. **Ukuran berkas** mengikuti jumlah parameter: VGG16 ±57 MB, InceptionV3 ±85 MB, ResNet50 ±92 MB (float32).
3. **Akurasi MFED** kemungkinan tinggi untuk ketiga model (> 90%) karena dataset seimbang dan homogen.
   Selisih antar-arsitektur bisa kecil, sehingga K1 dan McNemar penting.
4. **Generalisasi FFE**: model dengan residual/multi-skala (ResNet50/InceptionV3) diduga lebih tahan
   terhadap pergeseran domain dibanding VGG16, tetapi ini belum pasti. Penurunan terbesar diduga pada
   spesies laut yang tidak ada di MFED.
5. **Rekomendasi akhir** kemungkinan berupa *trade-off*: model dengan F1 tertinggi belum tentu yang
   tercepat. Tampilkan sebagai diagram *F1 vs latensi* (Pareto) di Bab IV.

---

## E. Tata letak hasil

```
outputs/<run_name>/seed<k>/<arch>/
  model.keras               checkpoint terbaik (dengan optimizer)
  model_inference.keras     untuk ukuran berkas & benchmark
  history.csv, kurva_pelatihan.png, train_meta.json
  metrics_mfed_test.json, cm_mfed_test.png, pred_mfed_test.csv
  metrics_ffe.json, cm_ffe.png, pred_ffe.csv, generalization.json
  efficiency.json (4 thread), efficiency_t<n>.json (K7)
  gradcam/gradcam_grid.png
outputs/<run_name>/ringkasan.csv, ringkasan_per_seed.csv, uji_mcnemar.csv
```
