package io.element.android.features.home.impl

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.features.home.impl.datasource.RoomListDataSource
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.SendTarget
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProviderFactory
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** La sala del Muro empieza con este caracter invisible, para ocultarla de la lista de chats. */
const val MURO_MARCA = "⁤"
private const val MURO_ALIAS_LOCAL = "chamos-muro"
private const val MURO_SERVIDOR = "matrix.org"
private const val MURO_ALIAS = "#$MURO_ALIAS_LOCAL:$MURO_SERVIDOR"

/** Las publicaciones llevan una marca invisible al inicio: P = publicación, V = venta, C = foto de portada. */
private const val MARCA_POST = "⁣"

fun RoomListRoomSummary.isChamosMuro(): Boolean = name?.startsWith(MURO_MARCA) == true

/** Los chats de avisos del servidor ("Matrix.org (Official Account)"): se esconden de la lista y se silencian. */
fun esAvisoDelServidor(nombre: String?): Boolean =
    nombre != null && (nombre.contains("Official Account", ignoreCase = true) || nombre.startsWith("Matrix.org", ignoreCase = true))

fun RoomListRoomSummary.isChamosAvisos(): Boolean = esAvisoDelServidor(name)

/** El plan gratis de matrix.org solo deja subir 10 MB por archivo. */
internal const val LIMITE_SUBIDA_BYTES = 10L * 1024 * 1024 - 300L * 1024

/** Comprime el archivo y solo lo sube si cabe en el límite del servidor. Devuelve el error, o null si salió bien. */
internal suspend fun subirConLimite(
    sender: io.element.android.libraries.mediaupload.api.MediaSender,
    uri: Uri,
    mime: String,
    caption: String?,
): String? {
    val config = io.element.android.libraries.mediaupload.api.MediaOptimizationConfig(
        compressImages = true,
        videoCompressionPreset = io.element.android.libraries.preferences.api.store.VideoCompressionPreset.LOW,
    )
    val info = sender.preProcessMedia(uri, mime, config).getOrElse { return "No se pudo preparar el archivo" }
    if (info.file.length() > LIMITE_SUBIDA_BYTES) {
        val mb = info.file.length() / (1024.0 * 1024.0)
        return "Pesa %.1f MB aun comprimido y matrix.org solo deja subir hasta 10 MB. Usa uno más corto.".format(mb)
    }
    return if (sender.sendPreProcessedMedia(info, caption, null, null).isSuccess) null else "No se pudo subir (el servidor lo rechazó)"
}

data class MuroPost(
    val id: String,
    val timestamp: Long,
    val autorId: String,
    val autor: String,
    val autorAvatar: String?,
    /** 'P' publicación, 'V' venta, 'C' portada. */
    val tipo: Char,
    val texto: String,
    val titulo: String,
    val precio: String,
    val ciudad: String,
    val contacto: String,
    val fuente: MediaSource?,
    val mime: String?,
    val nombreArchivo: String,
    val likes: Int,
    val yoLike: Boolean,
    val comentarios: Int,
    val propio: Boolean,
    val esVideo: Boolean = false,
    val urlExterna: String? = null,
    val repostDe: String = "",
    val noMeGusta: Int = 0,
    val yoNoMeGusta: Boolean = false,
    val reposts: Int = 0,
)

data class MuroComentario(
    val id: String,
    val timestamp: Long,
    val autor: String,
    val texto: String,
    val propio: Boolean,
)

data class ChamosMuroState(
    val mensaje: String = "",
    val cargando: Boolean = false,
    val posts: List<MuroPost> = emptyList(),
    val portadas: Map<String, MuroPost> = emptyMap(),
    val comentarios: Map<String, List<MuroComentario>> = emptyMap(),
    val yoId: String = "",
    val yoNombre: String = "",
    val yoAvatar: String? = null,
    val soyAdmin: Boolean = false,
    val activar: () -> Unit = {},
    val masAntiguos: () -> Unit = {},
    val publicar: suspend (Uri?, String?, String) -> String? = { _, _, _ -> "No disponible" },
    val vender: suspend (Uri?, String?, String, String, String, String, String) -> String? = { _, _, _, _, _, _, _ -> "No disponible" },
    val cambiarPortada: suspend (Uri, String) -> String? = { _, _ -> "No disponible" },
    val cambiarFoto: suspend (Uri, String) -> String? = { _, _ -> "No disponible" },
    val borrar: suspend (MuroPost) -> Boolean = { false },
    val meGusta: suspend (MuroPost) -> Unit = {},
    val noMeGusta: suspend (MuroPost) -> Unit = {},
    val repostear: suspend (MuroPost, String) -> Boolean = { _, _ -> false },
    val comentar: suspend (MuroPost, String) -> Boolean = { _, _ -> false },
    val borrarComentario: suspend (MuroComentario) -> Boolean = { false },
    val reportar: suspend (MuroPost, String) -> Boolean = { _, _ -> false },
    val bloquear: suspend (String) -> Boolean = { false },
    val cargarImagen: suspend (MuroPost) -> String? = { null },
    val cargarAvatar: suspend (String) -> String? = { null },
    val envio: String = "",
)

private fun InReplyTo.idDeRespuesta(): EventId = when (this) {
    is InReplyTo.NotLoaded -> eventId
    is InReplyTo.Pending -> eventId
    is InReplyTo.Ready -> eventId
    is InReplyTo.Error -> eventId
}

private fun limpiarCampo(t: String): String = t.replace("|", "/").replace("\n", " ").trim()

/** Convierte el texto de una publicación en sus partes. Devuelve (tipo, campos). */
private fun leerCuerpo(cuerpo: String): Pair<Char, List<String>> {
    if (!cuerpo.startsWith(MARCA_POST)) return Pair('P', listOf(cuerpo))
    val resto = cuerpo.substring(MARCA_POST.length)
    val tipo = resto.firstOrNull() ?: 'P'
    val campos = resto.drop(2).split("|")
    return Pair(tipo, campos)
}

