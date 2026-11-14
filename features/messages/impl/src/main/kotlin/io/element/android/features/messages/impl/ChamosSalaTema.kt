/*
 * Chamos: la sala de chat con el estilo neón (azul y violeta) de Chamos.
 */

package io.element.android.features.messages.impl

import androidx.compose.runtime.Composable
import io.element.android.libraries.designsystem.theme.ChamosNeonVioleta
import io.element.android.libraries.designsystem.theme.ChamosNeonAzul
import io.element.android.libraries.designsystem.theme.ChamosFondo2
import io.element.android.libraries.designsystem.theme.ChamosFondo1
import io.element.android.libraries.designsystem.theme.ChamosFondo0
import androidx.compose.ui.graphics.Color
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.compoundColorsDark

internal val ChamosAzulSala: Color get() = ChamosNeonAzul
internal val ChamosVioletaSala: Color get() = ChamosNeonVioleta

@Composable
internal fun ChamosSalaTema(content: @Composable () -> Unit) {
    val c = compoundColorsDark
    val neon = c.copy(
        bgCanvasDefault = ChamosFondo0,
        bgCanvasDefaultLevel1 = ChamosFondo1,
        bgSubtleSecondary = ChamosFondo2,
        bgSubtleSecondaryLevel0 = ChamosFondo1,
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
