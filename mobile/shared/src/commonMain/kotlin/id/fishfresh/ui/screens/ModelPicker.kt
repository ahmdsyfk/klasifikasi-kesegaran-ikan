package id.fishfresh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.ModelState
import id.fishfresh.domain.Scenario
import id.fishfresh.presentation.ModelUiState
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.fmt
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

/** Pemilih dua langkah: (1) skenario penelitian, (2) arsitektur + metriknya. Satu keputusan per langkah. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(st: ModelUiState, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scenario by remember { mutableStateOf<Scenario?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.l)
                .padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            val sc = scenario
            if (sc == null) {
                Eyebrow("Langkah 1 dari 2")
                Text("Pilih skenario", style = MaterialTheme.typography.titleLarge)
                st.scenarios.forEach { s ->
                    val active = st.selected?.scenario == s.key
                    Row(
                        Modifier.fillMaxWidth().clickable { scenario = s }.padding(vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium,
                                color = if (active) Palette.Primary else Palette.Text)
                            if (s.desc.isNotBlank()) Text(s.desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                            Text("${s.models.size} model", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
                        }
                        PlainIcon(Icons.AutoMirrored.Outlined.ArrowForward, Palette.TextMuted, 20.dp)
                    }
                    Hairline()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scenario = null }) {
                        PlainIcon(Icons.AutoMirrored.Outlined.ArrowBack, Palette.Text, contentDescription = "Kembali")
                    }
                    Column {
                        Eyebrow("Langkah 2 dari 2")
                        Text(sc.name, style = MaterialTheme.typography.titleLarge)
                    }
                }
                sc.models.forEach { m -> ArchOption(m, m.id == st.selected?.id, st.states[m.id]) { onSelect(m.id) } }
                Text(
                    "F1 = F1-score makro. MFED = data uji, FFE = uji lintas dataset tanpa latih ulang. " +
                        "Latensi diukur di CPU 4 thread laptop; waktu di HP tampil di layar hasil.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ArchOption(m: ModelEntry, selected: Boolean, state: ModelState?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Palette.Primary))
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.archName + (m.seed?.let { " · seed $it" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (state == ModelState.Ready || m.bundled) PlainIcon(Icons.Outlined.CloudDone, Palette.Accent, 18.dp, "Tersedia offline")
            }
            m.metrics.f1Mfed?.let { KeyValue("F1 MFED (uji)", fmt(it, 3)) }
            m.metrics.f1Ffe?.let { KeyValue("F1 FFE (lintas dataset)", fmt(it, 3)) }
            m.metrics.deltaF1?.let { KeyValue("ΔF1", fmt(it, 3)) }
            m.metrics.latencyMsCpu4?.let { KeyValue("Latensi CPU 4 thread", "${fmt(it)} ms") }
            KeyValue("Ukuran · input", "${fmt(m.fileMb)} MB · ${m.inputSize} px ${if (m.inputVariant == "eye_crop") "crop mata" else "citra utuh"}")
            if (m.demo) Text("Model demo, belum dilatih", style = MaterialTheme.typography.labelMedium, color = Palette.Warning)
        }
    }
    Hairline()
}
