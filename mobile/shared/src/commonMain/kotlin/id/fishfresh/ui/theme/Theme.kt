package id.fishfresh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.resources.Res
import id.fishfresh.resources.plus_jakarta_sans_bold
import id.fishfresh.resources.plus_jakarta_sans_medium
import id.fishfresh.resources.plus_jakarta_sans_regular
import id.fishfresh.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/** Palet merek (dari pemilik). Satu-satunya tambahan: merah & kuning untuk peringatan. */
object Palette {
    val Background = Color(0xFFFFFFFF)   // latar utama
    val Panel = Color(0xFFF8FAFC)        // latar kartu & panel
    val Primary = Color(0xFF0284C7)      // tombol scan, navigasi aktif
    val PrimaryPressed = Color(0xFF0369A1)
    val Accent = Color(0xFF0D9488)       // ikon fitur, grafik, penanda aktif
    val SoftAccent = Color(0xFFE0F2FE)   // latar ikon / badge kategori
    val Text = Color(0xFF0F172A)
    val TextMuted = Color(0xFF64748B)
    val Divider = Color(0xFFE2E8F0)
    // tambahan semantik
    val Danger = Color(0xFFDC2626)
    val DangerSoft = Color(0xFFFEF2F2)
    val AccentSoft = Color(0xFFF0FDFA)
    val Warning = Color(0xFFB45309)
    val WarningSoft = Color(0xFFFFFBEB)
}

/** Warna per kelas. Warna tidak pernah dipakai sendirian: selalu disertai teks + ikon. */
fun FreshnessClass.color(): Color = when (this) {
    FreshnessClass.HIGHLY_FRESH -> Palette.Accent
    FreshnessClass.FRESH -> Palette.Primary
    FreshnessClass.NOT_FRESH -> Palette.Danger
}

fun FreshnessClass.softColor(): Color = when (this) {
    FreshnessClass.HIGHLY_FRESH -> Palette.AccentSoft
    FreshnessClass.FRESH -> Palette.SoftAccent
    FreshnessClass.NOT_FRESH -> Palette.DangerSoft
}

/**
 * Skala jarak 4-pt. Aturan tata letak (dipakai semua layar lewat ScreenColumn/Section):
 * - tepi layar kiri-kanan = [screen] (20dp)
 * - jarak antar-bagian    = [section] (24dp)
 * - padding dalam kartu   = [card] (16dp), jarak antar-isi kartu = [item] (12dp)
 * - jarak antar-tombol bertumpuk = [item] (12dp)
 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
    val section = 24.dp
    val card = 16.dp
    val item = 12.dp
    val buttonHeight = 56.dp
}

private val colors = lightColorScheme(
    primary = Palette.Primary,
    onPrimary = Color.White,
    primaryContainer = Palette.SoftAccent,
    onPrimaryContainer = Palette.PrimaryPressed,
    secondary = Palette.Accent,
    onSecondary = Color.White,
    secondaryContainer = Palette.AccentSoft,
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = Palette.Accent,
    onTertiary = Color.White,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Panel,
    onSurfaceVariant = Palette.TextMuted,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Palette.Divider,
    surfaceBright = Palette.Background,
    surfaceDim = Palette.Panel,
    // outline = batas komponen interaktif -> perlu kontras >= 3:1
    outline = Color(0xFF94A3B8),
    outlineVariant = Palette.Divider,
    error = Palette.Danger,
    onError = Color.White,
    errorContainer = Palette.DangerSoft,
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Palette.Text,
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFF7DD3FC),
    scrim = Color(0xFF0F172A),
)

@Composable
private fun jakarta() = FontFamily(
    Font(Res.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/** Skala tipe ringkas: hanya 7 gaya yang dipakai di aplikasi agar hierarki mudah dibaca. */
@Composable
private fun typography(): Typography {
    val f = jakarta()
    fun s(size: Int, line: Int, w: FontWeight, ls: Double = 0.0) =
        TextStyle(fontFamily = f, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = ls.sp)
    return Typography(
        displaySmall = s(32, 40, FontWeight.Bold, -0.5),
        headlineLarge = s(28, 36, FontWeight.Bold, -0.4),
        headlineMedium = s(26, 34, FontWeight.Bold, -0.3),     // kelas hasil
        headlineSmall = s(22, 30, FontWeight.Bold, -0.2),      // judul layar
        titleLarge = s(20, 28, FontWeight.SemiBold),           // judul top bar
        titleMedium = s(16, 24, FontWeight.SemiBold),          // judul kartu/baris
        titleSmall = s(14, 20, FontWeight.SemiBold),
        bodyLarge = s(16, 24, FontWeight.Normal),
        bodyMedium = s(14, 21, FontWeight.Normal),
        bodySmall = s(13, 19, FontWeight.Normal),              // keterangan (abu)
        labelLarge = s(15, 20, FontWeight.SemiBold),           // tombol
        labelMedium = s(12, 16, FontWeight.SemiBold, 0.4),     // eyebrow/lencana
        labelSmall = s(11, 16, FontWeight.Medium, 0.3),
    )
}

/** Angka skor memakai monospace agar kolom persentase rata dan mudah dibandingkan. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Text)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IkanSegarTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = typography(),
        shapes = shapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
