package id.fishfresh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import id.fishfresh.ui.theme.Palette

/**
 * Logo "mata ikan segar": lingkaran biru polos berisi mata (kornea putih, iris toska, pupil, kilau).
 * Path SAMA dengan androidApp/res/drawable/ic_fish_logo.xml (grid 108, lingkaran r=36 di tengah).
 */
object FishEyePaths {
    const val DISC = "M18,54 a36,36 0 1,0 72,0 a36,36 0 1,0 -72,0 Z"
    const val SCLERA = "M27,54 a27,27 0 1,0 54,0 a27,27 0 1,0 -54,0 Z"
    const val IRIS = "M35,54 a19,19 0 1,0 38,0 a19,19 0 1,0 -38,0 Z"
    const val PUPIL = "M43.5,54 a10.5,10.5 0 1,0 21,0 a10.5,10.5 0 1,0 -21,0 Z"
    const val GLINT = "M56.4,46.5 a4.6,4.6 0 1,0 9.2,0 a4.6,4.6 0 1,0 -9.2,0 Z"
    const val GLINT2 = "M44.9,61.5 a2.1,2.1 0 1,0 4.2,0 a2.1,2.1 0 1,0 -4.2,0 Z"
    const val SPARKLE = "M76,25 Q77,31 83,32 Q77,33 76,39 Q75,33 69,32 Q75,31 76,25 Z"
}

/** Logo berwarna (lingkaran polos, tanpa bingkai/bentuk Material). */
val FishLogo: ImageVector by lazy {
    ImageVector.Builder("FishLogo", 72.dp, 72.dp, 72f, 72f).apply {
        group(translationX = -18f, translationY = -18f) {
            addPath(addPathNodes(FishEyePaths.DISC), fill = SolidColor(Palette.Primary))
            addPath(addPathNodes(FishEyePaths.SCLERA), fill = SolidColor(Color.White))
            addPath(addPathNodes(FishEyePaths.IRIS), fill = SolidColor(Palette.Accent))
            addPath(addPathNodes(FishEyePaths.PUPIL), fill = SolidColor(Palette.Text))
            addPath(addPathNodes(FishEyePaths.GLINT), fill = SolidColor(Color.White))
            addPath(addPathNodes(FishEyePaths.GLINT2), fill = SolidColor(Color.White), fillAlpha = 0.8f)
            addPath(addPathNodes(FishEyePaths.SPARKLE), fill = SolidColor(Palette.SoftAccent))
        }
    }.build()
}

/**
 * Ikon garis "mata ikan" 24dp untuk UI (Material Symbols tidak punya ikon mata ikan):
 * lingkaran luar (kornea), pupil bulat, kilau. Diwarnai lewat tint Icon().
 */
val FishEyeIcon: ImageVector by lazy {
    ImageVector.Builder("FishEye", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            addPathNodes("M12,2.5 a9.5,9.5 0 1,0 0.001,0 Z M12,4.5 a7.5,7.5 0 1,1 -0.001,0 Z"),
            fill = SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
        )
        addPath(
            addPathNodes("M12,7 a5,5 0 1,0 0.001,0 Z M14.2,8.6 a1.4,1.4 0 1,0 0.001,0 Z"),
            fill = SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
        )
    }.build()
}

/** Geometri panduan kamera, relatif terhadap lebar pratinjau (dipakai juga untuk memetakan ke foto). */
object GuideGeometry {
    /** Pusat lingkaran mata (fraksi lebar & tinggi pratinjau). */
    const val EYE_X = 0.5f
    const val EYE_Y = 0.46f
    /** Jari-jari lingkaran MATA = fraksi lebar. Crop = 1,10 x jari-jari ini (sama dengan data latih). */
    const val EYE_R = 0.11f
    const val CROP_MARGIN = 1.10f
}

/**
 * Overlay siluet kepala ikan (menghadap kiri) dengan lingkaran mata di tengah.
 * Area di luar lingkaran mata diredupkan agar perhatian tertuju ke mata.
 */
@Composable
fun FishHeadGuide(modifier: Modifier = Modifier, eyeColor: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val r = GuideGeometry.EYE_R * w
        val c = Offset(GuideGeometry.EYE_X * w, GuideGeometry.EYE_Y * size.height)
        val eye = Path().apply { addOval(Rect(c, r)) }

        clipPath(eye, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.35f)) }

        fun p(x: Float, y: Float) = Offset(c.x + x * r, c.y + y * r)
        val head = Path().apply {
            val a = p(3.3f, -3.6f); moveTo(a.x, a.y)
            val c1 = p(1.0f, -4.6f); val c2 = p(-2.8f, -2.8f); val s = p(-3.6f, 0.2f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, s.x, s.y)                       // dahi -> moncong
            val m1 = p(-3.0f, 0.55f); val m2 = p(-3.55f, 0.95f)
            lineTo(m1.x, m1.y); lineTo(m2.x, m2.y)                          // mulut
            val d1 = p(-3.2f, 2.2f); val d2 = p(-1.4f, 3.6f); val e = p(3.3f, 3.8f)
            cubicTo(d1.x, d1.y, d2.x, d2.y, e.x, e.y)                       // dagu -> perut
        }
        val gill = Path().apply {
            val g0 = p(2.4f, -3.1f); val gc = p(3.7f, 0.3f); val g1 = p(2.3f, 3.5f)
            moveTo(g0.x, g0.y); quadraticTo(gc.x, gc.y, g1.x, g1.y)
        }
        val outline = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shadow = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        listOf(head, gill).forEach {
            drawPath(it, Color.Black.copy(alpha = 0.25f), style = shadow)
            drawPath(it, Color.White.copy(alpha = 0.9f), style = outline)
        }
        // lingkaran mata (target) + batas crop (putus-putus) agar pengguna tahu area yang dipakai model
        drawCircle(eyeColor, r, c, style = Stroke(3.dp.toPx()))
        drawCircle(
            Color.White.copy(alpha = 0.7f), r * GuideGeometry.CROP_MARGIN, c,
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
        )
    }
}

private fun Rect(center: Offset, radius: Float) =
    androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))
