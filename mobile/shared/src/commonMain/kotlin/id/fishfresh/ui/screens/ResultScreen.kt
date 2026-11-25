package id.fishfresh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.Prediction
import id.fishfresh.domain.ScanRecord
import id.fishfresh.presentation.ResultViewModel
import id.fishfresh.presentation.ScanUiState
import id.fishfresh.presentation.ScanViewModel
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.ScoreBars
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.components.Section
import id.fishfresh.ui.components.fmt
import id.fishfresh.ui.components.icon
import id.fishfresh.ui.components.pct
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import id.fishfresh.ui.theme.color

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnalyzeScreen(vm: ScanViewModel, onDone: (Long) -> Unit, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { if (st is ScanUiState.Idle) vm.analyze() }
    LaunchedEffect(st) { (st as? ScanUiState.Done)?.let { vm.reset(); onDone(it.scanId) } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = st) {
            is ScanUiState.Failed -> {
                Notice(s.message)
                PrimaryButton("Coba lagi", { vm.analyze() })
                SecondaryButton("Kembali", { vm.reset(); onBack() })
            }
            is ScanUiState.Working -> {
                ContainedLoadingIndicator(Modifier.size(88.dp))
                Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text("Diproses di ponsel, tanpa internet.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            else -> ContainedLoadingIndicator(Modifier.size(88.dp))
        }
    }
}

@Composable
fun ResultScreen(
    vm: ResultViewModel,
    formatTime: (Long) -> String,
    onScanAgain: () -> Unit,
    onSaveAsData: (ScanRecord) -> Unit,
) {
    val rec by vm.record.collectAsState()
    val r = rec ?: return
    val multi = r.predictions.size > 1
    ScreenColumn {
        r.predictions.firstOrNull()?.let { Headline(r, it, formatTime) }
        if (r.predictions.any { it.modelId == "demo" }) Notice("Model demo belum dilatih: skor belum bermakna.")

        if (multi) {
            AgreementNote(r.predictions)
            r.predictions.forEach { p ->
                Section(title = "${p.archName} · ${p.top.cls.labelId}") { ScoreBars(p) }
            }
        } else {
            r.predictions.firstOrNull()?.let { p -> Section(title = "Skor semua kelas") { ScoreBars(p) } }
        }

        Section(title = "Detail teknis") {
            val first = r.predictions.firstOrNull()
            if (first != null) {
                KeyValue("Skenario", first.scenarioName)
                KeyValue("Input", if (first.inputVariant == "eye_crop") "crop mata" else "citra utuh")
                KeyValue("Thread · kuantisasi", "${first.threads} · ${first.quantization}")
                Hairline()
            }
            r.predictions.forEach { p ->
                KeyValue(if (multi) p.archName else "Praproses · inferensi",
                    "${fmt(p.preprocessMs)} ms · ${fmt(p.inferenceMs)} ms")
            }
            KeyValue("Versi model", first?.modelVersion?.take(19) ?: "-")
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
            PrimaryButton("Pindai lagi", onScanAgain, icon = Icons.Outlined.CameraAlt)
            SecondaryButton("Simpan sebagai data primer", { onSaveAsData(r) }, icon = Icons.Outlined.AddAPhoto)
        }
    }
}

/** Jawaban utama lebih dulu (kelas + keyakinan), detail di bawahnya. */
@Composable
private fun Headline(r: ScanRecord, p: Prediction, formatTime: (Long) -> String) {
    val top = p.top
    Row(
        Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        EyeThumb(r.eyePath, 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(if (r.predictions.size > 1) "Prediksi ${p.archName}" else "Prediksi")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(top.cls.icon(), null, Modifier.size(26.dp), tint = top.cls.color())
                Spacer(Modifier.width(Space.s))
                Text(top.cls.labelId, style = MaterialTheme.typography.headlineMedium, color = top.cls.color())
            }
            Text("Skor ${pct(top.score)} · unggul ${pct(p.margin)} dari kelas kedua",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            Text(formatTime(r.createdAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

/** Dipakai juga di layar lain untuk menampilkan satu prediksi lengkap. */
@Composable
fun PredictionCard(p: Prediction, showHeader: Boolean) = Section(
    title = if (showHeader) "${p.scenarioName} · ${p.archName}" else "Skor semua kelas",
) {
    ScoreBars(p)
    Hairline()
    KeyValue("Praproses · inferensi", "${fmt(p.preprocessMs)} ms · ${fmt(p.inferenceMs)} ms")
    KeyValue("Input · thread", "${if (p.inputVariant == "eye_crop") "crop mata" else "citra utuh"} · ${p.threads}")
}

@Composable
private fun AgreementNote(ps: List<Prediction>) {
    val labels = ps.map { it.top.cls }.distinct()
    Notice(
        if (labels.size == 1) "Ketiga arsitektur sepakat: ${labels.first().labelId}."
        else "Arsitektur tidak sepakat: " + ps.joinToString { "${it.archName} ${it.top.cls.labelId}" } + ".",
        warning = labels.size > 1,
    )
}
