package id.fishfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import id.fishfresh.resources.Res
import id.fishfresh.resources.logo_darmajaya
import id.fishfresh.ui.components.Hairline
import org.jetbrains.compose.resources.painterResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.Section
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
    StudentCard()
    QuestionCard()
    Section(title = "Ciri mata ikan per kelas") { EyeTraits() }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Image(painterResource(Res.drawable.logo_darmajaya), "Logo IIB Darmajaya", Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow("Mahasiswa peneliti")
            Text("Institut Informatika dan Bisnis Darmajaya", style = MaterialTheme.typography.titleSmall)
            Text("Bandar Lampung", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        IdRow("Nama", "Ahmad Safrudin Kurniawan", strong = true)
        IdRow("NIM", "2311010132")
        IdRow("Prodi", "S1 Teknik Informatika, Fakultas Ilmu Komputer")
        IdRow(
            "Skripsi",
            "Perbandingan Efisiensi Arsitektur VGG16, ResNet50, dan InceptionV3 untuk Klasifikasi Tingkat " +
                "Kesegaran Ikan Berdasarkan Citra Mata dengan Inferensi di Ponsel",
        )
    }
}

@Composable
private fun IdRow(key: String, value: String, strong: Boolean = false) = Row {
    Text(key, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
    Text(":", Modifier.width(12.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
    Text(
        value, Modifier.weight(1f),
        style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
    )
}

/* ---------- Pertanyaan ---------- */

@Composable
private fun QuestionCard() = Panel {
    Eyebrow("Pertanyaan penelitian")
    Text(
        "Arsitektur mana yang paling tepat sekaligus paling ringan untuk menilai kesegaran ikan dari foto mata, langsung di ponsel?",
        style = MaterialTheme.typography.titleSmall,
    )
}

/* ---------- Ciri mata per kelas ---------- */

private val features = listOf(
    "Bola mata" to listOf("Cembung", "Rata", "Cekung"),
    "Kornea" to listOf("Jernih", "Agak keruh", "Keruh, putih susu"),
    "Pupil" to listOf("Hitam pekat", "Agak keabuan", "Kelabu"),
    "Kilau" to listOf("Cemerlang", "Agak redup", "Kusam"),
)

/** Tabel pembanding: kolom = kelas (dengan ilustrasi mata), baris = bagian mata yang diamati. */
@Composable
private fun EyeTraits() {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Spacer(Modifier.width(68.dp))
        FreshnessClass.entries.forEach { c ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                EyeIllustration(c, Modifier.size(52.dp))
                Text(c.labelId, style = MaterialTheme.typography.labelMedium, color = c.color(), textAlign = TextAlign.Center)
            }
        }
    }
    features.forEach { (part, vals) ->
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(part, Modifier.width(68.dp), style = MaterialTheme.typography.titleSmall)
            vals.forEachIndexed { i, v ->
                val c = FreshnessClass.entries[i]
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(c.softColor()).padding(Space.xs),
                    contentAlignment = Alignment.Center,
                ) { Text(v, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            }
        }
    }
    Text(
        "Gambaran umum dari uji organoleptik mata ikan (SNI 2729:2013) dan Muchtar dkk. (2024). Model belajar " +
            "membedakan perubahan ini langsung dari foto.",
        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
    )
}

/** Ilustrasi mata: makin tidak segar, kornea makin berkabut, kilau hilang, dan bola mata tampak cekung. */
@Composable
private fun EyeIllustration(c: FreshnessClass, modifier: Modifier = Modifier) = Canvas(modifier) {
    val r = size.minDimension / 2f
    val o = center
    val (haze, glint, sunken) = when (c) {
        FreshnessClass.HIGHLY_FRESH -> Triple(0f, 1f, 0f)
        FreshnessClass.FRESH -> Triple(0.28f, 0.6f, 0.35f)
        FreshnessClass.NOT_FRESH -> Triple(0.62f, 0f, 1f)
    }
    drawCircle(Color(0xFFCBD5E1), r, o)                                   // tepi rongga mata
    drawCircle(Color(0xFFE2E8F0), r * 0.9f, o)                            // sklera
    drawCircle(Color(0xFF7C6A3A), r * 0.72f, o)                           // iris
    drawCircle(Color(0xFF0B1220), r * 0.42f, o)                           // pupil
    if (sunken > 0f) drawCircle(                                         // bayangan cekung di tepi atas
        Brush.radialGradient(listOf(Color.Transparent, Color(0x99334155)), center = o.copy(y = o.y + r * 0.25f), radius = r),
        r * 0.9f, o, alpha = sunken,
    )
    if (haze > 0f) drawCircle(Color(0xFFF1F5F9), r * 0.72f, o, alpha = haze) // kornea keruh
    if (glint > 0f) {
        drawCircle(Color.White, r * 0.16f, Offset(o.x + r * 0.26f, o.y - r * 0.28f), alpha = glint)
        drawCircle(Color.White, r * 0.07f, Offset(o.x - r * 0.2f, o.y + r * 0.22f), alpha = glint * 0.8f)
    }
}

/* ---------- Alur ---------- */

private data class Step(val icon: ImageVector, val title: String, val desc: String)

private val steps = listOf(
    Step(Icons.Outlined.Dataset, "Kumpulkan data", "MFED untuk latih, FFE untuk uji silang"),
    Step(Icons.Outlined.Crop, "Praproses", "Citra utuh atau crop mata, 224 × 224 px"),
    Step(Icons.Outlined.Memory, "Latih 3 CNN", "Transfer learning dari bobot ImageNet"),
    Step(Icons.Outlined.Analytics, "Uji dan bandingkan", "Kinerja, ketahanan, dan efisiensi"),
    Step(Icons.Outlined.PhoneAndroid, "Jalankan di ponsel", "Model terbaik berjalan tanpa internet"),
    Step(Icons.Outlined.Storefront, "Validasi lapangan", "Diuji pada foto ikan dari pasar"),
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
                Text(s.desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ---------- Data ---------- */

@Composable
private fun DataBlock() {
    DatasetCard(
        role = "Latih dan uji", name = "MFED v2", full = "Multistage Fish Eyes Dataset",
        value = "4.800", tags = listOf("2 spesies", "3 kelas", "1.600 per kelas"), accent = Palette.Primary,
    )
    DatasetCard(
        role = "Uji lintas dataset", name = "FFE v1", full = "Freshness of the Fish Eyes",
        value = "±4.392", tags = listOf("8 spesies", "tanpa latih ulang"), accent = Palette.Accent,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Pembagian MFED, terstratifikasi per kelas", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            Box(Modifier.weight(0.70f).fillMaxHeight().background(Palette.Primary))
            Box(Modifier.weight(0.15f).fillMaxHeight().background(Palette.Accent))
            Box(Modifier.weight(0.15f).fillMaxHeight().background(Palette.Warning))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Legend(Palette.Primary, "Latih", "70%")
            Legend(Palette.Accent, "Validasi", "15%")
            Legend(Palette.Warning, "Uji", "15%")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        PlainIcon(Icons.Outlined.Lock, Palette.TextMuted, 16.dp)
        Text(
            "Citra kembar dibuang dan citra mirip dikelompokkan, jadi tidak ada yang bocor dari data latih ke data uji.",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
        )
    }
}

/** Kartu dataset: garis warna di kiri (peran), nama dan ciri di tengah, jumlah citra di kanan. */
@Composable
private fun DatasetCard(role: String, name: String, full: String, value: String, tags: List<String>, accent: Color) = Row(
    Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(Color.White)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.medium),
) {
    Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
    Row(Modifier.weight(1f).padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(role.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(full, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                tags.forEach { t ->
                    Text(
                        t, Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
                            .border(1.dp, Palette.Divider, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Text, maxLines = 1,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
            Text("citra", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun Legend(c: Color, label: String, pct: String) = Row(
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
    Text(label, style = MaterialTheme.typography.bodySmall)
    Text(pct, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
}

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
        Triple(Icons.Outlined.Speed, "Efisiensi", "Parameter, ukuran berkas, waktu latih, waktu inferensi (CPU 4 thread), memori"),
        Triple(Icons.Outlined.Visibility, "Interpretasi", "Grad-CAM: apakah model melihat kornea dan pupil"),
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
