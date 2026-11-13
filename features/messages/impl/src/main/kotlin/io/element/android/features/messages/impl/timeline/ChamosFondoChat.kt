package io.element.android.features.messages.impl.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.element.android.compound.theme.ChamosEstilo
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fondo del chat (general o el de este chat). Va detrás de los mensajes y suavizado para que se lean bien. */
@Composable
fun ChamosFondoChat(modifier: Modifier = Modifier) {
    val clave = ChamosEstilo.claveFondo(ChamosEstilo.chatActual)
    if (clave.startsWith("u:")) {
        val contexto = LocalContext.current
        val nombre = clave.drop(2)
        val imagen by produceState<ImageBitmap?>(null, nombre) {
            value = withContext(Dispatchers.IO) { ChamosEstilo.cargarBitmap(contexto, nombre, 1600)?.asImageBitmap() }
        }
        imagen?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = modifier.fillMaxSize(),
            )
        }
        return
    }
    val recurso = when (clave.removePrefix("p:").toIntOrNull() ?: 0) {
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

/** Botón de la barra superior del chat para cambiar el fondo solo de ese chat. */
@Composable
fun ChamosFondoBoton(roomId: String) {
    SideEffect { ChamosEstilo.chatActual = roomId }
    var abrir by remember { mutableStateOf(false) }
    IconButton(onClick = { abrir = true }) {
        Icon(imageVector = CompoundIcons.Image(), contentDescription = "Fondo de este chat")
    }
    if (abrir) ChamosFondoDialogo(roomId = roomId, onDismiss = { abrir = false })
}
