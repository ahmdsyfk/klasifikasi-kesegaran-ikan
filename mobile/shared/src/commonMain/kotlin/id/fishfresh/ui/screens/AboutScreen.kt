package id.fishfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Dataset
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.FishLogo
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.ScreenHeader
import id.fishfresh.ui.components.Section
import id.fishfresh.ui.components.icon
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import id.fishfresh.ui.theme.color
import id.fishfresh.ui.theme.softColor

/**
 * Tab "Tentang": eksperimen penelitian disajikan sebagai infografis yang dibaca dari atas ke bawah
 * (siapa → pertanyaan → alur → data → model → skenario → ukuran). Tiap blok berdiri sendiri,
 * memakai angka besar dan ikon agar bisa dipindai tanpa membaca paragraf panjang.
 */
@Composable
fun AboutScreen() = ScreenColumn {
    ScreenHeader("Tentang penelitian", eyebrow = "Penelitian skripsi · 2026")
    StudentCard()
    QuestionCard()
    Section(title = "Alur eksperimen") { Pipeline() }
    Section(title = "Data") { DataBlock() }
    Section(title = "Tiga arsitektur CNN") { Architectures() }
    Section(title = "Pelatihan dua fase") { TwoPhase() }
    Section(title = "Skenario eksperimen") { Scenarios() }
    Section(title = "Yang diukur") { Measures() }
    Section(title = "Tentang aplikasi ini") { AppNotes() }
}

/* ---------- Mahasiswa ---------- */

@Composable
private fun StudentCard() = Panel(color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.Primary), contentAlignment = Alignment.Center) {
            Text("AS", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow("Mahasiswa peneliti")
            Text("Ahmad Safrudin Kurniawan", style = MaterialTheme.typography.titleMedium)
            Text("S1 Teknik Informatika", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        PlainIcon(Icons.Outlined.School, Palette.Accent, 18.dp)
        Text(
            "Fakultas Ilmu Komputer · Institut Informatika dan Bisnis Darmajaya, Bandar Lampung",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
        )
    }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Palette.SoftAccent).padding(Space.m)) {
        Text(
            "Perbandingan Efisiensi Arsitektur VGG16, ResNet50, dan InceptionV3 untuk Klasifikasi Tingkat " +
                "Kesegaran Ikan Berdasarkan Citra Mata pada Perangkat Terbatas",
            style = MaterialTheme.typography.titleSmall, color = Palette.PrimaryPressed,
        )
    }
}

/* ---------- Pertanyaan ---------- */

@Composable
private fun QuestionCard() = Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Image(FishLogo, null, Modifier.size(44.dp))
        Text(
            "Arsitektur mana yang paling tepat sekaligus paling ringan untuk menilai kesegaran ikan dari foto mata, langsung di ponsel?",
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FreshnessClass.entries.forEach { c ->
            Column(
                Modifier.weight(1f).clip(MaterialTheme.shapes.medium).background(c.softColor()).padding(vertical = Space.m),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                PlainIcon(c.icon(), c.color(), 22.dp)
                Text(c.labelId, style = MaterialTheme.typography.labelMedium, color = c.color())
            }
        }
    }
    Text(
        "Mata segar jernih dan berkilau; makin lama makin keruh, kusam, dan cekung.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
    )
}

/* ---------- Alur ---------- */

private data class Step(val icon: ImageVector, val title: String, val desc: String)

private val steps = listOf(
    Step(Icons.Outlined.Dataset, "Kumpulkan data", "MFED untuk latih & uji, FFE untuk uji lintas dataset."),
    Step(Icons.Outlined.Crop, "Praproses", "Citra utuh atau crop mata, diubah ke 224 × 224 piksel."),
    Step(Icons.Outlined.Memory, "Latih 3 CNN", "VGG16, ResNet50, InceptionV3 dengan transfer learning."),
    Step(Icons.Outlined.Analytics, "Uji & bandingkan", "Kinerja di MFED, ketahanan di FFE, dan efisiensi."),
    Step(Icons.Outlined.PhoneAndroid, "Jalankan di ponsel", "Model terbaik dipakai aplikasi ini, tanpa internet."),
    Step(Icons.Outlined.Storefront, "Validasi lapangan", "Diuji pada foto ikan dari pasar (data primer)."),
)

@Composable
private fun Pipeline() = Column {
    steps.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Primary), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
                if (i < steps.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(Palette.Divider))
            }
            Column(Modifier.weight(1f).padding(bottom = if (i < steps.lastIndex) Space.l else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PlainIcon(s.icon, Palette.Accent, 18.dp)
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                }
                Text(s.desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
        }
    }
}

/* ---------- Data ---------- */

@Composable
private fun DataBlock() {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Stat("4.800", "citra MFED", "2 spesies · 1.600 per kelas")
        Stat("±4.392", "citra FFE", "8 spesies · hanya untuk uji")
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Pembagian MFED (terstratifikasi)", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))) {
            SplitPart(0.70f, "Latih 70%", Palette.Primary)
            SplitPart(0.15f, "Val 15%", Palette.Accent)
            SplitPart(0.15f, "Uji 15%", Palette.PrimaryPressed)
        }
    }
    Text(
        "Citra identik dibuang dan citra hampir kembar dikelompokkan, agar tidak ada yang bocor dari data latih ke data uji.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
    )
}

