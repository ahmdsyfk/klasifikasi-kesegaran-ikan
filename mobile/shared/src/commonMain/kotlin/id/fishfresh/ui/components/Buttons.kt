package id.fishfresh.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.fishfresh.ui.theme.Palette

/**
 * Tombol aksi utama: biru laut #0284C7, saat ditekan #0369A1, bentuk berubah saat ditekan
 * (M3 Expressive ButtonDefaults.shapes()).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 60.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Button(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(height), enabled = enabled,
        shapes = ButtonDefaults.shapes(), interactionSource = source,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) Palette.PrimaryPressed else Palette.Primary,
            contentColor = Color.White,
        ),
    ) {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = OutlinedButton(
    onClick = onClick, modifier = modifier.fillMaxWidth().height(52.dp), enabled = enabled,
    shapes = ButtonDefaults.shapes(),
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Palette.PrimaryPressed),
) {
    if (icon != null) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}
