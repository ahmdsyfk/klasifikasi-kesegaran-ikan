package id.fishfresh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import id.fishfresh.domain.Prediction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.fishfresh.domain.Capture
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.domain.ScanRecord
import id.fishfresh.domain.SyncState
import id.fishfresh.presentation.CaptureDetailViewModel
import id.fishfresh.presentation.DataViewModel
import id.fishfresh.presentation.HistoryViewModel
import id.fishfresh.ui.components.ClassBadge
import id.fishfresh.ui.components.Eyebrow
import id.fishfresh.ui.components.ScreenHeader
import id.fishfresh.ui.components.ScreenColumn
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.Panel
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.ScoreBars
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.components.fmt
import id.fishfresh.ui.components.icon
import id.fishfresh.ui.theme.NumberStyle
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import id.fishfresh.ui.theme.color

// ------------------------------------------------------------------ Tab Data Primer
@Composable
fun DataScreen(vm: DataViewModel, formatTime: (Long) -> String, onCollect: () -> Unit, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.section), modifier = Modifier.padding(bottom = Space.section)) {
                Text(
                    (if (st.settings.dataset == "primer_2") "Dataset Primer 2" else "Dataset Primer 1") +
                        " · foto mata ikan dari pasar, diberi label oleh Anda.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted,
                )
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        FreshnessClass.entries.forEach { c -> CountTile(c, st.counts[c] ?: 0, Modifier.weight(1f)) }
                    }
                    Text("${st.captures.orEmpty().size} foto dari ${st.fishCount} ikan", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                PrimaryButton("Ambil data primer", onCollect, icon = Icons.Outlined.AddAPhoto)
                SyncRow(st.pending, st.syncConfigured, vm::syncNow)
                if (!st.captures.isNullOrEmpty()) Eyebrow("Foto terbaru")
            }
        }
        val list = st.captures
        if (list != null && list.isEmpty()) item {
            Text("Belum ada data. Foto satu mata per jepretan; beberapa foto dari ikan yang sama otomatis dikelompokkan.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(Space.xl))
        }
        items(list.orEmpty(), key = { it.id }) { c -> CaptureRow(c, formatTime) { onOpen(c.id) } }
    }
}

@Composable
private fun CountTile(c: FreshnessClass, n: Int, modifier: Modifier) = Panel(modifier, padding = Space.m) {
    PlainIcon(c.icon(), c.color(), 20.dp)
    Text("$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(c.labelId, style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
}

@Composable
private fun SyncRow(pending: Int, configured: Boolean, onSync: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlainIcon(
            when {
                !configured -> Icons.Outlined.CloudOff
                pending > 0 -> Icons.Outlined.CloudUpload
                else -> Icons.Outlined.CloudDone
            },
            if (pending > 0 || !configured) Palette.Warning else Palette.Accent, 22.dp,
        )
        Spacer(Modifier.width(Space.s))
        Text(
            when {
                !configured -> "Supabase belum dikonfigurasi di build ini"
                pending > 0 -> "$pending foto menunggu diunggah"
                else -> "Semua foto sudah terunggah"
            },
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
        )
        if (configured && pending > 0) TextButton(onClick = onSync) { Text("Unggah") }
    }
}

@Composable
private fun CaptureRow(c: Capture, formatTime: (Long) -> String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        EyeThumb(c.eyePath, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${c.species} · ${c.fishCode}", style = MaterialTheme.typography.titleSmall)
            Text(listOf(formatTime(c.capturedAt), c.market).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            ClassBadge(c.label)
        }
        PlainIcon(
            when (c.syncState) {
                SyncState.SYNCED -> Icons.Outlined.CloudDone
                SyncState.FAILED -> Icons.Outlined.CloudOff
                SyncState.PENDING -> Icons.Outlined.CloudSync
            },
            if (c.syncState == SyncState.SYNCED) Palette.Accent else Palette.TextMuted, 20.dp,
            contentDescription = c.syncState.name,
        )
    }
    Hairline()
}

@Composable
fun CaptureDetailScreen(vm: CaptureDetailViewModel, formatTime: (Long) -> String, onDeleted: () -> Unit) {
    val c by vm.capture.collectAsState()
    val cap = c ?: return
    var confirm by remember { mutableStateOf(false) }
    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            EyeThumb(cap.eyePath, 112.dp)
            AsyncImage("file://${cap.photo.path}", null, Modifier.weight(1f).padding(0.dp), contentScale = ContentScale.FillWidth)
        }
        Panel {
            Eyebrow("Label (bisa dikoreksi)")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FreshnessClass.entries.forEach { l ->
                    androidx.compose.material3.FilterChip(
                        selected = cap.label == l, onClick = { vm.relabel(l) }, label = { Text(l.labelId) },
                        leadingIcon = { PlainIcon(l.icon(), l.color(), 18.dp) },
                    )
                }
            }
            Hairline()
            KeyValue("Ikan", cap.fishCode)
            KeyValue("Jenis", cap.species)
            if (cap.market.isNotBlank()) KeyValue("Pasar", cap.market)
            KeyValue("Waktu", formatTime(cap.capturedAt))
            KeyValue("Foto", "${cap.photo.width}×${cap.photo.height} px")
            KeyValue("Lingkaran", "(${fmt(cap.circle.cx.toDouble())}, ${fmt(cap.circle.cy.toDouble())}) r ${fmt(cap.circle.r.toDouble())}" +
                if (cap.circleAdjusted) " · diatur" else "")
            cap.sharpness?.let { KeyValue("Ketajaman (var. Laplacian)", fmt(it)) }
            cap.brightness?.let { KeyValue("Kecerahan mata", fmt(it)) }
            KeyValue("Sinkron", when (cap.syncState) { SyncState.SYNCED -> "terunggah"; SyncState.PENDING -> "menunggu"; SyncState.FAILED -> "gagal" })
            cap.syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Danger) }
        }
        cap.predictions.forEach { PredictionCard(it, showHeader = true) }
        SecondaryButton("Hapus foto ini", { confirm = true }, icon = Icons.Outlined.DeleteOutline)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Hapus foto?") },
        text = { Text("Foto dihapus dari ponsel dan dari Supabase (bila sudah terunggah).") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.delete(); onDeleted() }) { Text("Hapus", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
    )
}