private suspend fun asegurarSalaMuro(client: MatrixClient, prefs: android.content.SharedPreferences): Pair<RoomId?, String> {
    val guardada = prefs.getString("sala", null)
    if (guardada != null) {
        val id = RoomId(guardada)
        if (client.getJoinedRoom(id) != null) return Pair(id, "")
    }
    val alias = RoomAlias(MURO_ALIAS)
    val resuelto = client.resolveRoomAlias(alias).getOrNull()?.orElse(null)
    val id: RoomId
    if (resuelto != null) {
        id = resuelto.roomId
        runCatching { client.notificationSettingsService.setRoomNotificationMode(id, RoomNotificationMode.MUTE) }
        if (client.getJoinedRoom(id) == null) {
            val r = client.joinRoomByIdOrAlias(RoomIdOrAlias.Alias(alias), listOf(MURO_SERVIDOR))
            if (r.isFailure) return Pair(null, "No pude entrar al Muro: " + (r.exceptionOrNull()?.message ?: "error"))
        }
    } else {
        val dominio = client.sessionId.value.substringAfter(":")
        if (dominio != MURO_SERVIDOR) {
            return Pair(null, "El Muro todavía no está disponible para tu servidor. Intenta más tarde.")
        }
        val creada = client.createRoom(
            CreateRoomParameters(
                name = MURO_MARCA + "Chamos Muro",
                topic = "Muro y tienda de la comunidad Chamos",
                isEncrypted = false,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PUBLIC_CHAT,
                roomAliasName = Optional.of(MURO_ALIAS_LOCAL),
            )
        )
        id = creada.getOrNull() ?: return Pair(null, "No pude crear el Muro: " + (creada.exceptionOrNull()?.message ?: "error"))
    }
    repeat(30) {
        if (client.getJoinedRoom(id) != null) {
            prefs.edit().putString("sala", id.value).apply()
            return Pair(id, "")
        }
        delay(500)
    }
    return Pair(null, "El Muro tarda en cargar. Intenta de nuevo en un momento.")
}

