package io.element.android.features.messages.impl.messagecomposer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.location.LiveLocationShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Ubicación en tiempo real de Chamos: menú propio (sin claves de mapas) para compartir tu posición en el chat
 * y para ver en un mapa de OpenStreetMap a quienes la están compartiendo.
 */
internal object ChamosVivo {
    @Volatile var room: JoinedRoom? = null
    @Volatile var manager: ActiveLiveLocationShareManager? = null
    @Volatile var scope: CoroutineScope? = null

    fun vincular(r: JoinedRoom, m: ActiveLiveLocationShareManager, s: CoroutineScope) {
        room = r
        manager = m
        scope = s
    }
}

@Composable
internal fun ChamosVivoItem() {
    var abierto by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { abierto = true },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.LocationPin())),
        content = { androidx.compose.material3.Text("Ubicación en tiempo real") },
    )
    if (abierto) ChamosVivoDialogo { abierto = false }
}

private fun coords(geo: String): Pair<Double, Double>? {
    val t = geo.removePrefix("geo:").substringBefore(';').split(',')
    val la = t.getOrNull(0)?.toDoubleOrNull() ?: return null
    val lo = t.getOrNull(1)?.toDoubleOrNull() ?: return null
    return la to lo
}

@Composable
private fun ChamosVivoDialogo(cerrar: () -> Unit) {
    val ctx = LocalContext.current
    val room = ChamosVivo.room
    val manager = ChamosVivo.manager
    val scope = ChamosVivo.scope
    if (room == null || manager == null || scope == null) {
        AlertDialog(
            onDismissRequest = cerrar,
            confirmButton = { TextButton(onClick = cerrar) { androidx.compose.material3.Text("Cerrar") } },
            text = { androidx.compose.material3.Text("La ubicación en tiempo real no está disponible en este chat.") },
        )
        return
    }
    val compartiendo by manager.sharingRoomIds.collectAsState()
    val yo = room.roomId in compartiendo
    val shares by remember(room) { room.subscribeToLiveLocationShares() }.collectAsState(initial = emptyList<LiveLocationShare>())
    var pendiente by remember { mutableStateOf(0) }
    var siguiendo by remember { mutableStateOf<String?>(null) }

    fun iniciar(min: Int) {
        scope.launch {
            manager.startShare(room.roomId, min.minutes)
                .onSuccess { Toast.makeText(ctx, "Compartiendo tu ubicación en tiempo real", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(ctx, "No se pudo compartir: " + (it.message ?: "error"), Toast.LENGTH_LONG).show() }
        }
        cerrar()
    }

    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            iniciar(pendiente)
        } else {
            Toast.makeText(ctx, "Necesito el permiso de ubicación", Toast.LENGTH_LONG).show()
        }
    }
    fun pedir(min: Int) {
        pendiente = min
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        permisos.launch(lista.toTypedArray())
    }

    val ahora = System.currentTimeMillis()
    val activos = shares.filter { it.endTimestamp > ahora && it.lastLocation != null }
    val actual = siguiendo?.let { id -> activos.firstOrNull { it.userId.value == id } }

    if (siguiendo != null) {
        val punto = actual?.lastLocation?.geoUri?.let { coords(it) }
        AlertDialog(
            onDismissRequest = { siguiendo = null },
            confirmButton = {
                if (punto != null) {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${punto.first},${punto.second}?q=${punto.first},${punto.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { androidx.compose.material3.Text("Abrir en mapas") }
                }
            },
            dismissButton = { TextButton(onClick = { siguiendo = null }) { androidx.compose.material3.Text("Cerrar") } },
            title = { androidx.compose.material3.Text(siguiendo ?: "") },
            text = {
                if (punto == null) {
                    androidx.compose.material3.Text("Esta persona ya dejó de compartir su ubicación.")
                } else {
                    val (la, lo) = punto
                    val url = "https://www.openstreetmap.org/export/embed.html?bbox=${lo - 0.004},${la - 0.003},${lo + 0.004},${la + 0.003}&layer=mapnik&marker=$la,$lo"
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        factory = { c ->
                            WebView(c).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                            }
                        },
                        update = { w ->
                            if (w.tag != url) {
                                w.tag = url
                                w.loadUrl(url)
                            }
                        },
                    )
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = cerrar,
        confirmButton = { TextButton(onClick = cerrar) { androidx.compose.material3.Text("Cerrar") } },
        title = { androidx.compose.material3.Text("Ubicación en tiempo real") },
        text = {
            Column {
                if (yo) {
                    androidx.compose.material3.Text("Estás compartiendo tu ubicación en este chat.")
                    TextButton(onClick = {
                        scope.launch { manager.stopShare(room.roomId) }
                        Toast.makeText(ctx, "Dejaste de compartir tu ubicación", Toast.LENGTH_SHORT).show()
                        cerrar()
                    }) { androidx.compose.material3.Text("Dejar de compartir") }
                } else {
                    androidx.compose.material3.Text("Comparte dónde estás, y se actualiza solo mientras dure:")
                    for ((min, nombre) in listOf(15 to "15 minutos", 60 to "1 hora", 480 to "8 horas")) {
                        TextButton(onClick = { pedir(min) }) { androidx.compose.material3.Text(nombre) }
                    }
                }
                androidx.compose.material3.Text("Compartiendo ahora en este chat:", modifier = Modifier.padding(top = 12.dp))
                if (activos.isEmpty()) {
                    androidx.compose.material3.Text("Nadie por ahora.")
                } else {
                    for (s in activos) {
                        TextButton(onClick = { siguiendo = s.userId.value }) { androidx.compose.material3.Text("📍 " + s.userId.value) }
                    }
                }
            }
        },
    )
}