@Composable
private fun RowScope.Stat(value: String, label: String, note: String) = Column(
    Modifier.weight(1f).clip(MaterialTheme.shapes.medium).background(Color.White)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.medium).padding(Space.m),
) {
    Text(value, style = MaterialTheme.typography.headlineMedium, color = Palette.Primary)
    Text(label, style = MaterialTheme.typography.titleSmall)
    Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
}

@Composable
private fun RowScope.SplitPart(w: Float, label: String, c: Color) = Box(
    Modifier.weight(w).fillMaxHeight().background(c), contentAlignment = Alignment.Center,
) { Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1) }

/* ---------- Arsitektur ---------- */

@Composable
private fun Architectures() {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Arch("VGG16", "16", "lapisan bertumpuk sederhana")
        Arch("ResNet50", "50", "jalur pintas residual")
        Arch("InceptionV3", "48", "filter paralel multi-ukuran")
    }
    Text(
        "Semua memakai bobot pralatih ImageNet dan kepala yang sama: GAP → Dropout → Dense 256 → Dropout → Softmax 3 kelas.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
    )
}

@Composable
private fun RowScope.Arch(name: String, depth: String, idea: String) = Column(
    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.medium).background(Color.White)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.medium).padding(Space.m),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Text(depth, style = MaterialTheme.typography.headlineSmall, color = Palette.Accent)
    Text("lapisan", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
    Text(name, Modifier.padding(top = Space.xs), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Text(idea, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, textAlign = TextAlign.Center)
}

/* ---------- Pelatihan ---------- */

@Composable
private fun TwoPhase() {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Phase("Fase 1", "Backbone dibekukan", "Hanya kepala dilatih", "lr 1e-3", Palette.SoftAccent, Palette.PrimaryPressed)
        Phase("Fase 2", "Fine-tuning", "30% lapisan atas dibuka", "lr 1e-5", Palette.AccentSoft, Palette.Accent)
    }
    Text(
        "Model terbaik dipilih dari val_loss terendah dengan early stopping. Augmentasi: rotasi, gamma, noise, perspektif, flip, kecerahan.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
    )
}

@Composable
private fun RowScope.Phase(tag: String, title: String, desc: String, lr: String, bg: Color, fg: Color) = Column(
    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.medium).background(bg).padding(Space.m),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Text(tag.uppercase(), style = MaterialTheme.typography.labelMedium, color = fg)
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
    Text(lr, Modifier.padding(top = Space.xs), style = MaterialTheme.typography.labelMedium, color = fg)
}

/* ---------- Skenario ---------- */

private val scenarios = listOf(
    "Baseline" to "Citra utuh 224 px (sesuai proposal)",
    "Crop mata" to "Hanya area mata, luar lingkaran dihitamkan",
    "K1" to "3 seed (42, 123, 2026) + uji McNemar",
    "K2" to "Tanpa fine-tuning",
    "K3" to "InceptionV3 pada 299 px",
    "K4" to "Tanpa augmentasi",
    "K5" to "Class weight + label smoothing",
    "K6" to "Fine-tuning lebih dalam (60%)",
    "K7" to "Skala thread CPU 1/2/4/8",
    "K8" to "Mixed precision",
    "Lapangan" to "Data primer dari pasar",
)

@Composable
private fun Scenarios() = Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
    scenarios.forEachIndexed { i, (code, desc) ->
        val main = i < 2
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(
                Modifier.width(76.dp).heightIn(min = 28.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (main) Palette.Primary else Palette.SoftAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = if (main) Color.White else Palette.PrimaryPressed)
            }
            Text(desc, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/* ---------- Ukuran ---------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Measures() {
    val items = listOf(
        Triple(Icons.Outlined.Analytics, "Kinerja", "Accuracy, precision, recall, F1 macro, confusion matrix"),
        Triple(Icons.Outlined.SyncAlt, "Generalisasi", "ΔF1 = F1 MFED − F1 FFE; makin kecil makin stabil"),
        Triple(Icons.Outlined.Speed, "Efisiensi", "Parameter, ukuran berkas, waktu latih & inferensi (CPU 4 thread), memori"),
        Triple(Icons.Outlined.Visibility, "Interpretasi", "Grad-CAM: apakah model melihat kornea & pupil"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                pair.forEach { (ic, t, d) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.medium).background(Color.White)
                            .border(1.dp, Palette.Divider, MaterialTheme.shapes.medium).padding(Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        PlainIcon(ic, Palette.Accent, 22.dp)
                        Text(t, style = MaterialTheme.typography.titleSmall)
                        Text(d, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
            }
        }
    }
}

/* ---------- Aplikasi ---------- */

@Composable
private fun AppNotes() {
    Note(Icons.Outlined.PhoneAndroid, "Model berjalan di ponsel (LiteRT), tanpa internet. Praproses identik dengan pelatihan dan diuji piksel per piksel.")
    Note(Icons.Outlined.AccountTree, "Semua skor kelas ditampilkan, bukan hanya kelas teratas. Model bisa dipilih per skenario.")
    Note(Icons.Outlined.Storefront, "Saat mengumpulkan data, label dipilih dulu; skor model baru tampil sesudahnya agar penilaian tidak terpengaruh.")
    Note(Icons.Outlined.Lock, "Tanpa akun dan tanpa GPS. Yang diunggah hanya foto mata, label, jenis ikan, pasar, catatan, model ponsel, dan skor.")
    Text(
        "Alat pendukung penelitian, bukan pengganti pemeriksaan mutu ikan resmi.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun Note(icon: ImageVector, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
    PlainIcon(icon, Palette.Accent, 20.dp)
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
}
