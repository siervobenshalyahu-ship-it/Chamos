/*
 * Chamos: tema neón (noche, azul y violeta) para envolver cualquier pantalla.
 */

package io.element.android.libraries.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.SemanticColors
import io.element.android.compound.tokens.generated.compoundColorsDark

/** Estilo elegido: false = Adán (neón azul y violeta), true = Eva (fucsia, rosa y violeta). */
object ChamosModo {
    var eva by mutableStateOf(false)
}

val ChamosNeonAzul: Color get() = if (ChamosModo.eva) Color(0xFFFF7AD9) else Color(0xFF29C5FF)
val ChamosNeonVioleta: Color get() = if (ChamosModo.eva) Color(0xFFE040FB) else Color(0xFF9B5CFF)
val ChamosFondo0: Color get() = if (ChamosModo.eva) Color(0xFF2D0A40) else Color(0xFF0A0E2B)
val ChamosFondo1: Color get() = if (ChamosModo.eva) Color(0xFF4A1163) else Color(0xFF121843)
val ChamosFondo2: Color get() = if (ChamosModo.eva) Color(0xFF5E1A7A) else Color(0xFF151B4A)
val ChamosTinta: Color get() = if (ChamosModo.eva) Color(0xFF2A0630) else Color(0xFF06081A)
val ChamosTextoSuave: Color get() = if (ChamosModo.eva) Color(0xFFFFC9F0) else Color(0xFFB8C0FF)

/** Colores de Chamos (Adán o Eva) sobre la paleta oscura de Compound. */
fun chamosNeonColors(base: SemanticColors = compoundColorsDark): SemanticColors = base.copy(
    bgCanvasDefault = ChamosFondo0,
    bgCanvasDefaultLevel1 = ChamosFondo1,
    bgSubtleSecondary = ChamosFondo2,
    bgSubtleSecondaryLevel0 = ChamosFondo1,
    bgActionPrimaryRest = ChamosNeonVioleta,
    bgActionPrimaryPressed = ChamosNeonAzul,
    bgActionPrimaryDisabled = Color(0xFF3A3F6B),
    textPrimary = Color.White,
    textSecondary = ChamosTextoSuave,
    textActionAccent = ChamosNeonAzul,
    iconAccentPrimary = ChamosNeonAzul,
    borderFocused = ChamosNeonAzul,
    borderInteractivePrimary = ChamosNeonVioleta,
    borderInteractiveSecondary = if (ChamosModo.eva) Color(0xFFB04DB0) else Color(0xFF4A52A0),
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