@Composable
fun rememberChamosMuro(
    client: MatrixClient,
    dataSource: RoomListDataSource,
    mediaSenderFactory: MediaSenderRoomFactory,
    mediaConfigFactory: MediaOptimizationConfigProviderFactory,
): ChamosMuroState {
    // OJO: el Presenter corre con Molecule y NO tiene LocalContext; se usa el contexto de la aplicación.
    val contexto: android.content.Context = remember {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
    }
    val prefs = remember { contexto.getSharedPreferences("chamos_muro", android.content.Context.MODE_PRIVATE) }
    val perfil by client.userProfile.collectAsState()
    var activo by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(false) }
    var salaId by remember { mutableStateOf<RoomId?>(null) }
    var soyAdmin by remember { mutableStateOf(false) }
    var todos by remember { mutableStateOf<List<MuroPost>>(emptyList()) }
    var envio by remember { mutableStateOf("") }
    val reintentados = remember { mutableSetOf<String>() }
    val fallidosTx = remember { mutableMapOf<String, io.element.android.libraries.matrix.api.core.TransactionId>() }
    var comentarios by remember { mutableStateOf<Map<String, List<MuroComentario>>>(emptyMap()) }
    var reportados by remember { mutableStateOf(prefs.getStringSet("reportados", emptySet()) ?: emptySet()) }
    val cacheArchivos = remember { mutableMapOf<String, String>() }
    val scopeMuro = rememberCoroutineScope()
    val yo = client.sessionId

    LaunchedEffect(activo) {
        if (!activo || salaId != null) return@LaunchedEffect
        try {
            cargando = true
            mensaje = "Conectando…"
            val (id, error) = asegurarSalaMuro(client, prefs)
            if (id == null) {
                mensaje = error
                cargando = false
                return@LaunchedEffect
            }
            runCatching { client.notificationSettingsService.setRoomNotificationMode(id, RoomNotificationMode.MUTE) }
            soyAdmin = client.getRoomInfo(id).getOrNull()?.creators?.contains(yo) == true
            mensaje = ""
            salaId = id
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("Chamos", "muro", e)
            mensaje = "No se pudo abrir el Muro"
            cargando = false
        }
    }

    LaunchedEffect(salaId) {
        val id = salaId ?: return@LaunchedEffect
        try {
            val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
            launch {
                delay(500)
                repeat(12) {
                    val r = sala.liveTimeline.paginate(Timeline.PaginationDirection.BACKWARDS)
                    if (r.getOrNull() == true) return@launch
                    delay(if (r.isFailure) 800 else 300)
                }
            }
            sala.liveTimeline.timelineItems.collect { items ->
                val lista = mutableListOf<MuroPost>()
                var pendientes = 0
                fallidosTx.clear()
                var fallos = 0
                var detalle = ""
                val coment = mutableMapOf<String, MutableList<MuroComentario>>()
                for (item in items) {
                    val ev = (item as? MatrixTimelineItem.Event)?.event ?: continue
                    val c = ev.content as? MessageContent ?: continue
                    if (ev.eventId == null) {
                        // Publicación propia que todavía no sale (o que falló): se muestra el estado y se reintenta una vez.
                        if (ev.isOwn) {
                            when (val st = ev.localSendState) {
                                is LocalEventSendState.Failed -> {
                                    fallos++
                                    detalle = st.toString().take(120)
                                    val tx = ev.transactionId
                                    if (tx != null) fallidosTx[tx.value] = tx
                                    if (tx != null && reintentados.add(tx.value)) {
                                        android.util.Log.w("Chamos", "muro: envío fallido, se descarta: $st")
                                        Toast.makeText(contexto, "No se pudo subir. Matrix.org solo deja subir archivos de hasta 10 MB; prueba con un video más corto.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                is LocalEventSendState.Sending -> pendientes++
                                else -> {}
                            }
                        }
                        continue
                    }
                    val eid = ev.eventId?.value ?: continue
                    val pf = ev.senderProfile as? ProfileDetails.Ready
                    val nombre = pf?.displayName ?: ev.sender.value.substringBefore(":").removePrefix("@")
                    val corazon = ev.reactions.firstOrNull { it.key.startsWith("❤") }
                    val likes = corazon?.senders?.size ?: 0
                    val yoLike = corazon?.senders?.any { it.senderId == yo } == true
                    val pulgar = ev.reactions.firstOrNull { it.key.startsWith("👎") }
                    val nogustas = pulgar?.senders?.size ?: 0
                    val yoNoGusta = pulgar?.senders?.any { it.senderId == yo } == true
                    val resp = c.inReplyTo?.idDeRespuesta()
                    when (val t = c.type) {
                        is ImageMessageType -> {
                            val (tipo, campos) = leerCuerpo(t.caption ?: "")
                            lista.add(
                                MuroPost(
                                    id = eid, timestamp = ev.timestamp, autorId = ev.sender.value, autor = nombre, autorAvatar = pf?.avatarUrl,
                                    tipo = tipo,
                                    texto = if (tipo == 'V') campos.getOrElse(4) { "" } else campos.firstOrNull() ?: "",
                                    titulo = if (tipo == 'V') campos.getOrElse(0) { "" } else "",
                                    precio = if (tipo == 'V') campos.getOrElse(1) { "" } else "",
                                    ciudad = if (tipo == 'V') campos.getOrElse(2) { "" } else "",
                                    contacto = if (tipo == 'V') campos.getOrElse(3) { "" } else "",
                                    fuente = t.source, mime = t.info?.mimetype, nombreArchivo = t.filename,
                                    likes = likes, yoLike = yoLike, comentarios = 0, propio = ev.isOwn,
                                    noMeGusta = nogustas, yoNoMeGusta = yoNoGusta, repostDe = if (tipo == 'R') campos.getOrElse(1) { "" } else "",
                                )
                            )
                        }
                        is VideoMessageType -> {
                            val (tipo, campos) = leerCuerpo(t.caption ?: "")
                            lista.add(
                                MuroPost(
                                    id = eid, timestamp = ev.timestamp, autorId = ev.sender.value, autor = nombre, autorAvatar = pf?.avatarUrl,
                                    tipo = tipo,
                                    texto = if (tipo == 'V') campos.getOrElse(4) { "" } else campos.firstOrNull() ?: "",
                                    titulo = if (tipo == 'V') campos.getOrElse(0) { "" } else "",
                                    precio = if (tipo == 'V') campos.getOrElse(1) { "" } else "",
                                    ciudad = if (tipo == 'V') campos.getOrElse(2) { "" } else "",
                                    contacto = if (tipo == 'V') campos.getOrElse(3) { "" } else "",
                                    esVideo = true, fuente = t.source, mime = t.info?.mimetype, nombreArchivo = t.filename,
                                    likes = likes, yoLike = yoLike, comentarios = 0, propio = ev.isOwn,
                                    noMeGusta = nogustas, yoNoMeGusta = yoNoGusta, repostDe = if (tipo == 'R') campos.getOrElse(1) { "" } else "",
                                )
                            )
                        }
                        is TextMessageType -> {
                            if (resp != null) {
                                coment.getOrPut(resp.value) { mutableListOf() }
                                    .add(MuroComentario(eid, ev.timestamp, nombre, t.body, ev.isOwn))
                            } else {
                                val urlVideo = Regex(MARCA_VIDEO_EXT + "(https://\\S+)").find(t.body)?.groupValues?.get(1)
                                val (tipo, campos) = leerCuerpo(t.body.replace(Regex(MARCA_VIDEO_EXT + "https://\\S+"), ""))
                                lista.add(
                                    MuroPost(
                                        id = eid, timestamp = ev.timestamp, autorId = ev.sender.value, autor = nombre, autorAvatar = pf?.avatarUrl,
                                        tipo = tipo,
                                        texto = if (tipo == 'V') campos.getOrElse(4) { "" } else campos.firstOrNull() ?: "",
                                        titulo = if (tipo == 'V') campos.getOrElse(0) { "" } else "",
                                        precio = if (tipo == 'V') campos.getOrElse(1) { "" } else "",
                                        ciudad = if (tipo == 'V') campos.getOrElse(2) { "" } else "",
                                        contacto = if (tipo == 'V') campos.getOrElse(3) { "" } else "",
                                        esVideo = urlVideo != null, urlExterna = urlVideo,
                                        fuente = urlVideo?.let { MediaSource(it) }, mime = if (urlVideo != null) "video/mp4" else null, nombreArchivo = if (urlVideo != null) "video.mp4" else "",
                                        likes = likes, yoLike = yoLike, comentarios = 0, propio = ev.isOwn,
                                    noMeGusta = nogustas, yoNoMeGusta = yoNoGusta, repostDe = if (tipo == 'R') campos.getOrElse(1) { "" } else "",
                                    )
                                )
                            }
                        }
                        else -> {}
                    }
                }
                lista.removeAll { it.esVideo } // el Muro ya no admite videos: solo fotos, letras y grafitis
                val ordenados = coment.mapValues { e -> e.value.sortedBy { it.timestamp } }
                comentarios = ordenados
                todos = lista.map { it.copy(comentarios = ordenados[it.id]?.size ?: 0, reposts = lista.count { x -> x.repostDe == it.id }) }.sortedByDescending { it.timestamp }
                envio = if (fallos > 0) "⚠️ No se pudo subir tu publicación ($detalle)" else if (pendientes > 0) "⏳ Subiendo tu publicación…" else ""
                cargando = false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("Chamos", "muro", e)
            mensaje = "No se pudo cargar el Muro"
            cargando = false
        }
    }

    val visibles = todos.filter { it.id !in reportados }
    val portadas = visibles.filter { it.tipo == 'C' && it.fuente != null }.groupBy { it.autorId }.mapValues { e -> e.value.maxByOrNull { it.timestamp }!! }
    val posts = visibles.filter { it.tipo != 'C' }

    val masAntiguos: () -> Unit = {
        val id = salaId
        if (id != null) {
            scopeMuro.launch { runCatching { client.getJoinedRoom(id)?.liveTimeline?.paginate(Timeline.PaginationDirection.BACKWARDS) } }
        }
    }

    // Una subida que falló se descarta (no se reintenta: si pesa más de lo que permite el servidor, cada intento
    // manda un aviso de matrix.org que hace sonar el teléfono).
    LaunchedEffect(salaId) {
        val idSala = salaId ?: return@LaunchedEffect
        while (true) {
            delay(4_000)
            val sala = client.getJoinedRoom(idSala) ?: continue
            for (tx in fallidosTx.values.toList()) {
                runCatching { sala.liveTimeline.cancelSend(tx) }
                fallidosTx.remove(tx.value)
            }
        }
    }

    suspend fun enviar(uri: Uri?, mime: String?, cuerpo: String): String? {
        val id = salaId ?: return "El Muro aún no está listo"
        val sala = client.getJoinedRoom(id) ?: return "El Muro aún no está listo"
        if (uri == null) {
            return if (sala.liveTimeline.sendMessage(cuerpo, null, emptyList()).isSuccess) null else "No se pudo publicar"
        }
        val sender = mediaSenderFactory.create(client, sala)
        if (mime != null && mime.startsWith("video")) {
            // Los videos del Muro se comprimen a 720p y se guardan 7 días en el servidor de Chamos.
            val cfg = io.element.android.libraries.mediaupload.api.MediaOptimizationConfig(
                compressImages = true,
                videoCompressionPreset = io.element.android.libraries.preferences.api.store.VideoCompressionPreset.STANDARD,
            )
            val info = runCatching { sender.preProcessMedia(uri, mime, cfg).getOrNull() }.getOrNull()
            val enlace = if (info != null && info.file.length() <= 95L * 1024 * 1024) subirVideoChamos(info.file, 168) else null
            if (enlace != null) {
                sender.cleanUp()
                return if (sala.liveTimeline.sendMessage(cuerpo + MARCA_VIDEO_EXT + enlace, null, emptyList()).isSuccess) null else "No se pudo publicar"
            }
        }
        val error = subirConLimite(sender, uri, mime ?: "image/jpeg", cuerpo)
        sender.cleanUp()
        return error
    }

    val publicar: suspend (Uri?, String?, String) -> String? = { uri, mime, texto ->
        enviar(uri, mime, MARCA_POST + "P|" + texto.trim())
    }
    val vender: suspend (Uri?, String?, String, String, String, String, String) -> String? = { uri, mime, titulo, precio, ciudad, contacto, desc ->
        enviar(uri, mime, MARCA_POST + "V|" + limpiarCampo(titulo) + "|" + limpiarCampo(precio) + "|" + limpiarCampo(ciudad) + "|" + limpiarCampo(contacto) + "|" + limpiarCampo(desc))
    }
    val cambiarPortada: suspend (Uri, String) -> String? = { uri, mime -> enviar(uri, mime, MARCA_POST + "C|") }
    val cambiarFoto: suspend (Uri, String) -> String? = foto@{ uri, mime ->
        val bytes = runCatching { withContext(Dispatchers.IO) { contexto.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }.getOrNull()
        if (bytes == null) return@foto "No pude leer la foto"
        if (bytes.size > 5 * 1024 * 1024) return@foto "La foto pesa más de 5 MB"
        if (client.uploadAvatar(mime, bytes).isSuccess) null else "No se pudo cambiar la foto"
    }

    val evento: (String) -> EventOrTransactionId = { EventOrTransactionId.Event(EventId(it)) }
    val bloquear: suspend (String) -> Boolean = { id -> runCatching { client.ignoreUser(io.element.android.libraries.matrix.api.core.UserId(id)).isSuccess }.getOrDefault(false) }
    val borrar: suspend (MuroPost) -> Boolean = borrar@{ p ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@borrar false
        runCatching { sala.liveTimeline.redactEvent(evento(p.id), "Publicación borrada").isSuccess }.getOrDefault(false)
    }
    val meGusta: suspend (MuroPost) -> Unit = gusta@{ p ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@gusta
        if (p.yoNoMeGusta) runCatching { sala.liveTimeline.toggleReaction("👎", evento(p.id)) }
        runCatching { sala.liveTimeline.toggleReaction("❤️", evento(p.id)) }
    }
    val noMeGusta: suspend (MuroPost) -> Unit = nogusta@{ p ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@nogusta
        if (p.yoLike) runCatching { sala.liveTimeline.toggleReaction("❤️", evento(p.id)) }
        runCatching { sala.liveTimeline.toggleReaction("👎", evento(p.id)) }
    }
    val repostear: suspend (MuroPost, String) -> Boolean = { p, comentario ->
        val original = if (p.tipo == 'R') p.repostDe else p.id
        enviar(null, null, MARCA_POST + "R|" + limpiarCampo(comentario) + "|" + original) == null
    }
    val comentar: suspend (MuroPost, String) -> Boolean = comenta@{ p, texto ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@comenta false
        runCatching { sala.liveTimeline.replyMessage(EventId(p.id), texto, null, emptyList()).isSuccess }.getOrDefault(false)
    }
    val borrarComentario: suspend (MuroComentario) -> Boolean = bc@{ c ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@bc false
        runCatching { sala.liveTimeline.redactEvent(evento(c.id), "Comentario borrado").isSuccess }.getOrDefault(false)
    }
    val reportar: suspend (MuroPost, String) -> Boolean = reporta@{ p, motivo ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@reporta false
        val ok = runCatching { sala.reportContent(EventId(p.id), motivo, null).isSuccess }.getOrDefault(false)
        if (ok) {
            val nuevo = reportados + p.id
            reportados = nuevo
            prefs.edit().putStringSet("reportados", nuevo).apply()
        }
        ok
    }
    val cargarAvatar: suspend (String) -> String? = avatar@{ url ->
        val archivo = runCatching {
            client.matrixMediaLoader.downloadMediaFile(MediaSource(url), "image/jpeg", "avatar.jpg").getOrNull()?.toFile()
        }.getOrNull() ?: return@avatar null
        val copia = java.io.File(contexto.cacheDir, "avatar_actual_" + System.currentTimeMillis() + ".jpg")
        runCatching { withContext(Dispatchers.IO) { archivo.copyTo(copia, overwrite = true) } }.getOrNull()?.absolutePath
    }
    val cargarImagen: suspend (MuroPost) -> String? = cargar@{ p ->
        val fuente = p.fuente ?: return@cargar null
        cacheArchivos[p.id]?.let { if (java.io.File(it).exists()) return@cargar it }
        val carpeta = java.io.File(contexto.filesDir, "muro_cache")
        val local = java.io.File(carpeta, p.id.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(80) + ".img")
        if (local.exists() && local.length() > 0) {
            cacheArchivos[p.id] = local.absolutePath
            return@cargar local.absolutePath
        }
        if (p.urlExterna != null) {
            if (!bajarVideoChamos(p.urlExterna, local)) return@cargar null
            cacheArchivos[p.id] = local.absolutePath
            return@cargar local.absolutePath
        }
        var archivo: java.io.File? = null
        for (intento in 0 until 3) {
            archivo = runCatching { client.matrixMediaLoader.downloadMediaFile(fuente, p.mime, p.nombreArchivo).getOrNull()?.toFile() }.getOrNull()
            if (archivo != null) break
            delay(1000L * (intento + 1))
        }
        val descargado: java.io.File = archivo ?: return@cargar null
        val ruta = try {
            withContext(Dispatchers.IO) {
                carpeta.mkdirs()
                carpeta.listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 10L * 24 * 3600_000L) f.delete()
                }
                descargado.copyTo(local, overwrite = true)
                local.absolutePath
            }
        } catch (_: Throwable) {
            descargado.absolutePath
        }
        cacheArchivos[p.id] = ruta
        ruta
    }

    return ChamosMuroState(
        mensaje = mensaje,
        cargando = cargando,
        posts = posts,
        portadas = portadas,
        comentarios = comentarios,
        yoId = yo.value,
        yoNombre = perfil.displayName ?: yo.value.substringBefore(":").removePrefix("@"),
        yoAvatar = perfil.avatarUrl,
        soyAdmin = soyAdmin,
        activar = { activo = true },
        masAntiguos = masAntiguos,
        publicar = publicar,
        vender = vender,
        cambiarPortada = cambiarPortada,
        cambiarFoto = cambiarFoto,
        borrar = borrar,
        meGusta = meGusta,
        noMeGusta = noMeGusta,
        repostear = repostear,
        comentar = comentar,
        borrarComentario = borrarComentario,
        reportar = reportar,
        bloquear = bloquear,
        cargarImagen = cargarImagen,
        cargarAvatar = cargarAvatar,
        envio = envio,
    )
}

/* ---------------------------------------------------------------------------------------------------- */
/* Pantalla                                                                                              */
/* ---------------------------------------------------------------------------------------------------- */

private val FONDO = Color(0xFF10162B)
private val TARJETA = Color(0xFF1B2543)
private val DORADO = Color(0xFFF4B54A)

@Composable
private fun ImagenMuro(post: MuroPost, state: ChamosMuroState, modifier: Modifier, escala: ContentScale) {
    var bmp by remember(post.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(post.id) {
        val ruta = state.cargarImagen(post)
        if (ruta != null) {
            bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(ruta, o)
                    var muestra = 1
                    while (o.outWidth / (muestra * 2) >= 1080) muestra *= 2
                    val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra }
                    android.graphics.BitmapFactory.decodeFile(ruta, o2)
                }.getOrNull()
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = escala, modifier = modifier)
    } else {
        Box(modifier.background(Color(0x33888888)), contentAlignment = Alignment.Center) { Text("Cargando…", color = Color(0xFFCFD6E6), fontSize = 12.sp) }
    }
}

@Composable
fun ChamosMuroPantalla(state: ChamosMuroState) {
    // Letrero de neón azul y violeta al abrir el Muro ("RED DE CHAMOS") y la Tienda ("MERCADO DE LOS CHAMOS").
    var pestanaActual by remember { mutableStateOf(0) }
    var neon by remember { mutableStateOf<Int?>(0) }
    var yaVioRed by remember { mutableStateOf(false) }
    LaunchedEffect(pestanaActual) {
        val cual = if (pestanaActual == 1) 1 else if (!yaVioRed && pestanaActual == 0) 0 else null
        yaVioRed = true
        neon = cual
        if (cual != null) {
            delay(2300)
            neon = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        ChamosMuroContenido(state, onPestana = { pestanaActual = it })
        val n = neon
        if (n != null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (n == 1) ChamosReelNeon("MERCADO DE", "LOS CHAMOS") else ChamosReelNeon("RED DE", "CHAMOS")
            }
        }
    }
}

@Composable
private fun ChamosMuroContenido(state: ChamosMuroState, onPestana: (Int) -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var pestana by remember { mutableStateOf(0) }
    var perfilDe by remember { mutableStateOf("") }
    var nuevaVenta by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.activar() }
    LaunchedEffect(pestana) { onPestana(pestana) }

    val verPerfilDe = if (perfilDe.isEmpty()) state.yoId else perfilDe

    val pestanas: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        listOf("Inicio", "Tienda", "Perfil").forEachIndexed { i, nombre ->
            Text(
                nombre,
                color = if (pestana == i) Color(0xFF1A1205) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(if (pestana == i) DORADO else TARJETA, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .clickable { pestana = i; if (i == 2) perfilDe = "" }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
    }
    Column(Modifier.fillMaxSize().background(FONDO)) {
        if (pestana == 2) Box(Modifier.padding(top = 84.dp)) { pestanas() }
        if (state.mensaje.isNotEmpty() && state.posts.isEmpty()) {
            Text(state.mensaje, color = Color.White, modifier = Modifier.padding(24.dp))
        }
        val lista = when (pestana) {
            1 -> state.posts.filter { it.tipo == 'V' }
            2 -> state.posts.filter { it.autorId == verPerfilDe }
            else -> state.posts
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = if (pestana == 2) 12.dp else 0.dp, bottom = 12.dp)) {
            if (pestana == 0) {
                item(key = "cabecera") { CabeceraRobot(pestanas, io.element.android.features.home.impl.R.drawable.chamos_red_portada) }
            }
            if (state.envio.isNotEmpty()) {
                item(key = "envio") {
                    Text(
                        state.envio, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(if (state.envio.startsWith("⚠")) Color(0xFF8A2A2A) else Color(0xFF2A3558), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp),
                    )
                }
            }
            if (pestana == 2) {
                item(key = "perfil") { CabeceraPerfil(state, verPerfilDe, lista.size) }
            }
            if (pestana == 0) {
                item(key = "nuevo") { CompositorMuro(state, onVender = { nuevaVenta = true }) }
            }
            if (pestana == 1) {
                item(key = "cabecera_mercado") { CabeceraRobot(pestanas, io.element.android.features.home.impl.R.drawable.chamos_mercado_portada) }
                item(key = "vender") {
                    Text(
                        "🛒  Vender algo",
                        color = Color(0xFF1A1205), fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(DORADO, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { nuevaVenta = true }.padding(14.dp),
                    )
                }
            }
            if (lista.isEmpty() && state.mensaje.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        if (state.cargando) "Cargando…" else if (pestana == 1) "Todavía no hay nada en venta." else "Todavía no hay publicaciones. ¡Sé el primero!",
                        color = Color(0xFFCFD6E6), modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(lista, key = { it.id }) { post ->
                TarjetaPost(post, state, onPerfil = { perfilDe = it; pestana = 2 })
            }
            item(key = "mas") {
                LaunchedEffect(lista.size) { state.masAntiguos() }
                Box(Modifier.height(80.dp))
            }
        }
    }

    if (nuevaVenta) {
        var titulo by remember { mutableStateOf("") }
        var precio by remember { mutableStateOf("") }
        var ciudad by remember { mutableStateOf("") }
        var contacto by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var uri by remember { mutableStateOf<Uri?>(null) }
        var mime by remember { mutableStateOf<String?>(null) }
        val selector = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) { uri = u; mime = contexto.contentResolver.getType(u) ?: "image/jpeg" }
        }
        AlertDialog(
            onDismissRequest = { nuevaVenta = false },
            title = { Text("Vender algo") },
            text = {
                Column {
                    OutlinedTextField(value = titulo, onValueChange = { titulo = it.take(60) }, placeholder = { Text("¿Qué vendes?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = precio, onValueChange = { precio = it.take(20) }, placeholder = { Text("Precio (ej. 20$)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = ciudad, onValueChange = { ciudad = it.take(30) }, placeholder = { Text("Ciudad o zona") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contacto, onValueChange = { contacto = it.take(60) }, placeholder = { Text("Cómo te contactan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it.take(300) }, placeholder = { Text("Cuéntanos más") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { selector.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (uri == null) "📷 Agregar foto" else "📷 Foto lista (tocar para cambiar)")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = titulo.isNotBlank() && precio.isNotBlank(), onClick = {
                    val u = uri; val m = mime
                    nuevaVenta = false
                    Toast.makeText(contexto, "Publicando…", Toast.LENGTH_SHORT).show()
                    scope.launch { Toast.makeText(contexto, state.vender(u, m, titulo, precio, ciudad, contacto, desc) ?: "Publicado en la tienda", Toast.LENGTH_LONG).show() }
                }) { Text("Publicar") }
            },
            dismissButton = { TextButton(onClick = { nuevaVenta = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CabeceraPerfil(state: ChamosMuroState, autorId: String, nPosts: Int) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    val esMio = autorId == state.yoId
    val portada = state.portadas[autorId]
    val ejemplo = state.posts.firstOrNull { it.autorId == autorId }
    val nombre = if (esMio) state.yoNombre else (ejemplo?.autor ?: autorId.substringBefore(":").removePrefix("@"))
    val avatar = if (esMio) state.yoAvatar else ejemplo?.autorAvatar
    var pendiente by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var menuAvatar by remember { mutableStateOf(false) }
    var menuPortada by remember { mutableStateOf(false) }
    fun acomodarActual(redonda: Boolean) {
        scope.launch {
            Toast.makeText(contexto, "Abriendo tu foto…", Toast.LENGTH_SHORT).show()
            val ruta = if (redonda) (avatar?.let { state.cargarAvatar(it) }) else (portada?.let { state.cargarImagen(it) })
            if (ruta == null) Toast.makeText(contexto, "No pude abrir la foto actual. Elige otra.", Toast.LENGTH_LONG).show()
            else pendiente = Pair(Uri.fromFile(java.io.File(ruta)), redonda)
        }
    }
    val selPortada = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) pendiente = Pair(u, false)
    }
    val selFoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) pendiente = Pair(u, true)
    }
    pendiente?.let { (uriElegida, redonda) ->
        EditorFoto(
            uri = uriElegida,
            redonda = redonda,
            onCancelar = { pendiente = null },
            onListo = { recortada ->
                pendiente = null
                Toast.makeText(contexto, if (redonda) "Cambiando tu foto…" else "Subiendo portada…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val r = if (redonda) state.cambiarFoto(recortada, "image/jpeg") else state.cambiarPortada(recortada, "image/jpeg")
                    Toast.makeText(contexto, r ?: (if (redonda) "Foto cambiada" else "Portada cambiada"), Toast.LENGTH_LONG).show()
                }
            },
        )
    }
    Column(Modifier.fillMaxWidth().background(TARJETA, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF2A3558), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))) {
            if (portada != null) {
                ImagenMuro(portada, state, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
                Text("Sin foto de portada", color = Color(0xFFCFD6E6), modifier = Modifier.align(Alignment.Center))
            }
            if (esMio) {
                Text(
                    "✏️ Editar",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color(0x99000000), CircleShape)
                        .clickable { if (portada != null) menuPortada = true else selPortada.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = menuPortada, onDismissRequest = { menuPortada = false }) {
                    DropdownMenuItem(text = { Text("Mover mi portada actual") }, onClick = { menuPortada = false; acomodarActual(false) })
                    DropdownMenuItem(text = { Text("Elegir otra imagen") }, onClick = { menuPortada = false; selPortada.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                Avatar(avatarData = AvatarData(autorId, nombre, avatar, AvatarSize.UserListItem), avatarType = AvatarType.User, forcedAvatarSize = 72.dp)
                if (esMio) {
                    Text(
                        "✏️",
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xFF2A3558), CircleShape)
                            .clickable { if (avatar != null) menuAvatar = true else selFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .padding(6.dp),
                    )
                    DropdownMenu(expanded = menuAvatar, onDismissRequest = { menuAvatar = false }) {
                        DropdownMenuItem(text = { Text("Mover mi foto actual") }, onClick = { menuAvatar = false; acomodarActual(true) })
                        DropdownMenuItem(text = { Text("Elegir otra foto") }, onClick = { menuAvatar = false; selFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(nombre, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$nPosts publicaciones", color = Color(0xFFCFD6E6), fontSize = 13.sp)
            }
        }
    }
}

private fun cargarBitmap(contexto: android.content.Context, uri: Uri): android.graphics.Bitmap? = runCatching {
    val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contexto.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opt) }
    var muestra = 1
    while (maxOf(opt.outWidth, opt.outHeight) / muestra > 2048) muestra *= 2
    val opt2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra }
    val bmp = contexto.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opt2) }
        ?: return@runCatching null
    val giro = runCatching {
        contexto.contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (giro == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(giro) }, true)
}.getOrNull()

private fun recortarFoto(
    contexto: android.content.Context,
    bmp: android.graphics.Bitmap,
    fw: Float,
    fh: Float,
    zoom: Float,
    ox: Float,
    oy: Float,
    ow: Int,
    oh: Int,
): Uri? = runCatching {
    val base = maxOf(fw / bmp.width, fh / bmp.height)
    val esc = base * zoom
    val k = ow / fw
    val salida = android.graphics.Bitmap.createBitmap(ow, oh, android.graphics.Bitmap.Config.ARGB_8888)
    val m = android.graphics.Matrix()
    m.postScale(esc * k, esc * k)
    m.postTranslate(ow / 2f + ox * k - bmp.width * esc * k / 2f, oh / 2f + oy * k - bmp.height * esc * k / 2f)
    android.graphics.Canvas(salida).drawBitmap(bmp, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    val dir = java.io.File(contexto.cacheDir, "muro_cache").apply { mkdirs() }
    val f = java.io.File(dir, "recorte_" + System.currentTimeMillis() + ".jpg")
    f.outputStream().use { salida.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
    Uri.fromFile(f)
}.getOrNull()

/** Para acomodar la foto antes de subirla: se arrastra con un dedo y se acerca pellizcando. */
@Composable
private fun EditorFoto(uri: Uri, redonda: Boolean, onListo: (Uri) -> Unit, onCancelar: () -> Unit) {
    val contexto = LocalContext.current
    val densidad = androidx.compose.ui.platform.LocalDensity.current
    val bmp = remember(uri) { cargarBitmap(contexto, uri) }
    val anchoMarco = 270.dp
    val altoMarco = if (redonda) 270.dp else 130.dp
    val fw = with(densidad) { anchoMarco.toPx() }
    val fh = with(densidad) { altoMarco.toPx() }
    var zoom by remember { mutableStateOf(1f) }
    var ox by remember { mutableStateOf(0f) }
    var oy by remember { mutableStateOf(0f) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(if (redonda) "Acomoda tu foto" else "Acomoda tu portada") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Arrástrala para moverla y pellizca para acercar o alejar", fontSize = 13.sp)
                Box(
                    Modifier.padding(top = 14.dp)
                        .size(anchoMarco, altoMarco)
                        .clip(if (redonda) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .pointerInput(bmp) {
                            detectTransformGestures { _, pan, z, _ ->
                                val b = bmp
                                if (b != null) {
                                    val nz = (zoom * z).coerceIn(1f, 5f)
                                    val base = maxOf(fw / b.width, fh / b.height)
                                    val mx = maxOf(0f, (b.width * base * nz - fw) / 2f)
                                    val my = maxOf(0f, (b.height * base * nz - fh) / 2f)
                                    zoom = nz
                                    ox = (ox + pan.x).coerceIn(-mx, mx)
                                    oy = (oy + pan.y).coerceIn(-my, my)
                                }
                            }
                        },
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = ox, translationY = oy),
                        )
                    } else {
                        Text("No pude abrir la foto", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = bmp != null, onClick = {
                val b = bmp
                val u = if (b == null) null else recortarFoto(contexto, b, fw, fh, zoom, ox, oy, if (redonda) 800 else 1280, if (redonda) 800 else (1280 * 130 / 270))
                if (u != null) onListo(u) else Toast.makeText(contexto, "No pude preparar la foto", Toast.LENGTH_LONG).show()
            }) { Text("Listo") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

/** Portada grande (Red de Chamos y Mercado): la imagen ocupa todo el espacio, con las pestañas encima. */
@Composable
private fun CabeceraRobot(pestanas: @Composable () -> Unit, imagen: Int) {
    Box(
        Modifier
            .layout { medida, c ->
                val extra = 24.dp.roundToPx()
                val p = medida.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
            }
            .height(232.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)),
    ) {
        Image(
            painter = painterResource(imagen),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        )
        // Sombra arriba para que se lean las letras de la barra (Reels / Muro) y las pestañas.
        Box(
            Modifier.fillMaxWidth().height(120.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)),
            ),
        )
        // Sombra abajo para que se lean las pestañas al pie del banner.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))),
            ),
        )
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { pestanas() }
    }
}

@Composable
private fun CuerpoPost(post: MuroPost, state: ChamosMuroState) {
    Column {
            if (post.tipo == 'V') {
                Text(post.titulo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                    Text(post.precio, color = DORADO, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (post.ciudad.isNotBlank()) Text("📍 " + post.ciudad, color = Color(0xFFCFD6E6), fontSize = 14.sp)
                }
            }
            if (post.texto.isNotBlank()) Text(post.texto, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            if (post.fuente != null) {
                if (post.esVideo) VideoMuro(post, state)
                else ImagenMuro(post, state, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp).padding(top = 8.dp), ContentScale.FillWidth)
            }
            if (post.tipo == 'V' && post.contacto.isNotBlank()) {
                Text("📞 " + post.contacto, color = DORADO, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
    }
}

@Composable
private fun VideoMuro(post: MuroPost, state: ChamosMuroState) {
    var ruta by remember(post.id) { mutableStateOf<String?>(null) }
    var cargando by remember(post.id) { mutableStateOf(false) }
    var jugar by remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(jugar) {
        if (jugar && ruta == null) {
            cargando = true
            ruta = state.cargarImagen(post)
            cargando = false
            if (ruta == null) jugar = false
        }
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(320.dp).background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val r = ruta
        if (jugar && r != null) {
            VideoTextura(ruta = r, activo = true, repetir = true, onProgreso = {}, onFin = {})
        } else {
            Text(
                if (cargando) "Cargando video…" else "▶  Toca para ver el video",
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.clickable { jugar = true }.padding(24.dp),
            )
        }
    }
}

/** El rectángulo para publicar: escribir, agregar imagen o video, o poner algo en venta. */
@Composable
private fun CompositorMuro(state: ChamosMuroState, onVender: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var texto by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var mime by remember { mutableStateOf<String?>(null) }
    var publicando by remember { mutableStateOf(false) }
    fun elegir(u: Uri?, porDefecto: String) {
        if (u == null) return
        val peso = runCatching { contexto.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } }.getOrNull() ?: 0L
        if (peso > 50L * 1024 * 1024) {
            Toast.makeText(contexto, "El archivo pesa más de 50 MB", Toast.LENGTH_LONG).show()
            return
        }
        uri = u
        mime = contexto.contentResolver.getType(u) ?: porDefecto
    }
    val selImagen = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { elegir(it, "image/jpeg") }
    var grafiti by remember { mutableStateOf(false) }
    var esGrafiti by remember { mutableStateOf(false) }
    if (grafiti) {
        ChamosGrafitiEditor(
            onListo = { u -> uri = u; mime = "image/jpeg"; esGrafiti = true; grafiti = false },
            onCancelar = { grafiti = false },
        )
    }
    Column(Modifier.fillMaxWidth().background(TARJETA, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Avatar(avatarData = AvatarData(state.yoId, state.yoNombre, state.yoAvatar, AvatarSize.UserListItem), avatarType = AvatarType.User, forcedAvatarSize = 40.dp)
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it.take(500) },
                placeholder = { Text("¿Qué cuentas, chamo?", color = Color(0xFFCFD6E6)) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        if (uri != null) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (esGrafiti) "🎨 Grafiti listo" else if (mime?.startsWith("video") == true) "🎬 Video listo" else "🖼 Imagen lista", color = DORADO, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("✕ Quitar", color = Color(0xFFCFD6E6), fontSize = 13.sp, modifier = Modifier.clickable { uri = null; mime = null; esGrafiti = false }.padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                "🖼 Imagen" to { esGrafiti = false; selImagen.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                "🎨 Grafiti" to { grafiti = true },
            ).forEach { (nombre, accion) ->
                Text(
                    nombre, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.background(Color(0x33FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { accion() }.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Box(Modifier.weight(1f))
            val puede = (texto.isNotBlank() || uri != null) && !publicando
            Text(
                if (publicando) "Publicando…" else "Publicar",
                color = Color(0xFF1A1205), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier
                    .background(if (puede) DORADO else Color(0x66F4B54A), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clickable(enabled = puede) {
                        val u = uri; val m = mime; val t = texto
                        publicando = true
                        scope.launch {
                            val resultado = state.publicar(u, m, t)
                            publicando = false
                            if (resultado == null) { texto = ""; uri = null; mime = null; esGrafiti = false }
                            Toast.makeText(contexto, resultado ?: "Publicado", Toast.LENGTH_LONG).show()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TarjetaPost(post: MuroPost, state: ChamosMuroState, onPerfil: (String) -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var comentarios by remember { mutableStateOf(false) }
    var confirmarBorrar by remember { mutableStateOf(false) }
    var reportando by remember { mutableStateOf(false) }
    var reposteando by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(TARJETA, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(
                avatarData = AvatarData(post.autorId, post.autor, post.autorAvatar, AvatarSize.UserListItem),
                avatarType = AvatarType.User, forcedAvatarSize = 40.dp,
            )
            Column(Modifier.weight(1f).clickable { onPerfil(post.autorId) }) {
                Text(post.autor, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(post.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    color = Color(0xFFCFD6E6), fontSize = 12.sp,
                )
            }
            Box {
                Text("⋮", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 10.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (post.propio || state.soyAdmin) DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; confirmarBorrar = true })
                    if (!post.propio) DropdownMenuItem(text = { Text("Reportar") }, onClick = { menu = false; reportando = true })
                }
            }
        }
        if (post.tipo == 'R') {
            Text("🔁 reposteó", color = Color(0xFFCFD6E6), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (post.texto.isNotBlank()) Text(post.texto, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            val orig = state.posts.firstOrNull { it.id == post.repostDe }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0x22FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(10.dp)) {
                if (orig == null) {
                    Text("La publicación original ya no está disponible.", color = Color(0xFFCFD6E6), fontSize = 13.sp)
                } else {
                    Text(orig.autor, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable { onPerfil(orig.autorId) })
                    CuerpoPost(orig, state)
                }
            }
        } else {
            CuerpoPost(post, state)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Text((if (post.yoLike) "❤️ " else "🤍 ") + post.likes, color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { scope.launch { state.meGusta(post) } })
            Text((if (post.yoNoMeGusta) "👎🏽 " else "👎 ") + post.noMeGusta, color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { scope.launch { state.noMeGusta(post) } })
            Text("💬 " + post.comentarios, color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { comentarios = true })
            Text("🔁 " + post.reposts, color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { reposteando = true })
            if (!post.propio) Text("🚩 Denunciar", color = Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.clickable { reportando = true })
        }
    }
    if (reposteando) {
        var comentarioRepost by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reposteando = false },
            title = { Text("Repostear en tu muro") },
            text = {
                OutlinedTextField(value = comentarioRepost, onValueChange = { comentarioRepost = it.take(300) }, placeholder = { Text("Agrega algo (opcional)") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    reposteando = false
                    scope.launch { Toast.makeText(contexto, if (state.repostear(post, comentarioRepost)) "Reposteado" else "No se pudo repostear", Toast.LENGTH_SHORT).show() }
                }) { Text("Repostear") }
            },
            dismissButton = { TextButton(onClick = { reposteando = false }) { Text("Cancelar") } },
        )
    }
    if (confirmarBorrar) {
        AlertDialog(
            onDismissRequest = { confirmarBorrar = false },
            title = { Text("¿Borrar esta publicación?") },
            text = { Text("Se borra para todos y no se puede recuperar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrar = false
                    scope.launch { Toast.makeText(contexto, if (state.borrar(post)) "Borrada" else "No se pudo borrar", Toast.LENGTH_SHORT).show() }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrar = false }) { Text("Cancelar") } },
        )
    }
    if (reportando) {
        val motivos = listOf("Desnudos o contenido sexual", "Violencia o algo peligroso", "Acoso o maltrato", "Estafa o spam", "Otro motivo")
        AlertDialog(
            onDismissRequest = { reportando = false },
            title = { Text("¿Por qué lo reportas?") },
            text = {
                Column {
                    motivos.forEach { motivo ->
                        Text(motivo, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable {
                            reportando = false
                            scope.launch {
                                val ok = state.reportar(post, motivo)
                                Toast.makeText(contexto, if (ok) "Gracias, lo vamos a revisar" else "No se pudo enviar el reporte", Toast.LENGTH_LONG).show()
                            }
                        }.padding(vertical = 12.dp))
                    }
                    Text("🚫 Bloquear a " + post.autor, fontSize = 16.sp, color = Color(0xFFD32F2F), modifier = Modifier.fillMaxWidth().clickable {
                        reportando = false
                        scope.launch {
                            state.reportar(post, "Usuario bloqueado")
                            val ok = state.bloquear(post.autorId)
                            Toast.makeText(contexto, if (ok) "Usuario bloqueado: ya no verás lo que publique" else "No se pudo bloquear", Toast.LENGTH_LONG).show()
                        }
                    }.padding(vertical = 12.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reportando = false }) { Text("Cancelar") } },
        )
    }
    if (comentarios) ComentariosMuro(post, state) { comentarios = false }
}

@Composable
private fun ComentariosMuro(post: MuroPost, state: ChamosMuroState, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var texto by remember { mutableStateOf("") }
    val lista = state.comentarios[post.id].orEmpty()
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(lista.size.toString() + " comentarios") },
        text = {
            Column {
                if (lista.isEmpty()) {
                    Text("Sé el primero en comentar", fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                        items(lista, key = { it.id }) { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.autor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(c.texto, fontSize = 14.sp)
                                }
                                if (c.propio || state.soyAdmin) {
                                    Text("🗑", fontSize = 18.sp, modifier = Modifier.clickable { scope.launch { state.borrarComentario(c) } }.padding(6.dp))
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it.take(300) },
                    placeholder = { Text("Escribe un comentario") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = texto.trim()
                if (t.isNotEmpty()) {
                    texto = ""
                    scope.launch { if (!state.comentar(post, t)) Toast.makeText(contexto, "No se pudo comentar", Toast.LENGTH_SHORT).show() }
                }
            }) { Text("Enviar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
