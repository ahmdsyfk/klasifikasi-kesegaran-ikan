package id.fishfresh.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dataset
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.FishLogo
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

/**
 * Ringkasan eksperimen penelitian. Isi disusun berlapis (ringkasan selalu tampil, rincian dibuka
 * per topik) agar pembaca tidak langsung dihadapkan pada seluruh detail sekaligus.
 */
@Composable
fun AboutScreen() = ScreenColumn {
    Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Image(FishLogo, null, Modifier.size(48.dp))
        Column(Modifier.padding(start = Space.m)) {
            Eyebrow("Penelitian skripsi · 2026")
            Text("Tentang penelitian", style = MaterialTheme.typography.headlineSmall)
        }
    }

    Panel {
        Text(
            "Perbandingan Efisiensi Arsitektur VGG16, ResNet50, dan InceptionV3 untuk Klasifikasi " +
                "Tingkat Kesegaran Ikan Berdasarkan Citra Mata pada Perangkat Terbatas",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Ahmad Safrudin Kurniawan · Teknik Informatika, Fakultas Ilmu Komputer, " +
                "Institut Informatika dan Bisnis Darmajaya, Bandar Lampung",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
        )
        Text(
            "Mata ikan segar umumnya jernih, cerah, dan berkilau; mata ikan yang tidak segar tampak keruh, " +
                "kusam, atau cekung. Penelitian ini membandingkan tiga CNN untuk mengenali perubahan itu dari foto " +
                "mata ke dalam tiga kelas: Sangat Segar, Segar, dan Tidak Segar. Yang dibandingkan bukan hanya " +
                "ketepatan, tetapi juga kebutuhan komputasi dan kemampuan bertahan pada data dari sumber lain.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
        Topic(Icons.Outlined.Flag, "Tujuan", initiallyOpen = true) {
            Bullet("Mengukur dan membandingkan accuracy, precision, recall, dan F1-score ketiga arsitektur, pada MFED maupun pada FFE tanpa pelatihan ulang.")
            Bullet("Mengukur dan membandingkan efisiensinya: jumlah parameter, ukuran model, waktu pelatihan, waktu inferensi, dan penggunaan memori pada perangkat terbatas.")
        }
        Topic(Icons.Outlined.Dataset, "Data") {
            Term("MFED (data utama)", "Multistage Fish Eyes Dataset v2: 4.800 citra mata dua spesies ikan air tawar, 1.600 per kelas. Dibagi 70 : 15 : 15 untuk latih, validasi, dan uji secara terstratifikasi.")
            Term("FFE (uji lintas dataset)", "Freshness of the Fish Eyes v1: ±4.392 citra dari delapan spesies. Hanya dipakai untuk menguji, tanpa pelatihan ulang, agar terlihat apakah model tetap baik pada data dari sumber berbeda.")
            Term("Anti kebocoran data", "Citra identik dibuang dan citra hampir-duplikat (pHash) dikelompokkan, sehingga satu kelompok tidak pernah terpecah ke data latih dan data uji.")
            Term("Data primer (aplikasi ini)", "Foto mata ikan dari pasar, diberi label oleh pengumpul. Disimpan sebagai Primer 1 dan Primer 2; beberapa foto dari ikan yang sama diberi kode ikan yang sama agar tetap satu kelompok saat pembagian data.")
        }
        Topic(Icons.Outlined.Memory, "Model & pelatihan") {
            Term("Arsitektur", "VGG16, ResNet50, InceptionV3 dengan bobot pralatih ImageNet; kepala klasifikasi GAP → Dropout → Dense 256 → Dropout → Softmax 3 kelas. Input 224 × 224 piksel.")
            Term("Dua fase", "Fase 1: backbone dibekukan (learning rate 1e-3). Fase 2: 30% lapisan teratas dibuka untuk fine-tuning (1e-5). Model terbaik dipilih dari val_loss terendah dengan early stopping.")
            Term("Augmentasi", "Rotasi, gamma, noise, transformasi perspektif, flip horizontal, dan kecerahan.")
        }
        Topic(Icons.Outlined.Science, "Skenario eksperimen") {
            Term("Baseline", "Sesuai proposal: citra utuh di-resize 224 px.")
            Term("Crop mata", "Hanya area mata: crop persegi berpusat di mata (1,10 × ukuran mata), area di luar lingkaran dihitamkan, lalu 224 px. Di aplikasi, crop ini dibuat dari lingkaran pada kamera.")
            Term("K1 · Multi-seed", "Diulang dengan seed 42, 123, dan 2026, lalu diuji signifikansinya dengan McNemar.")
            Term("K2 · Tanpa fine-tuning", "Backbone tetap beku untuk melihat kontribusi fine-tuning.")
            Term("K3 · Inception 299 px", "InceptionV3 pada resolusi aslinya.")
            Term("K4 · Tanpa augmentasi", "Mengukur pengaruh augmentasi pada MFED dan FFE.")
            Term("K5–K8", "Class weight + label smoothing, fine-tuning lebih dalam (60%), skala thread 1/2/4/8, dan mixed precision.")
            Term("Validasi lapangan", "Model diuji pada data primer dari pasar yang dikumpulkan dengan aplikasi ini.")
        }
        Topic(Icons.Outlined.Analytics, "Cara mengukur") {
            Term("Kinerja", "Accuracy, precision, recall, F1-score macro, dan confusion matrix (terutama Sangat Segar ↔ Segar yang paling mudah tertukar).")
            Term("Generalisasi", "ΔF1 = F1 MFED − F1 FFE. Makin kecil, makin stabil model pada data dari sumber lain.")
            Term("Efisiensi", "Jumlah parameter, ukuran berkas, waktu pelatihan, waktu inferensi di CPU 4 thread (batch 1), dan memori puncak.")
            Term("Interpretasi", "Grad-CAM untuk melihat apakah model benar-benar memperhatikan kornea dan pupil.")
        }
        Topic(Icons.Outlined.PhoneAndroid, "Tentang aplikasi") {
            Bullet("Model berjalan di ponsel (LiteRT) dan bisa dipakai tanpa internet. Waktu praproses dan inferensi di ponsel ikut dicatat.")
            Bullet("Praproses di ponsel identik dengan pelatihan; kecocokannya diuji piksel per piksel terhadap kode Python.")
            Bullet("Semua skor kelas ditampilkan, bukan hanya kelas teratas, agar hasil dapat dianalisis.")
            Bullet("Saat mengumpulkan data, label dipilih lebih dulu; skor model baru ditampilkan setelah disimpan agar penilaian pengumpul tidak terpengaruh.")
            Bullet("Aplikasi ini alat pendukung penelitian (pengambilan data primer dan demonstrasi), bukan pengganti pemeriksaan mutu ikan secara resmi.")
        }
        Topic(Icons.Outlined.Lock, "Privasi") {
            Bullet("Tanpa akun. Setiap instalasi mendapat identitas anonim; ponsel hanya bisa melihat data miliknya sendiri.")
            Bullet("Yang diunggah: foto mata ikan, label, jenis ikan, nama pasar, catatan, model ponsel, dan skor model. Tidak ada lokasi GPS atau data pribadi.")
        }
    }
}

@Composable
private fun Topic(icon: ImageVector, title: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Panel(padding = Space.card) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            PlainIcon(icon, Palette.Accent, 22.dp)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PlainIcon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, Palette.TextMuted, 22.dp)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { content() }
        }
    }
}

@Composable
private fun Term(term: String, desc: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(desc, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
}

@Composable
private fun Bullet(text: String) = Row {
    Text("•", Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Accent)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
