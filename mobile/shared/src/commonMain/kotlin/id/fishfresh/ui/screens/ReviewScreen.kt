package id.fishfresh.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.fishfresh.domain.EyeCircle
import id.fishfresh.presentation.CaptureMode
import id.fishfresh.presentation.CaptureSession
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

/**
 * Periksa posisi mata sebelum dipakai. Geser lingkaran dengan jari, atur ukuran dengan penggeser.
 * Lingkaran = batas crop yang dikirim ke model (sudah termasuk margin 1,10).
 */
@Composable
fun ReviewScreen(session: CaptureSession, onRetake: () -> Unit, onContinue: () -> Unit) {
    val photo = session.photo ?: return
    var circle by remember { mutableStateOf(session.circle ?: CaptureSession.defaultCircle(photo.width, photo.height, true)) }
    var adjusted by remember { mutableStateOf(session.adjusted) }
    val maxR = minOf(photo.width, photo.height) * 0.6f
    val minR = minOf(photo.width, photo.height) * 0.04f

    Column(
        Modifier.fillMaxSize().background(Palette.Background)
            .padding(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.item),
    ) {
        Text("Geser lingkaran agar pas di mata. Area di luar lingkaran tidak dipakai model.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        if (session.fromGallery) Notice("Foto dari galeri: atur lingkaran agar pas di mata ikan.", warning = false)

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = minOf(boxW / photo.width, boxH / photo.height)
            val shown = Offset(photo.width * scale, photo.height * scale)
            val origin = Offset((boxW - shown.x) / 2, (boxH - shown.y) / 2)
            AsyncImage("file://${photo.path}", null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(
                Modifier.fillMaxSize().pointerInput(photo.path) {
                    detectDragGestures { _, drag ->
                        circle = circle.copy(
                            cx = (circle.cx + drag.x / scale).coerceIn(0f, photo.width.toFloat()),
                            cy = (circle.cy + drag.y / scale).coerceIn(0f, photo.height.toFloat()),
                        )
                        adjusted = true
                    }
                },
            ) {
                val c = origin + Offset(circle.cx * scale, circle.cy * scale)
                val r = circle.r * scale
                val hole = Path().apply { addOval(circleRect(c, r)) }
                clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.45f)) }
                drawCircle(Color.White, r, c, style = Stroke(3.dp.toPx()))
                drawCircle(Palette.Accent, r / 1.1f, c, style = Stroke(1.5.dp.toPx()))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PlainIcon(Icons.Outlined.PhotoSizeSelectSmall, Palette.TextMuted, 20.dp)
            Slider(
                value = circle.r, onValueChange = { circle = circle.copy(r = it); adjusted = true },
                valueRange = minR..maxR, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.Primary, activeTrackColor = Palette.Primary, inactiveTrackColor = Palette.Divider),
            )
        }

        PrimaryButton(
            if (session.mode == CaptureMode.SCAN) "Analisis" else "Lanjut: beri label",
            onClick = {
                session.circle = circle
                session.adjusted = adjusted
                onContinue()
            },
            icon = if (session.mode == CaptureMode.SCAN) Icons.Outlined.Insights else Icons.AutoMirrored.Outlined.ArrowForward,
        )
        SecondaryButton("Ulangi foto", onRetake, icon = Icons.Outlined.Replay)
    }
}

private fun circleRect(c: Offset, r: Float) =
    androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)

/** Gambar crop mata bulat (PNG 224 bermasker) untuk pratinjau/daftar. */
@Composable
fun EyeThumb(path: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).aspectRatio(1f).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black)) {
        if (path != null) AsyncImage("file://$path", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
