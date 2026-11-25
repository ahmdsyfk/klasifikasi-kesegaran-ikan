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
| FFE v1 | Mendeley Data 10.17632/xzyx7pbr3w.1 | folder `<Spesies> - <Kelas>` | 4.392 citra (diunduh per berkas) — *angka final dicatat setelah unduhan lengkap* |

Observasi visual: citra MFED berupa **kepala ikan utuh** dengan latar; mata hanya ±15–25 % lebar
citra dan posisinya bervariasi. Citra FFE sebagian besar sudah **dipotong rapat ke area mata**.
Perbedaan pembingkaian ini menjadi alasan skenario "fokus mata" (bagian 4).

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
| mata_tidak_terdeteksi | tidak ada kotak valid / skor OWLv2 rendah | *ditetapkan dari distribusi* |
| mata_terlalu_kecil | sisi kotak mata (px asli) | *ditetapkan* |
| buram | variansi Laplacian di area mata | *ditetapkan* |
| terlalu_gelap / terlalu_terang | rata-rata kecerahan area mata | *ditetapkan* |
| over/under_exposure | fraksi piksel jenuh | *ditetapkan* |

*Ambang dan jumlah citra yang dibuang dicatat setelah deteksi mata selesai (lihat bagian 7).*

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

### Run

| Run | Mulai | Data | Status |
|---|---|---|---|
| `baseline` (citra utuh, sesuai proposal) | 2026-11-25 ±22:25 | split seed 42, 3.360 latih | berjalan: InceptionV3 → ResNet50 → VGG16 |
| `eye_mask` (fokus mata) | setelah ROI MFED selesai | split yang sama, setelah filter | menunggu |

## 8. Hasil

*Diisi otomatis dari `outputs/<run>/ringkasan.csv` setelah evaluasi.*
