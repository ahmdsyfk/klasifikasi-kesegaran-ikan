# IkanSegar app-v0.2.0 (pre-release)

Rilis kedua aplikasi Android **IkanSegar**. Model demo sudah diganti dengan model hasil penelitian.

## Perubahan

- **Model bawaan APK: VGG16 skenario crop mata (`eye_mask-vgg16-s42`, float16, 28,3 MB).** Aplikasi langsung bisa memindai tanpa mengunduh model. Model demo berbobot acak tidak ikut lagi di APK rilis.
- **Model penelitian lain tersedia di Konfigurasi → Model**, diunduh dari rilis `model-baseline-s42` dan `model-eye_mask-s42`. Default registry adalah `eye_mask-vgg16-s42`.
- Paritas Keras vs TFLite untuk keenam model: kelas teratas sama pada semua input uji (top1_agreement 1,0), selisih skor terbesar 0,002.

## Kinerja model

Diuji pada data uji MFED (in-domain) dan FFE (lintas dataset, tanpa latih ulang). Latensi diukur di CPU desktop dengan 4 thread, bukan di ponsel.

| Skenario | Arsitektur | Akurasi MFED | F1 MFED | F1 FFE | Latensi | Ukuran |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Crop mata | **VGG16** (bawaan) | 0,838 | 0,838 | 0,350 | 122,5 ms | 28,3 MB |
| Crop mata | ResNet50 | 0,829 | 0,826 | 0,313 | 41,7 ms | 45,9 MB |
| Crop mata | InceptionV3 | 0,738 | 0,736 | 0,336 | 36,7 ms | 42,6 MB |
| Citra utuh | VGG16 | 0,907 | 0,906 | 0,339 | 122,6 ms | 28,3 MB |
| Citra utuh | ResNet50 | 0,896 | 0,895 | 0,365 | 41,7 ms | 45,9 MB |
| Citra utuh | InceptionV3 | 0,804 | 0,800 | 0,315 | 36,7 ms | 42,6 MB |

**Batasan.** F1 pada FFE sekitar 0,31 sampai 0,37, mendekati tebakan acak untuk tiga kelas. Model belum andal untuk foto ikan di luar kondisi MFED (jenis ikan, kamera, dan pencahayaan berbeda). Skor aplikasi untuk ikan pasar dipakai sebagai bahan validasi lapangan, bukan sebagai penilaian akhir.

## Cara instal

1. Unduh `IkanSegar-0.2.0.apk` (Android 8.0 / API 26 ke atas) dari halaman ini.
2. Buka berkasnya. Jika muncul peringatan, izinkan **Instal aplikasi tidak dikenal** untuk browser atau pengelola berkas yang dipakai.
3. Pilih **Instal**. Versi 0.1.0 yang sudah terpasang akan diperbarui tanpa menghapus riwayat dan data primer.

## Tanda tangan

Sertifikat sama dengan 0.1.0: `CN=IkanSegar, O=Penelitian Kesegaran Ikan, C=ID`, SHA-256 `1d900b5ce92d5e221a4173ddce152b24da3fa389c2272363d33a7b5dfd4332b9`.
