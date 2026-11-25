package id.fishfresh.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.presentation.CollectStep
import id.fishfresh.presentation.CollectViewModel
import id.fishfresh.ui.components.ClassBadge
import id.fishfresh.ui.components.FishEyeIcon
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.ScoreBars
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.components.icon
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import id.fishfresh.ui.theme.color
import id.fishfresh.ui.theme.softColor

/** Ciri visual mata (proposal 2.1) sebagai pengingat singkat saat memberi label. */
private fun FreshnessClass.hint(): String = when (this) {
    FreshnessClass.HIGHLY_FRESH -> "Mata jernih, cerah, berkilau, cembung"
    FreshnessClass.FRESH -> "Masih cerah, kilau berkurang, mulai rata"
    FreshnessClass.NOT_FRESH -> "Keruh, kusam, atau cekung"
}

val COMMON_SPECIES = listOf("Nila", "Mujair", "Bandeng", "Kembung", "Tongkol", "Lele", "Gurame", "Kakap", "Selar", "Tenggiri")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollectScreen(vm: CollectViewModel, onAdjust: () -> Unit, onAgain: (sameFish: Boolean) -> Unit, onDone: () -> Unit) {
    val st by vm.state.collectAsState()
    // Kembali dari "Atur lingkaran" -> perbarui pratinjau crop.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPreview() }
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            EyeThumb(st.eyePreview, 72.dp)
            Column(Modifier.weight(1f)) {
                Eyebrow(
                    when (st.step) {
                        CollectStep.LABEL -> "Langkah 1 dari 2"
                        CollectStep.DETAILS -> "Langkah 2 dari 2"
                        else -> "Data primer"
                    },
                )
                Text("Ikan ${st.fishCode}", style = MaterialTheme.typography.titleMedium)
                if (st.step == CollectStep.LABEL) {
                    TextButton(onClick = onAdjust, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Atur lingkaran mata")
                    }
                }
            }
        }

        when (st.step) {
            CollectStep.LABEL -> {
                Text("Seberapa segar ikan ini menurut Anda?", style = MaterialTheme.typography.headlineSmall)
                Text("Nilai langsung dari ikannya. Skor model baru ditampilkan setelah disimpan agar penilaian Anda tidak terpengaruh.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
                    FreshnessClass.entries.forEach { c -> LabelOption(c, st.label == c) { vm.chooseLabel(c) } }
                }
            }

            CollectStep.DETAILS -> {
                st.label?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClassBadge(it)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = vm::back) {
                            PlainIcon(Icons.Outlined.Edit, Palette.Primary, 18.dp)
                            Spacer(Modifier.size(4.dp))
                            Text("Ubah label")
                        }
                    }
                }
                SpeciesField(st.species, st.speciesSuggestions, vm::setSpecies)
                OutlinedTextField(
                    st.market, vm::setMarket, Modifier.fillMaxWidth(), label = { Text("Pasar / lokasi") },
                    placeholder = { Text("mis. Pasar Natar") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                // Catatan jarang dipakai -> disembunyikan sampai diminta (formulir lebih pendek).
                var showNotes by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(st.notes.isNotBlank()) }
                if (showNotes) {
                    OutlinedTextField(
                        st.notes, vm::setNotes, Modifier.fillMaxWidth(), label = { Text("Catatan (opsional)") },
                        placeholder = { Text("mis. disimpan di es, dibeli pagi") }, minLines = 2,
                    )
                } else {
                    TextButton(onClick = { showNotes = true }) { Text("+ Tambah catatan") }
                }
                st.error?.let { Notice(it) }
                PrimaryButton("Simpan", vm::save, icon = Icons.Outlined.Done, enabled = st.canSave)
            }

            CollectStep.SAVING -> Column(
                Modifier.fillMaxWidth().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                ContainedLoadingIndicator(Modifier.size(80.dp))
                Text("Menyimpan & menjalankan model…", style = MaterialTheme.typography.bodyMedium)
            }

            CollectStep.SAVED -> {
                val c = st.saved ?: return@ScreenColumn
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PlainIcon(Icons.Outlined.CheckCircle, Palette.Accent, 32.dp)
                    Text("Tersimpan di ponsel", style = MaterialTheme.typography.headlineSmall)
                }
                Text("Akan diunggah ke Supabase otomatis saat ada internet.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                Panel {
                    Eyebrow("Label Anda")
                    ClassBadge(c.label)
                }
                c.predictions.forEach { p ->
                    Panel {
                        Eyebrow("Prediksi ${p.scenarioName} · ${p.archName}")
                        val agree = p.top.cls == c.label
                        Text(
                            if (agree) "Model setuju dengan label Anda" else "Model memprediksi ${p.top.cls.labelId}",
                            style = MaterialTheme.typography.titleMedium, color = if (agree) Palette.Accent else Palette.Warning,
                        )
                        ScoreBars(p)
                    }
                }
                if (c.predictions.isEmpty()) Notice("Belum ada model yang siap, jadi prediksi tidak dihitung. Data tetap tersimpan.", warning = false)
                Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
                    PrimaryButton("Ikan berikutnya", { vm.finish(true); onAgain(false) }, icon = Icons.Outlined.AddAPhoto)
                    SecondaryButton("Foto lagi ikan yang sama", { vm.finish(false); onAgain(true) }, icon = FishEyeIcon)
                    TextButton(onClick = { vm.finish(false); onDone() }, Modifier.fillMaxWidth()) { Text("Selesai") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LabelOption(c: FreshnessClass, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        shapes = ButtonDefaults.shapes(),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.color() else Palette.Divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) c.softColor() else Palette.Panel),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.m),
    ) {
        Icon(c.icon(), null, Modifier.size(32.dp), tint = c.color())
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(c.labelId, style = MaterialTheme.typography.titleLarge, color = c.color())
            Text(c.hint(), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeciesField(value: String, recent: List<String>, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), label = { Text("Jenis ikan") }, singleLine = true,
            keyboardOptions = KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        val options = (recent + COMMON_SPECIES).distinctBy { it.lowercase() }.take(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            options.forEach { s ->
                FilterChip(
                    selected = value.equals(s, ignoreCase = true), onClick = { onChange(s) }, label = { Text(s) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.SoftAccent),
                )
            }
        }
    }
}
