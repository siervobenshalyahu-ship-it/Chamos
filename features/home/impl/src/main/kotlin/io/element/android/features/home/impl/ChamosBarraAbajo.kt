package io.element.android.features.home.impl

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra de abajo plegable: desliza el dedo hacia la derecha y se esconde dejando un solo icono;
 * desliza hacia la izquierda (o toca el icono) y se abre de nuevo.
 */
@Composable
internal fun ChamosBarraAbajo(modifier: Modifier = Modifier, contenido: @Composable () -> Unit) {
    var abierta by rememberSaveable { mutableStateOf(true) }
    Box(
        modifier = modifier
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
    ) {
        if (abierta) {
            contenido()
        } else {
            val forma = RoundedCornerShape(50)
            androidx.compose.material3.Text(
                text = "☰",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(forma)
                    .background(Color(0xE61B2250))
                    .border(1.5.dp, Color(0xFF2F8BFF), forma)
                    .clickable { abierta = true }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}