// ------------------------------------------------------------------ Tab Riwayat
@Composable
fun HistoryScreen(vm: HistoryViewModel, formatTime: (Long) -> String, onOpen: (Long) -> Unit) {
    val items by vm.items.collectAsState()
    val grid by vm.grid.collectAsState()
    val list = items
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (grid) 2 else 1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl),
        horizontalArrangement = Arrangement.spacedBy(Space.item),
        verticalArrangement = Arrangement.spacedBy(Space.item),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (list.isNullOrEmpty()) "Hasil pindai tersimpan di ponsel." else "${list.size} hasil pindai tersimpan di ponsel.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted,
                )
                ViewToggle(grid, vm::setGrid)
            }
        }
        if (list != null && list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                PlainIcon(Icons.Outlined.History, Palette.TextMuted, 40.dp)
                Text("Belum ada hasil pindai.", color = Palette.TextMuted)
            }
        }
        items(list.orEmpty(), key = { it.id }) { r ->
            if (grid) HistoryCard(r, formatTime) { onOpen(r.id) } else HistoryRow(r, formatTime) { onOpen(r.id) }
        }
    }
}

/** Pengalih kartu/list: dua ikon polos, yang aktif berwarna aksen. */
@Composable
private fun ViewToggle(grid: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.clip(MaterialTheme.shapes.small).border(1.dp, Palette.Divider, MaterialTheme.shapes.small),
) {
    listOf(false to Icons.AutoMirrored.Outlined.ViewList, true to Icons.Outlined.GridView).forEach { (g, ic) ->
        val on = g == grid
        Box(
            Modifier.size(44.dp).background(if (on) Palette.SoftAccent else Color.Transparent).clickable { onChange(g) },
            contentAlignment = Alignment.Center,
        ) { PlainIcon(ic, if (on) Palette.Primary else Palette.TextMuted, 22.dp, if (g) "Tampilan kartu" else "Tampilan list") }
    }
}

/** Satu garis berisi ketiga skor kelas (lebar = skor), urutan tetap agar mudah dibandingkan antar-item. */
@Composable
private fun ScoreStrip(p: Prediction, modifier: Modifier = Modifier) = Row(
    modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Divider),
) {
    FreshnessClass.entries.forEach { c ->
        val v = p.scoreOf(c)
        if (v > 0.005f) Box(Modifier.weight(v).fillMaxHeight().background(c.color()))
    }
}

@Composable
private fun ScoreLegend(p: Prediction) = Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
    FreshnessClass.entries.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(c.color()))
            Text(id.fishfresh.ui.components.pct(p.scoreOf(c), 0), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        }
    }
}

private fun modelLabel(r: ScanRecord, p: Prediction) =
    if (r.predictions.size > 1) "${p.scenarioName} · ${r.predictions.size} arsitektur" else "${p.archName} · ${p.scenarioName}"

private val cardModifier: Modifier
    @Composable get() = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(Color.White)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.large)

/** Mode list: ringkas, satu baris per hasil. */
@Composable
private fun HistoryRow(r: ScanRecord, formatTime: (Long) -> String, onClick: () -> Unit) {
    val p = r.predictions.firstOrNull() ?: return
    Row(
        cardModifier.clickable(onClick = onClick).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        EyeThumb(r.eyePath, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlainIcon(p.top.cls.icon(), p.top.cls.color(), 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(p.top.cls.labelId, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = p.top.cls.color())
                Text(id.fishfresh.ui.components.pct(p.top.score, 1), style = NumberStyle, fontWeight = FontWeight.Bold)
            }
            ScoreStrip(p)
            Text("${modelLabel(r, p)} · ${formatTime(r.createdAt)}", style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Mode kartu: foto mata besar di atas, kelas dan ketiga skor di bawahnya. */
@Composable
private fun HistoryCard(r: ScanRecord, formatTime: (Long) -> String, onClick: () -> Unit) {
    val p = r.predictions.firstOrNull() ?: return
    Column(cardModifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black)) {
            if (r.eyePath != null) AsyncImage("file://${r.eyePath}", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                id.fishfresh.ui.components.pct(p.top.score, 1),
                Modifier.align(Alignment.TopEnd).padding(Space.s).clip(RoundedCornerShape(8.dp))
                    .background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                style = NumberStyle, fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlainIcon(p.top.cls.icon(), p.top.cls.color(), 16.dp)
                Spacer(Modifier.width(4.dp))
                Text(p.top.cls.labelId, style = MaterialTheme.typography.titleSmall, color = p.top.cls.color(), maxLines = 1)
            }
            ScoreStrip(p)
            ScoreLegend(p)
            Text(modelLabel(r, p), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(r.createdAt), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        }
    }
}
