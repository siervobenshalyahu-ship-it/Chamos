/*
 * Chamos: tema neón (noche, azul y violeta) para envolver cualquier pantalla.
 */

package io.element.android.libraries.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.SemanticColors
import io.element.android.compound.tokens.generated.compoundColorsDark

val ChamosNeonAzul = Color(0xFF29C5FF)
val ChamosNeonVioleta = Color(0xFF9B5CFF)

/** Colores neón de Chamos sobre la paleta oscura de Compound. */
fun chamosNeonColors(base: SemanticColors = compoundColorsDark): SemanticColors = base.copy(
    bgCanvasDefault = Color(0xFF0A0E2B),
    bgCanvasDefaultLevel1 = Color(0xFF121843),
    bgSubtleSecondary = Color(0xFF151B4A),
    bgSubtleSecondaryLevel0 = Color(0xFF121843),
    bgActionPrimaryRest = ChamosNeonVioleta,
    bgActionPrimaryPressed = ChamosNeonAzul,
    bgActionPrimaryDisabled = Color(0xFF3A3F6B),
    textPrimary = Color.White,
    textSecondary = Color(0xFFB8C0FF),
    textActionAccent = ChamosNeonAzul,
    iconAccentPrimary = ChamosNeonAzul,
    borderFocused = ChamosNeonAzul,
    borderInteractivePrimary = ChamosNeonVioleta,
    borderInteractiveSecondary = Color(0xFF4A52A0),
)

@Composable
fun ChamosNeonTema(content: @Composable () -> Unit) {
    ElementTheme(
        theme = Theme.Dark,
        applySystemBarsUpdate = false,
        compoundDark = chamosNeonColors(),
        content = content,
    )
}
