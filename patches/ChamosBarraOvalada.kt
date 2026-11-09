package io.element.android.features.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Barra ovalada de arriba: los iconos van encima. Desliza el dedo hacia la izquierda y se despliega mostrando todos
 * los iconos; desliza hacia la derecha y se esconde dejando un solo icono ([fijo]).
 */
@Composable
internal fun ChamosBarraOvalada(fijo: @Composable () -> Unit, contenido: @Composable RowScope.() -> Unit) {
    var abierta by rememberSaveable { mutableStateOf(false) }
    val forma = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(forma)
            .background(Color(0xE61B2250))
            .border(1.5.dp, Color(0xFF2F8BFF), forma)
            .animateContentSize()
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, arrastre -> total += arrastre },
                    onDragEnd = {
                        if (total < -24f) abierta = true else if (total > 24f) abierta = false
                    },
                    onDragCancel = { total = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = abierta,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { contenido() }
        }
        fijo()
    }
}
