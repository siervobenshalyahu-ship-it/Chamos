/*
 * Chamos: la sala de chat con el estilo neón (azul y violeta) de Chamos.
 */

package io.element.android.features.messages.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.compoundColorsDark

internal val ChamosAzulSala = Color(0xFF29C5FF)
internal val ChamosVioletaSala = Color(0xFF9B5CFF)

@Composable
internal fun ChamosSalaTema(content: @Composable () -> Unit) {
    val c = compoundColorsDark
    val neon = c.copy(
        bgCanvasDefault = Color(0xFF0A0E2B),
        bgCanvasDefaultLevel1 = Color(0xFF121843),
        bgSubtleSecondary = Color(0xFF151B4A),
        bgSubtleSecondaryLevel0 = Color(0xFF121843),
        bgActionPrimaryRest = ChamosVioletaSala,
        bgActionPrimaryPressed = ChamosAzulSala,
        bgActionPrimaryDisabled = Color(0xFF3A3F6B),
        textPrimary = Color.White,
        textSecondary = Color(0xFFB8C0FF),
        textActionAccent = ChamosAzulSala,
        iconAccentPrimary = ChamosAzulSala,
        borderFocused = ChamosAzulSala,
        borderInteractivePrimary = ChamosVioletaSala,
        borderInteractiveSecondary = Color(0xFF4A52A0),
    )
    ElementTheme(
        theme = Theme.Dark,
        applySystemBarsUpdate = false,
        compoundDark = neon,
        content = content,
    )
}
