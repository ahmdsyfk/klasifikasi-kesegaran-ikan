# IkanSegar (Android)

Aplikasi Android untuk penelitian kesegaran ikan berdasarkan citra mata. Fungsinya ada dua:

1. **Pindai**: memotret mata ikan lalu menampilkan **semua skor** tiga kelas (Sangat Segar, Segar, Tidak Segar). Model dijalankan di ponsel dengan LiteRT dan bisa dipakai tanpa internet.
2. **Data primer**: memotret mata ikan di pasar dan memberi label. Data disimpan dulu di ponsel (offline-first), lalu diunggah ke Supabase saat ada internet.

## Arsitektur

Proyek ini memakai Kotlin Multiplatform dengan target Android saja, pola MVVM, dan Material 3 Expressive.

| Modul / folder | Isi |
| --- | --- |
| `shared/commonMain` | Domain, praproses (`ImageMath`), ViewModel, UI Compose, repositori SQLDelight, Koin |
| `shared/androidMain` | Kamera CameraX dengan overlay kepala ikan, LiteRT, penyimpanan foto, gateway Supabase, WorkManager |
| `shared/commonMain/sqldelight` | Skema basis data lokal |
| `androidApp` | `MainActivity`, splash screen, ikon, manifest, dan `BuildConfig` Supabase |
| `tools/gen_preprocess_fixture.py` | Fixture uji paritas praproses Kotlin terhadap Python |

Praproses di ponsel **identik** dengan pipeline pelatihan. Crop mata dibuat persis seperti `src/fishfresh/eye_roi.py::crop_eye` (Pillow LANCZOS, masker lingkaran). Citra utuh diubah ukurannya persis seperti `tf.image.resize(bilinear, antialias)`. Kesamaan ini diuji oleh `PreprocessParityTest`.

## Build

```bash
cd mobile
cp local.properties.example local.properties   # isi sdk.dir; kunci Supabase opsional
./gradlew :shared:testAndroidHostTest           # uji paritas praproses
./gradlew :androidApp:assembleDebug
```

`SUPABASE_URL` dan `SUPABASE_PUBLISHABLE_KEY` dibaca dari `local.properties` atau dari variabel lingkungan. Kalau keduanya kosong, aplikasi tetap berjalan; hanya unggah ke Supabase yang nonaktif.

## Model

- **Model bawaan APK:** `androidApp/src/main/assets/models/<id>.tflite` dan `<id>.json`. Saat ini isinya model **demo** (bobot acak) yang ditandai jelas di aplikasi.
- **Model hasil penelitian:** diterbitkan ke registry Supabase per skenario. Ponsel mengunduh model sekali, memverifikasi SHA-256, lalu menjalankannya offline.

```bash
# dari root repo; SUPABASE_URL & SUPABASE_SECRET_KEY di environment (jangan ditulis ke berkas)
uv run python -m fishfresh.export_mobile -c configs/eye_mask.yaml --publish --default resnet50
uv run python -m fishfresh.export_mobile -c configs/eye_mask.yaml --bundle resnet50   # jadikan model bawaan APK
```

## Data primer

Data primer ditarik ke laptop dengan `uv run python -m fishfresh.primer pull`. Skema Supabase dan uji RLS-nya ada di `../supabase/`.

## Izin aplikasi

| Izin | Keterangan |
| --- | --- |
| `CAMERA` | Diminta saat runtime, dengan penjelasan. Bila ditolak permanen, aplikasi mengarahkan ke Setelan. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Untuk unggah ke Supabase dan constraint jaringan WorkManager. |
| Galeri | Memakai Photo Picker, jadi tidak perlu izin penyimpanan. |

## Lisensi pihak ketiga

Font Plus Jakarta Sans berlisensi SIL OFL 1.1 (lihat `licenses/PlusJakartaSans-OFL.txt`).
