package id.fishfresh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.ModelState
import id.fishfresh.presentation.BackendViewModel
import id.fishfresh.presentation.ModelViewModel
import id.fishfresh.presentation.SettingsViewModel
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.Section
import id.fishfresh.ui.components.fmt
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (v, label) ->
            ToggleButton(
                checked = v == selected, onCheckedChange = { onSelect(v) }, modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(checkedContainerColor = Palette.Primary),
            ) { Text(label) }
        }
    }
}

@Composable
private fun Field(label: String, hint: String? = null, content: @Composable () -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        content()
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted) }
    }

/**
 * Tab Konfigurasi: satu tempat untuk model, Supabase, dan setelan umum.
 * Dipecah tiga bagian lewat tombol segmen agar satu layar hanya memuat satu topik.
 */
@Composable
fun ConfigScreen(vm: SettingsViewModel, models: ModelViewModel, backend: BackendViewModel) {
    var part by rememberSaveable { mutableIntStateOf(0) }
    ScreenColumn {
        Segmented(listOf(0 to "Model", 1 to "Supabase", 2 to "Umum"), part) { part = it }
        when (part) {
            0 -> ModelSection(models)
            1 -> SupabaseSections(backend)
            else -> GeneralSection(vm)
        }
    }
}

/* ---------- Model ---------- */

@Composable
private fun ModelSection(models: ModelViewModel) {
    val ms by models.state.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Model hasil penelitian diambil dari rilis GitHub. Unduh sekali, lalu bisa dipakai tanpa internet.",
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted,
        )
        IconButton(onClick = { models.refresh() }) {
            PlainIcon(Icons.Outlined.Refresh, Palette.Primary, contentDescription = "Perbarui daftar model")
        }
    }
    ms.refreshError?.let { Notice(it) }
    ms.scenarios.forEach { sc ->
        Section(title = sc.name) {
            sc.models.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                ModelRow(m, ms.states[m.id], onDownload = { models.download(m.id) }, onDelete = { models.delete(m.id) })
            }
        }
    }
    if (ms.models.isEmpty()) Text("Daftar model kosong. Ketuk perbarui saat ada internet.", color = Palette.TextMuted)
}

@Composable
private fun ModelRow(m: ModelEntry, st: ModelState?, onDownload: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(m.archName, style = MaterialTheme.typography.titleSmall)
            val f1 = m.metrics.f1Mfed?.let { "F1 MFED ${fmt(it, 3)} · " } ?: ""
            Text(
                f1 + when {
                    m.bundled -> "bawaan aplikasi"
                    st == ModelState.Ready -> "tersimpan · ${fmt(m.fileMb)} MB"
                    st is ModelState.Downloading -> "mengunduh ${(st.progress * 100).toInt()}%"
                    st is ModelState.Failed -> st.message
                    else -> "${fmt(m.fileMb)} MB"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (st is ModelState.Failed) Palette.Danger else Palette.TextMuted,
            )
        }
        when {
            m.bundled -> PlainIcon(Icons.Outlined.CheckCircle, Palette.Accent, 22.dp)
            st is ModelState.Downloading -> CircularProgressIndicator(progress = { st.progress }, modifier = Modifier.size(24.dp))
            st == ModelState.Ready -> IconButton(onClick = onDelete) {
                PlainIcon(Icons.Outlined.DeleteOutline, Palette.TextMuted, contentDescription = "Hapus berkas model")
            }
            else -> IconButton(onClick = onDownload) {
                PlainIcon(
                    if (st is ModelState.Failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Download,
                    Palette.Primary, contentDescription = "Unduh model",
                )
            }
        }
    }
}

/* ---------- Umum ---------- */

@Composable
private fun GeneralSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsState()
    Section(title = "Pengumpulan data") {
        Field("Dataset tujuan", "Foto baru masuk ke dataset ini.") {
            Segmented(listOf("primer_1" to "Primer 1", "primer_2" to "Primer 2"), s.dataset, vm::setDataset)
        }
        OutlinedTextField(s.market, vm::setMarket, Modifier.fillMaxWidth(), label = { Text("Pasar bawaan") }, singleLine = true)
    }
    Section(title = "Inferensi di ponsel") {
        Field("Jumlah thread CPU", "Proposal mengukur dengan 4 thread. Pilihan lain untuk membandingkan kecepatan inferensi.") {
            Segmented(listOf(1 to "1", 2 to "2", 4 to "4"), s.threads, vm::setThreads)
        }
    }
    Section(title = "Aplikasi") {
        KeyValue("Versi", vm.platform.appVersion)
        KeyValue("Perangkat", vm.platform.deviceModel)
    }
}
