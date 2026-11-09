package io.element.android.features.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra ovalada de arriba: los iconos van encima. Se despliega hacia la izquierda mostrando todos los iconos
 * y se esconde hacia la derecha dejando uno solo (el de abrir/cerrar).
 */
@Composable
internal fun ChamosBarraOvalada(contenido: @Composable RowScope.() -> Unit) {
    var abierta by rememberSaveable { mutableStateOf(false) }
    val forma = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(forma)
            .background(Color(0xE61B2250))
            .border(1.5.dp, Color(0xFF2F8BFF), forma)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = abierta,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { contenido() }
        }
        androidx.compose.material3.Text(
            text = if (abierta) "▶" else "◀",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.clickable { abierta = !abierta }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
