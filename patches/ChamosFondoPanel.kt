package PKG

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ChamosEstilo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lista de fondos para elegir: los de Chamos, "Mis imágenes" (de la galería) y, si se abre desde un chat,
 * la opción de usar el fondo general. roomId = null cambia el fondo general; con roomId cambia solo ese chat.
 */
@Composable
fun ChamosFondoPanel(roomId: String?) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    val actual = if (roomId == null) {
        ChamosEstilo.fondoPropio?.let { "u:$it" } ?: "p:${ChamosEstilo.fondo}"
    } else {
        ChamosEstilo.fondosPorChat[roomId] ?: "g"
    }

    fun elegir(clave: String) {
        if (roomId == null) {
            ChamosEstilo.elegirFondoGeneral(clave)
        } else {
            ChamosEstilo.elegirFondoChat(roomId, if (clave == "g") null else clave)
        }
    }

    val selector = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val nombre = withContext(Dispatchers.IO) { ChamosEstilo.guardarImagen(contexto, uri) }
                if (nombre != null) {
                    ChamosEstilo.registrarFondo(nombre)
                    elegir("u:$nombre")
                } else {
                    Toast.makeText(contexto, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (roomId != null) {
            OpcionFondo("Igual que el fondo general", actual == "g") { elegir("g") }
        }
        ChamosEstilo.fondos.forEachIndexed { i, nombre ->
            OpcionFondo(nombre, actual == "p:$i") { elegir("p:$i") }
        }
        Text("Mis imágenes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        ChamosEstilo.fondosPropios.toList().forEach { nombre ->
            val miniatura by produceState<ImageBitmap?>(null, nombre) {
                value = withContext(Dispatchers.IO) { ChamosEstilo.cargarBitmap(contexto, nombre, 160)?.asImageBitmap() }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { elegir("u:$nombre") },
            ) {
                RadioButton(selected = actual == "u:$nombre", onClick = { elegir("u:$nombre") })
                miniatura?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                Text("Mi imagen", modifier = Modifier.padding(start = 10.dp))
                TextButton(onClick = { ChamosEstilo.borrarFondo(contexto, nombre) }) { Text("Quitar") }
            }
        }
        TextButton(onClick = { selector.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text("＋ Agregar una imagen de mi galería")
        }
    }
}


@Composable
private fun OpcionFondo(texto: String, marcado: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        RadioButton(selected = marcado, onClick = onClick)
        Text(texto)
    }
}

@Composable
fun ChamosFondoDialogo(roomId: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text(if (roomId == null) "Fondo del chat" else "Fondo de este chat") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChamosFondoPanel(roomId)
            }
        },
    )
}
