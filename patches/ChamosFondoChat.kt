package io.element.android.features.messages.impl.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.element.android.compound.theme.ChamosEstilo
import io.element.android.features.messages.impl.R

/** Fondo ilustrado del chat (elegido en "Estilos"). Va detrás de los mensajes y suavizado para que se lean bien. */
@Composable
fun ChamosFondoChat(modifier: Modifier = Modifier) {
    val recurso = when (ChamosEstilo.fondo) {
        1 -> R.drawable.chamos_fondo_1
        2 -> R.drawable.chamos_fondo_2
        3 -> R.drawable.chamos_fondo_3
        4 -> R.drawable.chamos_fondo_4
        5 -> R.drawable.chamos_fondo_5
        6 -> R.drawable.chamos_fondo_6
        7 -> R.drawable.chamos_fondo_7
        8 -> R.drawable.chamos_fondo_8
        9 -> R.drawable.chamos_fondo_9
        else -> return
    }
    Image(
        painter = painterResource(recurso),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 0.35f,
        modifier = modifier.fillMaxSize(),
    )
}
