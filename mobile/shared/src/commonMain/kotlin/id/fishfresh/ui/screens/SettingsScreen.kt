package id.fishfresh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonGroupDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.fishfresh.domain.ModelState
import id.fishfresh.presentation.ModelViewModel
import id.fishfresh.presentation.SettingsViewModel
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
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

@Composable
fun SettingsScreen(vm: SettingsViewModel, models: ModelViewModel) {
    val s by vm.settings.collectAsState()
    val pending by vm.pending.collectAsState()
    val ms by models.state.collectAsState()
    ScreenColumn {
        Section(title = "Pengumpulan data") {
            Field("Dataset tujuan", "Foto baru masuk ke dataset ini.") {
                Segmented(listOf("primer_1" to "Primer 1", "primer_2" to "Primer 2"), s.dataset, vm::setDataset)
            }
            OutlinedTextField(s.market, vm::setMarket, Modifier.fillMaxWidth(), label = { Text("Pasar bawaan") }, singleLine = true)
            Hairline()
            KeyValue("Unggahan ke Supabase", if (vm.syncConfigured) (if (pending == 0) "semua terunggah" else "$pending menunggu") else "tidak dikonfigurasi")
        }

        Section(title = "Inferensi di ponsel") {
            Field("Jumlah thread CPU", "Proposal mengukur dengan 4 thread. Kurangi untuk meniru ponsel yang lebih lemah.") {
                Segmented(listOf(1 to "1", 2 to "2", 4 to "4"), s.threads, vm::setThreads)
            }
        }

        Section(
            title = "Model tersimpan",
            action = {
                IconButton(onClick = { models.refresh() }) { PlainIcon(Icons.Outlined.Refresh, Palette.Primary, contentDescription = "Perbarui daftar model") }
            },
        ) {
            if (ms.models.isEmpty()) Text("Daftar model kosong.", color = Palette.TextMuted)
            ms.models.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                val st = ms.states[m.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${m.scenarioName} · ${m.archName}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                m.bundled -> "bawaan aplikasi"
                                st == ModelState.Ready -> "tersimpan · ${fmt(m.fileMb)} MB"
                                st is ModelState.Downloading -> "mengunduh ${(st.progress * 100).toInt()} %"
                                else -> "belum diunduh · ${fmt(m.fileMb)} MB"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                        )
                    }
                    if (!m.bundled && st == ModelState.Ready) {
                        IconButton(onClick = { models.delete(m.id) }) {
                            PlainIcon(Icons.Outlined.DeleteOutline, Palette.TextMuted, contentDescription = "Hapus berkas model")
                        }
                    }
                }
            }
        }

        Section(title = "Aplikasi") {
            KeyValue("Versi", vm.platform.appVersion)
            KeyValue("Perangkat", vm.platform.deviceModel)
        }
    }
}
