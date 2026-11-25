package id.fishfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.ModelState
import id.fishfresh.presentation.ModelUiState
import id.fishfresh.presentation.ModelViewModel
import id.fishfresh.ui.components.FishEyeIcon
import id.fishfresh.ui.components.FishLogo
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.components.Section
import id.fishfresh.ui.components.fmt
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

@Composable
fun HomeScreen(
    vm: ModelViewModel,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val st by vm.state.collectAsState()
    var picker by remember { mutableStateOf(false) }

    ScreenColumn {
        // Aksi utama: satu tombol besar. Panduan langkah hanya muncul sampai pemindaian pertama.
        Panel(color = Color.White) {
            Text("Pindai mata ikan", style = MaterialTheme.typography.headlineSmall)
            if (!st.settings.onboarded) Steps()
            else Text("Masukkan mata ikan ke lingkaran di kamera.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
                PrimaryButton("Buka kamera", onCamera, icon = Icons.Outlined.CameraAlt)
                SecondaryButton("Pilih dari galeri", onGallery, icon = Icons.Outlined.PhotoLibrary)
            }
        }

        if (st.isDemo) Notice("Model demo belum dilatih, jadi skornya belum bermakna. Model hasil penelitian menyusul.")

        ModelSection(st, onChange = { picker = true }, onCompare = vm::setCompare, onDownload = vm::download)
    }

    if (picker) ModelPickerSheet(st, onDismiss = { picker = false }, onSelect = { vm.select(it); picker = false })
}

@Composable
private fun Steps() = Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
    listOf(
        FishEyeIcon to "Posisikan mata ikan di dalam lingkaran",
        Icons.Outlined.CameraAlt to "Potret di cahaya cukup, hindari pantulan",
        Icons.Outlined.Insights to "Lihat skor ketiga kelas kesegaran",
    ).forEachIndexed { i, (icon, text) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            PlainIcon(icon, Palette.Accent, 22.dp)
            Text("${i + 1}. $text", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun ModelEntry.metricLine(): String = buildList {
    metrics.f1Mfed?.let { add("F1 ${fmt(it, 3)}") }
    metrics.f1Ffe?.let { add("FFE ${fmt(it, 3)}") }
    add(if (fileMb < 0.1) "< 0,1 MB" else "${fmt(fileMb)} MB")
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelSection(st: ModelUiState, onChange: () -> Unit, onCompare: (Boolean) -> Unit, onDownload: (String) -> Unit) {
    val sel = st.selected
    Section(
        title = "Model klasifikasi",
        action = { TextButton(onClick = onChange, enabled = st.models.isNotEmpty()) { Text("Ganti") } },
    ) {
        if (sel == null) {
            Text("Belum ada model", style = MaterialTheme.typography.titleMedium)
        } else {
            val compare = st.settings.compareArchs && st.active.size > 1
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (compare) "${sel.scenarioName} · ${st.active.size} arsitektur" else "${sel.scenarioName} · ${sel.archName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (compare) st.active.joinToString(", ") { it.archName } else sel.metricLine(),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                )
            }
        }
        st.active.forEach { m ->
            when (val s = st.states[m.id]) {
                is ModelState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Mengunduh ${m.archName} · ${(s.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    LinearWavyProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth(), color = Palette.Accent)
                }
                ModelState.NotDownloaded -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onDownload(m.id) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    PlainIcon(Icons.Outlined.CloudDownload, Palette.Primary, 20.dp)
                    Text("Unduh ${m.archName} (${fmt(m.fileMb)} MB) untuk dipakai offline",
                        style = MaterialTheme.typography.bodySmall, color = Palette.PrimaryPressed)
                }
                is ModelState.Failed -> Notice("${m.archName}: ${s.message}")
                else -> Unit
            }
        }
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bandingkan 3 arsitektur", style = MaterialTheme.typography.titleSmall)
                Text("Satu foto, skor dari VGG16, ResNet50, InceptionV3", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            Spacer(Modifier.width(Space.m))
            Switch(st.settings.compareArchs, onCompare, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Primary))
        }
    }
}
