/*
 * Chamos: estilo neón (azul y violeta) para todas las pantallas de entrada, inicio de sesión y añadir cuenta.
 */

package io.element.android.features.login.impl.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.compoundColorsDark
import io.element.android.libraries.designsystem.theme.components.Text

internal val ChamosAzulNeon = Color(0xFF29C5FF)
internal val ChamosVioletaNeon = Color(0xFF9B5CFF)
internal val ChamosFondoNoche = Color(0xFF0A0E2B)
internal val ChamosDegradadoNeon = Brush.horizontalGradient(listOf(ChamosAzulNeon, ChamosVioletaNeon))

/** Tema oscuro con los colores de Chamos: lo usan todas las pantallas de entrada. */
@Composable
internal fun ChamosLoginTema(content: @Composable () -> Unit) {
    val c = compoundColorsDark
    val neon = c.copy(
        bgCanvasDefault = ChamosFondoNoche,
        bgCanvasDefaultLevel1 = Color(0xFF121843),
        bgSubtleSecondary = Color(0xFF151B4A),
        bgSubtleSecondaryLevel0 = Color(0xFF121843),
        bgActionPrimaryRest = ChamosVioletaNeon,
        bgActionPrimaryPressed = ChamosAzulNeon,
        bgActionPrimaryDisabled = Color(0xFF3A3F6B),
        textPrimary = Color.White,
        textSecondary = Color(0xFFB8C0FF),
        textActionAccent = ChamosAzulNeon,
        iconAccentPrimary = ChamosAzulNeon,
        borderFocused = ChamosAzulNeon,
        borderInteractivePrimary = ChamosVioletaNeon,
        borderInteractiveSecondary = Color(0xFF4A52A0),
    )
    ElementTheme(
        theme = Theme.Dark,
        applySystemBarsUpdate = false,
        compoundDark = neon,
        content = content,
    )
}

/** Botón principal: píldora con degradado neón azul → violeta. */
@Composable
internal fun ChamosBotonNeon(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showProgress: Boolean = false,
) {
    val forma = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(forma)
            .background(if (enabled) ChamosDegradadoNeon else Brush.horizontalGradient(listOf(Color(0xFF3A3F6B), Color(0xFF3A3F6B))), forma)
            .clickable(enabled = enabled && !showProgress, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color(0xFF06081A), strokeWidth = 2.5.dp)
        } else {
            Text(text = text, color = if (enabled) Color(0xFF06081A) else Color(0xFF9AA0D0), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Botón secundario: contorno neón, fondo transparente. */
@Composable
internal fun ChamosBotonContorno(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val forma = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(forma)
            .border(BorderStroke(1.6.dp, ChamosDegradadoNeon), forma)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
