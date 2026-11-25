package id.fishfresh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

/**
 * Kerangka isi layar yang SAMA untuk semua layar: tepi 20dp, jarak antar-bagian 24dp,
 * bisa digulir, ruang bawah 32dp agar tombol terakhir tidak menempel tepi.
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier.fillMaxSize()
        .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
        .padding(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl),
    verticalArrangement = Arrangement.spacedBy(Space.section),
    content = content,
)

/** Judul layar tab: eyebrow kecil (konteks) + judul + satu kalimat penjelas (opsional). */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) = Row(modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        eyebrow?.let { Eyebrow(it) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted) }
    }
    trailing()
}

/** Bagian berjudul: judul kecil di luar kartu, isi di dalam Panel. */
@Composable
fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
    if (title != null) Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(title, Modifier.weight(1f))
        action()
    }
    Panel(content = content)
}

/** Baris navigasi dalam kartu: ikon polos + judul + keterangan + panah. Target sentuh >= 48dp. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
) {
    PlainIcon(icon, Palette.Accent, 22.dp)
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted) }
    }
    PlainIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, Palette.TextMuted, 22.dp)
}
