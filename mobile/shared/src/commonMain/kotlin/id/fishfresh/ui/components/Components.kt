package id.fishfresh.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.domain.Prediction
import id.fishfresh.ui.theme.NumberStyle
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import id.fishfresh.ui.theme.color
import id.fishfresh.ui.theme.softColor

fun FreshnessClass.icon(): ImageVector = when (this) {
    FreshnessClass.HIGHLY_FRESH -> Icons.Outlined.Verified
    FreshnessClass.FRESH -> Icons.Outlined.CheckCircle
    FreshnessClass.NOT_FRESH -> Icons.Outlined.ReportProblem
}

/** Format persen Indonesia, 2 desimal: 71,23 % */
fun pct(v: Float, decimals: Int = 2): String {
    val p = v * 100
    val factor = if (decimals == 2) 100 else 10
    val rounded = kotlin.math.round(p * factor) / factor
    val s = rounded.toString().let { if ('.' in it) it else "$it.0" }
    val (a, b) = s.split('.')
    return "$a,${b.padEnd(decimals, '0').take(decimals)} %"
}

fun fmt(v: Double, decimals: Int = 1): String {
    val f = if (decimals == 1) 10.0 else if (decimals == 2) 100.0 else 1000.0
    val s = (kotlin.math.round(v * f) / f).toString()
    return s.replace('.', ',')
}

/** Panel datar berlatar abu es, bergaris tipis (pemisah kartu hasil). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Palette.Panel,
    padding: Dp = Space.card,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(color)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.large).padding(padding),
    verticalArrangement = Arrangement.spacedBy(Space.item),
    content = content,
)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium,
    color = Palette.TextMuted, fontWeight = FontWeight.SemiBold,
)

@Composable
fun Hairline() = HorizontalDivider(color = Palette.Divider, thickness = 1.dp)

/** Ikon polos tanpa wadah/border radius (permintaan pemilik). */
@Composable
fun PlainIcon(icon: ImageVector, tint: Color = Palette.Accent, size: Dp = 24.dp, contentDescription: String? = null) =
    Icon(icon, contentDescription, Modifier.size(size), tint = tint)

/** Lencana kelas: teks + ikon + warna (tidak bergantung pada warna saja). */
@Composable
fun ClassBadge(cls: FreshnessClass, modifier: Modifier = Modifier) = Row(
    modifier.clip(RoundedCornerShape(50)).background(cls.softColor()).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
    Icon(cls.icon(), null, Modifier.size(16.dp), tint = cls.color())
    Text(cls.labelId, style = MaterialTheme.typography.labelMedium, color = cls.color(), fontWeight = FontWeight.SemiBold)
}

/**
 * SEMUA skor kelas (urutan tetap: Sangat Segar, Segar, Tidak Segar) agar mudah dibandingkan
 * antar foto/model. Nilai softmax mentah ditampilkan 4 desimal untuk keperluan penelitian.
 */
@Composable
fun ScoreBars(p: Prediction, modifier: Modifier = Modifier, compact: Boolean = false) {
    val top = p.top.cls
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        FreshnessClass.entries.forEach { cls ->
            val v = p.scoreOf(cls)
            val anim by animateFloatAsState(v)
            val isTop = cls == top
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(cls.icon(), null, Modifier.size(16.dp), tint = cls.color())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        cls.labelId, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isTop) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(pct(v), style = NumberStyle, fontWeight = if (isTop) FontWeight.Bold else FontWeight.Medium)
                    if (!compact) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            v.toString().take(6), Modifier.widthIn(min = 52.dp), style = NumberStyle,
                            color = Palette.TextMuted, textAlign = TextAlign.End,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(if (compact) 6.dp else 8.dp).clip(CircleShape).background(Palette.Divider)) {
                    Box(
                        Modifier.fillMaxWidth(anim.coerceIn(0f, 1f)).height(if (compact) 6.dp else 8.dp)
                            .clip(CircleShape).background(if (isTop) cls.color() else cls.color().copy(alpha = 0.45f)),
                    )
                }
            }
        }
    }
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier, warning: Boolean = true) = Row(
    modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .background(if (warning) Palette.WarningSoft else Palette.SoftAccent).padding(Space.m),
    horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
) {
    Icon(
        if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null,
        tint = if (warning) Palette.Warning else Palette.Primary,
    )
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        // Label tidak pernah dipotong; nilai panjang turun ke baris berikutnya, rata kanan.
        Text(key, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, softWrap = false)
        Text(
            value, Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
        )
    }
