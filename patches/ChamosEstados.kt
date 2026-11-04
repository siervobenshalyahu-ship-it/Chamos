package io.element.android.features.home.impl

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ChamosEstilo
import io.element.android.features.home.impl.datasource.RoomListDataSource
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.SendTarget
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProviderFactory
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Las salas de estados de Chamos empiezan con este caracter invisible. */
const val CHAMOS_ESTADO_MARCA = "⁣"
private const val CHAMOS_ESTADO_PREFIJO = "Estado de "
private const val CHAMOS_ESTADO_HORAS = 24L
private const val DURACION_FIJA_MS = 6000

fun RoomListRoomSummary.isChamosEstado(): Boolean = name?.startsWith(CHAMOS_ESTADO_MARCA) == true

enum class TipoEstado { IMAGEN, VIDEO, TEXTO }

data class EstadoEntrada(
    val id: String,
    val timestamp: Long,
    val tipo: TipoEstado,
    val texto: String,
    val fuente: MediaSource?,
    val mime: String?,
    val nombre: String,
)

data class ChamosEstadoItem(
    val roomId: RoomId,
    val titulo: String,
    val nuevo: Boolean,
    val entradas: List<EstadoEntrada>,
    val avatar: AvatarData? = null,
    val propio: Boolean = false,
)

data class ChamosEstadosState(
    val propio: ChamosEstadoItem?,
    val otros: List<ChamosEstadoItem>,
    val publicarMedia: suspend (Uri, String) -> Boolean,
    val publicarTexto: suspend (String) -> Boolean,
    val cargarArchivo: suspend (EstadoEntrada) -> String?,
    val marcarVisto: (ChamosEstadoItem) -> Unit,
    val alcanceTodos: Boolean = false,
    val cambiarAlcance: (Boolean) -> Unit = {},
    val yoId: String = "",
    val yoNombre: String? = null,
    val yoAvatar: String? = null,
    val borrar: suspend (EstadoEntrada) -> Boolean = { false },
    val editar: suspend (EstadoEntrada, String) -> Boolean = { _, _ -> false },
    val diagnostico: String = "",
)

private fun RoomListRoomSummary.tituloEstado(): String =
    (name ?: "").removePrefix(CHAMOS_ESTADO_MARCA).removePrefix(CHAMOS_ESTADO_PREFIJO).ifBlank { "Contacto" }

private fun extraerEntrada(item: MatrixTimelineItem): EstadoEntrada? {
    val ev = (item as? MatrixTimelineItem.Event)?.event ?: return null
    val contenido = ev.content as? MessageContent ?: return null
    val id = ev.eventId?.value ?: ev.transactionId?.value ?: return null
    return when (val t = contenido.type) {
        is ImageMessageType -> EstadoEntrada(id, ev.timestamp, TipoEstado.IMAGEN, t.caption ?: "", t.source, t.info?.mimetype, t.filename)
        is VideoMessageType -> EstadoEntrada(id, ev.timestamp, TipoEstado.VIDEO, t.caption ?: "", t.source, t.info?.mimetype, t.filename)
        is TextMessageType -> EstadoEntrada(id, ev.timestamp, TipoEstado.TEXTO, t.body, null, null, "")
        else -> null
    }
}

@Composable
fun rememberChamosEstados(
    client: MatrixClient,
    dataSource: RoomListDataSource,
    mediaSenderFactory: MediaSenderRoomFactory,
    mediaConfigFactory: MediaOptimizationConfigProviderFactory,
): ChamosEstadosState {
    // OJO: el Presenter corre con Molecule y NO tiene LocalContext; se usa el contexto de la aplicación.
    val contexto: android.content.Context = remember {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
    }
    val perfil by client.userProfile.collectAsState()
    var todos by remember {
        mutableStateOf(contexto.getSharedPreferences("chamos_estados_cfg", android.content.Context.MODE_PRIVATE).getBoolean("todos", false))
    }
    val cambiarAlcance: (Boolean) -> Unit = { v ->
        todos = v
        contexto.getSharedPreferences("chamos_estados_cfg", android.content.Context.MODE_PRIVATE).edit().putBoolean("todos", v).apply()
    }
    val perfiles: SnapshotStateMap<RoomId, io.element.android.libraries.matrix.api.user.MatrixUser> = remember { mutableStateMapOf() }
    val salas by dataSource.statusRoomsFlow.collectAsState(initial = emptyList())
    val chats by dataSource.roomSummariesFlow.collectAsState(initial = emptyList())
    val esMia: SnapshotStateMap<RoomId, Boolean> = remember { mutableStateMapOf() }
    val intentos = remember { mutableSetOf<RoomId>() }
    val silenciadas = remember { mutableSetOf<RoomId>() }
    val entradasPorSala: SnapshotStateMap<RoomId, List<EstadoEntrada>> = remember { mutableStateMapOf() }
    val vistos: SnapshotStateMap<String, String> = remember {
        val prefs = contexto.getSharedPreferences("chamos_estados_vistos", android.content.Context.MODE_PRIVATE)
        mutableStateMapOf<String, String>().also { m -> prefs.all.forEach { (k, v) -> if (v is String) m[k] = v } }
    }
    val cacheArchivos = remember { mutableMapOf<String, String>() }
    // Cada 20 segundos se reintenta entrar a salas de estado y enviar invitaciones que hayan fallado.
    var latido by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            latido++
        }
    }

    // Las salas del Muro y de los Reels se silencian desde que arranca la app (sin esperar a que se abran).
    LaunchedEffect(latido) {
        for (nombre in listOf("chamos_muro", "chamos_reels")) {
            val guardada = contexto.getSharedPreferences(nombre, android.content.Context.MODE_PRIVATE).getString("sala", null) ?: continue
            runCatching {
                val id = RoomId(guardada)
                if (client.getJoinedRoom(id) != null && silenciadas.add(id)) {
                    client.notificationSettingsService.setRoomNotificationMode(id, RoomNotificationMode.MUTE)
                }
            }
        }
    }

    // Saber cuáles son mías (soy la creadora) y entrar solo a las invitaciones de otras personas.
    LaunchedEffect(salas.map { it.roomId to it.displayType }, latido) {
        try {
        for (sala in salas) {
            if (esMia[sala.roomId] == null) {
                val info = client.getRoomInfo(sala.roomId).getOrNull()
                esMia[sala.roomId] = info?.creators?.contains(client.sessionId) == true
                val creador = info?.creators?.firstOrNull()
                if (creador != null && creador != client.sessionId) {
                    client.getProfile(creador).getOrNull()?.let { perfiles[sala.roomId] = it }
                }
            }
            if (sala.displayType == RoomSummaryDisplayType.INVITE && esMia[sala.roomId] != true && intentos.add(sala.roomId)) {
                val r = client.joinRoom(sala.roomId)
                if (r.isFailure) {
                    android.util.Log.e("Chamos", "no se pudo entrar a un estado", r.exceptionOrNull())
                    intentos.remove(sala.roomId)
                }
            }
            if (sala.displayType == RoomSummaryDisplayType.ROOM &&
                sala.userDefinedNotificationMode != RoomNotificationMode.MUTE &&
                silenciadas.add(sala.roomId)
            ) {
                client.notificationSettingsService.setRoomNotificationMode(sala.roomId, RoomNotificationMode.MUTE)
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.e("Chamos", "estados", e) }
    }

    val propias = salas.filter { esMia[it.roomId] == true && it.displayType == RoomSummaryDisplayType.ROOM }.distinctBy { it.roomId }
    val propia = propias.firstOrNull()
    val otras = salas.filter { esMia[it.roomId] == false && it.displayType == RoomSummaryDisplayType.ROOM }.distinctBy { it.roomId }
    val idsActivos = (propias + otras).map { it.roomId }

    // Leer los estados de cada sala (los de las últimas 24 horas).
    LaunchedEffect(idsActivos) {
        try {
        for (id in idsActivos) {
            launch {
                try {
                    var salaLeida = client.getJoinedRoom(id)
                    var vez = 0
                    while (salaLeida == null && vez < 20) {
                        delay(1000)
                        salaLeida = client.getJoinedRoom(id)
                        vez++
                    }
                    val sala = salaLeida ?: return@launch
                    launch {
                        // Cargar hacia atrás hasta cubrir las últimas 24 horas.
                        // paginate() devuelve true cuando YA llegó al principio de la sala (no "hay más").
                        // Se sigue cargando hasta llegar al principio, porque cada página incluye también eventos
                        // internos de la sala (miembros, permisos, etc.) y los estados quedaban sin cargar.
                        delay(800)
                        var vecesSeguidas = 0
                        repeat(25) {
                            val r = sala.liveTimeline.paginate(Timeline.PaginationDirection.BACKWARDS)
                            val alInicio = r.getOrNull() == true
                            if (alInicio) return@launch
                            if (r.isFailure && !sala.liveTimeline.backwardPaginationStatus.value.hasMoreToLoad) return@launch
                            vecesSeguidas++
                            delay(if (r.isFailure) 800 else 300)
                        }
                    }
                    sala.liveTimeline.timelineItems.collect { items ->
                        val limite = System.currentTimeMillis() - CHAMOS_ESTADO_HORAS * 60 * 60 * 1000L
                        entradasPorSala[id] = items.mapNotNull { extraerEntrada(it) }
                            .filter { it.timestamp >= limite }
                            .sortedBy { it.timestamp }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e("Chamos", "estados", e)
                }
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.e("Chamos", "estados", e) }
    }

    val contactosIds = chats.filter { (it.isDm || it.isDirect) && it.displayType == RoomSummaryDisplayType.ROOM }.map { it.roomId }

    val otrosChatsIds = chats.filter { !(it.isDm || it.isDirect) && it.displayType == RoomSummaryDisplayType.ROOM && !it.isSpace }.map { it.roomId }

    suspend fun contactos(): List<UserId> {
        val directos = contactosIds.mapNotNull { id ->
            runCatching { client.getRoom(id)?.getDirectRoomMember()?.userId }.getOrNull()
        }
        // Salas de dos personas que el teléfono no marcó como "directas" (también son contactos).
        val pares = otrosChatsIds.take(60).mapNotNull { id ->
            runCatching {
                val otros = client.getRoom(id)?.getMembers(4)?.getOrNull().orEmpty().map { it.userId }.filter { it != client.sessionId }.distinct()
                if (otros.size == 1) otros.first() else null
            }.getOrNull()
        }
        // "Todos": además, cualquier persona con la que comparto algún grupo.
        val deGrupos = if (todos) {
            otrosChatsIds.take(40).flatMap { id ->
                runCatching { client.getRoom(id)?.getMembers(200)?.getOrNull().orEmpty().map { it.userId } }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        return (directos + pares + deGrupos).filter { it != client.sessionId }.distinct()
    }

    // Invitar a los contactos a mi sala de estados.
    val invitados = remember { mutableSetOf<UserId>() }
    var nContactos by remember { mutableStateOf(0) }
    LaunchedEffect(propia?.roomId, contactosIds, otrosChatsIds, todos, latido) {
        try {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        // Esperar a que la lista de chats se estabilice (si cambia, este bloque se cancela y reinicia).
        delay(4000)
        val permitidos = contactos()
        nContactos = permitidos.size
        // Quitar a quien ya no debe verlos (cuando se vuelve a "solo contactos").
        if (!todos && permitidos.isNotEmpty()) {
            val actuales = sala.getMembers(1000).getOrNull().orEmpty().map { it.userId }
            for (u in actuales) {
                if (u != client.sessionId && u !in permitidos) {
                    sala.kickUser(u, "Estado solo para contactos")
                    invitados.remove(u)
                }
            }
        }
        for (usuario in permitidos) {
            if (usuario !in invitados) {
                val r = sala.inviteUserById(usuario)
                // Si falla por algo distinto a "ya está en la sala", se vuelve a intentar en el próximo latido.
                if (r.isSuccess || (r.exceptionOrNull()?.message ?: "").contains("already", ignoreCase = true)) invitados.add(usuario)
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.e("Chamos", "estados", e) }
    }

    // Borrar mis estados con más de 24 horas.
    LaunchedEffect(propia?.roomId) {
        try {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        val borrados = mutableSetOf<String>()
        val reintentados = mutableSetOf<String>()
        val reloj = flow {
            while (true) {
                emit(Unit)
                delay(10 * 60 * 1000L)
            }
        }
        combine(sala.liveTimeline.timelineItems, reloj) { items, _ -> items }.collect { items ->
            val limite = System.currentTimeMillis() - CHAMOS_ESTADO_HORAS * 60 * 60 * 1000L
            for (item in items) {
                val evento = (item as? MatrixTimelineItem.Event)?.event ?: continue
                val eventId = evento.eventId
                if (eventId == null) {
                    // Estado propio que no llegó a enviarse: se vuelve a poner en la cola de envío.
                    val tx = evento.transactionId
                    if (tx != null && evento.isOwn && evento.localSendState is io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState.Failed && reintentados.add(tx.value)) {
                        android.util.Log.w("Chamos", "estado sin enviar, reintentando: ${evento.localSendState}")
                        sala.liveTimeline.retrySend(EventOrTransactionId.Transaction(tx), SendTarget.Event)
                    }
                    continue
                }
                if (evento.isOwn && evento.content is MessageContent && evento.timestamp < limite && borrados.add(eventId.value)) {
                    sala.liveTimeline.redactEvent(EventOrTransactionId.Event(eventId), "Estado vencido")
                }
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.e("Chamos", "estados", e) }
    }

    suspend fun salaPropia(): JoinedRoom? {
        propia?.roomId?.let { id -> client.getJoinedRoom(id)?.let { return it } }
        val nombre = client.userProfile.value.displayName ?: client.sessionId.value
        val creada = client.createRoom(
            CreateRoomParameters(
                name = CHAMOS_ESTADO_MARCA + CHAMOS_ESTADO_PREFIJO + nombre,
                topic = "Estados de Chamos (se borran a las 24 horas)",
                isEncrypted = false,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
                invite = contactos(),
            )
        ).getOrNull() ?: return null
        esMia[creada] = true
        var sala: JoinedRoom? = null
        repeat(10) {
            if (sala == null) {
                sala = client.getJoinedRoom(creada)
                if (sala == null) delay(500)
            }
        }
        val s = sala ?: return null
        // Solo yo puedo publicar; los demás solo ven.
        s.powerLevels().getOrNull()?.let { niveles -> s.updatePowerLevels(niveles.copy(eventsDefault = 50)) }
        client.notificationSettingsService.setRoomNotificationMode(creada, RoomNotificationMode.MUTE)
        return s
    }

    val publicarMedia: suspend (Uri, String) -> Boolean = publicar@{ uri, mime ->
        val sala = salaPropia() ?: return@publicar false
        val sender = mediaSenderFactory.create(client, sala)
        val config = mediaConfigFactory.create(client).get()
        val resultado = sender.sendMedia(uri = uri, mimeType = mime, mediaOptimizationConfig = config)
        sender.cleanUp()
        resultado.isSuccess
    }

    val publicarTexto: suspend (String) -> Boolean = publicar@{ texto ->
        val sala = salaPropia() ?: return@publicar false
        sala.liveTimeline.sendMessage(texto, null, emptyList()).isSuccess
    }

    val cargarArchivo: suspend (EstadoEntrada) -> String? = cargar@{ e ->
        cacheArchivos[e.id]?.let { if (java.io.File(it).exists()) return@cargar it }
        // Copia guardada en el teléfono: un estado ya descargado no se vuelve a bajar.
        val carpeta = java.io.File(contexto.filesDir, "estados_cache")
        val local = java.io.File(
            carpeta,
            e.id.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(80) + "_" + e.nombre.replace(Regex("[^A-Za-z0-9._]"), "_").takeLast(40),
        )
        if (local.exists() && local.length() > 0) {
            cacheArchivos[e.id] = local.absolutePath
            return@cargar local.absolutePath
        }
        val fuente = e.fuente ?: return@cargar null
        var archivo: java.io.File? = null
        for (intento in 0 until 4) {
            archivo = client.matrixMediaLoader.downloadMediaFile(fuente, e.mime, e.nombre).getOrNull()?.toFile()
            if (archivo != null) break
            delay(1000L * (intento + 1))
        }
        if (archivo == null) return@cargar null
        val descargado: java.io.File = archivo
        val ruta = try {
            withContext(Dispatchers.IO) {
                carpeta.mkdirs()
                // Los estados duran 24 h: se borran las copias de más de 2 días.
                carpeta.listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 48L * 3600_000L) f.delete()
                }
                descargado.copyTo(local, overwrite = true)
                local.absolutePath
            }
        } catch (_: Throwable) {
            descargado.absolutePath
        }
        cacheArchivos[e.id] = ruta
        ruta
    }

    fun salaDe(e: EstadoEntrada): RoomId? =
        propias.firstOrNull { s -> entradasPorSala[s.roomId]?.any { it.id == e.id } == true }?.roomId

    val borrarEstado: suspend (EstadoEntrada) -> Boolean = borrar@{ e ->
        val id = salaDe(e) ?: return@borrar false
        val sala = client.getJoinedRoom(id) ?: return@borrar false
        try {
            if (e.id.startsWith("$")) {
                sala.liveTimeline.redactEvent(EventOrTransactionId.Event(EventId(e.id)), "Estado borrado").isSuccess
            } else {
                sala.liveTimeline.cancelSend(TransactionId(e.id)).isSuccess
            }
        } catch (t: Throwable) {
            android.util.Log.e("Chamos", "borrar estado", t)
            false
        }
    }

    val editarEstado: suspend (EstadoEntrada, String) -> Boolean = editar@{ e, nuevo ->
        val id = salaDe(e) ?: return@editar false
        val sala = client.getJoinedRoom(id) ?: return@editar false
        // Un estado que aún no se envió no se puede editar.
        if (!e.id.startsWith("$")) return@editar false
        val evento = EventOrTransactionId.Event(EventId(e.id))
        try {
            if (e.tipo == TipoEstado.TEXTO) {
                sala.liveTimeline.editMessage(evento, nuevo, null, emptyList()).isSuccess
            } else {
                sala.liveTimeline.editCaption(evento, nuevo.ifBlank { null }, null).isSuccess
            }
        } catch (t: Throwable) {
            android.util.Log.e("Chamos", "editar estado", t)
            false
        }
    }

    val marcarVisto: (ChamosEstadoItem) -> Unit = { item ->
        item.entradas.lastOrNull()?.let { ultima ->
            vistos[item.roomId.value] = ultima.id
            contexto.getSharedPreferences("chamos_estados_vistos", android.content.Context.MODE_PRIVATE)
                .edit().putString(item.roomId.value, ultima.id).apply()
        }
    }

    fun item(sala: RoomListRoomSummary, titulo: String): ChamosEstadoItem {
        val entradas = entradasPorSala[sala.roomId].orEmpty()
        return ChamosEstadoItem(
            roomId = sala.roomId,
            titulo = titulo,
            nuevo = entradas.isNotEmpty() && vistos[sala.roomId.value] != entradas.last().id,
            entradas = entradas,
            avatar = perfiles[sala.roomId]?.let { AvatarData(it.userId.value, it.displayName, it.avatarUrl, AvatarSize.UserListItem) },
        )
    }

    return ChamosEstadosState(
        propio = propia?.let { p ->
            val todas = propias.flatMap { entradasPorSala[it.roomId].orEmpty() }.distinctBy { it.id }.sortedBy { it.timestamp }
            item(p, "Mi estado").copy(
                entradas = todas,
                nuevo = false,
                propio = true,
            )
        },
        otros = otras.map { sala -> item(sala, perfiles[sala.roomId]?.displayName?.takeIf { it.isNotBlank() } ?: sala.tituloEstado()) }.filter { it.entradas.isNotEmpty() },
        publicarMedia = publicarMedia,
        publicarTexto = publicarTexto,
        cargarArchivo = cargarArchivo,
        marcarVisto = marcarVisto,
        alcanceTodos = todos,
        cambiarAlcance = cambiarAlcance,
        yoId = perfil.userId.value,
        yoNombre = perfil.displayName,
        yoAvatar = perfil.avatarUrl,
        borrar = borrarEstado,
        editar = editarEstado,
        diagnostico = "salas ${salas.size} (invitaciones ${salas.count { it.displayType == RoomSummaryDisplayType.INVITE }}) · mías ${propias.size} · de otros ${otras.size} · contactos ${nContactos} · invitados ${invitados.size}",
    )
}

/** Fila "Estados" arriba de la pantalla principal: mi estado y los de mis contactos, en carrusel. */
@Composable
fun ChamosEstadosRow(
    state: ChamosEstadosState?,
    modifier: Modifier = Modifier,
) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var creando by remember { mutableStateOf(false) }
    var visor by remember { mutableStateOf<Int?>(null) }
    // Contactos cuyo estado se está cargando: el visor NO se abre hasta que el archivo esté completo.
    val cargando = remember { mutableStateListOf<String>() }
    var decisionStats by remember { mutableStateOf(ChamosStats.decision(contexto)) }
    LaunchedEffect(decisionStats, state?.yoId) {
        val id = state?.yoId.orEmpty()
        if (decisionStats == true && id.startsWith("@")) ChamosStats.reportar(contexto, id, state?.yoNombre)
    }
    if (decisionStats == null && state != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Tu privacidad") },
            text = {
                Text(
                    "Chamos guarda tu usuario, tu nombre, la versión de la app, el modelo de tu teléfono y tu dirección IP " +
                        "para saber cuántas personas usan la app y mejorarla. " +
                        "Nunca guarda tus mensajes, contraseñas ni claves.",
                )
            },
            confirmButton = {
                TextButton(onClick = { ChamosStats.decidir(contexto, true); decisionStats = true }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { ChamosStats.decidir(contexto, false); decisionStats = false }) { Text("No, gracias") }
            },
        )
    }

    val propiosConEntradas = state?.propio?.takeIf { it.entradas.isNotEmpty() }
    val reproducibles = listOfNotNull(propiosConEntradas) + state?.otros.orEmpty()

    fun abrirCuandoCargue(item: ChamosEstadoItem) {
        val clave = item.roomId.value
        val st = state ?: return
        if (clave in cargando) return
        cargando.add(clave)
        scope.launch {
            val primera = item.entradas.firstOrNull()
            val listo = primera == null || primera.tipo == TipoEstado.TEXTO || st.cargarArchivo(primera) != null
            cargando.remove(clave)
            if (listo) {
                visor = reproducibles.indexOfFirst { it.roomId == item.roomId }.coerceAtLeast(0)
                // Los demás estados de la persona se van bajando en segundo plano para que pasen sin esperar.
                item.entradas.drop(1).take(3).forEach { e -> if (e.tipo != TipoEstado.TEXTO) launch { st.cargarArchivo(e) } }
            } else {
                Toast.makeText(contexto, "No se pudo cargar el estado. Inténtalo otra vez.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Estados",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                var a: android.content.Context? = contexto
                while (a is android.content.ContextWrapper && a !is android.app.Activity) a = a.baseContext
                val activity = a as? android.app.Activity
                if (activity != null) {
                    runCatching {
                        Class.forName("io.element.android.x.ChamosUpdater")
                            .getMethod("checkNow", android.app.Activity::class.java)
                            .invoke(null, activity)
                    }
                }
            }) { Text("Buscar actualización") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EstadoCirculo(
                    texto = "Yo",
                    avatar = state?.let { AvatarData(it.yoId, it.yoNombre, it.yoAvatar, AvatarSize.UserListItem) },
                    titulo = "Mi estado",
                    anillo = false,
                    conMas = true,
                    onMas = { creando = true },
                    onClick = { if (propiosConEntradas != null) abrirCuandoCargue(propiosConEntradas) else creando = true },
                    neon = colorNeon(state?.yoId ?: "yo"),
                    tieneEstado = propiosConEntradas != null,
                    cargando = propiosConEntradas != null && propiosConEntradas.roomId.value in cargando,
                )
            }
            items(state?.otros.orEmpty(), key = { it.roomId.value }) { estado ->
                EstadoCirculo(
                    texto = estado.titulo.firstOrNull()?.uppercase() ?: "?",
                    titulo = estado.titulo,
                    avatar = estado.avatar,
                    anillo = estado.nuevo,
                    conMas = false,
                    onMas = {},
                    onClick = { abrirCuandoCargue(estado) },
                    neon = colorNeon(estado.roomId.value),
                    tieneEstado = true,
                    cargando = estado.roomId.value in cargando,
                )
            }
        }
    }

    if (creando && state != null) {
        NuevoEstadoDialogo(
            todos = state.alcanceTodos,
            onAlcance = state.cambiarAlcance,
            onCerrar = { creando = false },
            onMedia = { uri, mime ->
                Toast.makeText(contexto, "Publicando estado…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val ok = state.publicarMedia(uri, mime)
                    Toast.makeText(contexto, if (ok) "Estado publicado" else "No se pudo publicar", Toast.LENGTH_SHORT).show()
                }
            },
            onTexto = { texto ->
                scope.launch {
                    val ok = state.publicarTexto(texto)
                    Toast.makeText(contexto, if (ok) "Estado publicado" else "No se pudo publicar", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    val abierto = visor
    if (abierto != null && state != null && reproducibles.isNotEmpty()) {
        VisorEstados(
            items = reproducibles,
            inicio = abierto.coerceIn(0, reproducibles.lastIndex),
            cargar = state.cargarArchivo,
            onVisto = state.marcarVisto,
            onBorrar = { e ->
                visor = null
                scope.launch {
                    val ok = state.borrar(e)
                    Toast.makeText(contexto, if (ok) "Estado borrado" else "No se pudo borrar", Toast.LENGTH_SHORT).show()
                }
            },
            onEditar = { e, texto ->
                scope.launch {
                    val ok = state.editar(e, texto)
                    Toast.makeText(contexto, if (ok) "Estado editado" else "No se pudo editar", Toast.LENGTH_SHORT).show()
                }
            },
            onCerrar = { visor = null },
        )
    }
}

/** Colores de neón de discoteca: cada contacto tiene el suyo, siempre el mismo. */
private val NEON_PALETA = listOf(
    Color(0xFFFF2D95), // rosa
    Color(0xFF00E5FF), // cian
    Color(0xFFB6FF00), // lima
    Color(0xFFFF9100), // naranja
    Color(0xFFB388FF), // violeta
    Color(0xFFFFEA00), // amarillo
    Color(0xFF00FF9C), // verde menta
    Color(0xFFFF4D4D), // rojo
    Color(0xFF3D7BFF), // azul eléctrico
    Color(0xFFFF6EFF), // fucsia
)

private fun colorNeon(clave: String): Color = NEON_PALETA[(clave.hashCode() and 0x7fffffff) % NEON_PALETA.size]

/** Brocha de cometa: una cola de luz que termina en un punto blanco. Empieza en la hora de las 3 y gira con rotate(). */
private fun cometa(color: Color, alfa: Float, largo: Float): Brush = Brush.sweepGradient(
    0f to Color.Transparent,
    (1f - largo) to Color.Transparent,
    0.97f to color.copy(alpha = 0.9f * alfa),
    1f to Color.White.copy(alpha = alfa),
)

/**
 * Aro de luz de neón alrededor de la foto.
 * - Con estado: aro encendido con el color del contacto (más tenue si ya lo viste) y un latido suave.
 * - Cargando: una luz corre alrededor de la foto, en el color del contacto y otra de color contrario, hasta que el estado esté listo.
 */
private fun Modifier.aroNeon(color: Color, visible: Boolean, brillo: Float, cargando: Boolean): Modifier = composed {
    val transicion = rememberInfiniteTransition(label = "neon")
    val giro by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "giro",
    )
    val latido by transicion.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "latido",
    )
    val contrario = NEON_PALETA[(NEON_PALETA.indexOf(color).coerceAtLeast(0) + NEON_PALETA.size / 2) % NEON_PALETA.size]
    drawBehind {
        if (!visible) return@drawBehind
        val grosor = 3.dp.toPx()
        val radio = size.minDimension / 2f - 2.5.dp.toPx()
        val arcoTopLeft = Offset(center.x - radio, center.y - radio)
        val arcoSize = Size(radio * 2f, radio * 2f)
        val base = if (cargando) 0.35f else brillo * latido
        // Resplandor: varias capas más anchas y más transparentes, como un tubo de neón.
        listOf(9.dp.toPx() to 0.10f, 6.dp.toPx() to 0.16f, 3.5.dp.toPx() to 0.28f).forEach { (extra, a) ->
            drawCircle(color.copy(alpha = a * base), radius = radio, style = Stroke(width = grosor + extra))
        }
        drawCircle(color.copy(alpha = 0.95f * base), radius = radio, style = Stroke(width = grosor))
        if (cargando) {
            rotate(giro, center) {
                listOf(8.dp.toPx() to 0.22f, 4.dp.toPx() to 0.45f, 0f to 1f).forEach { (extra, a) ->
                    drawArc(
                        brush = cometa(color, a, 0.5f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcoTopLeft,
                        size = arcoSize,
                        style = Stroke(width = grosor * 1.2f + extra, cap = StrokeCap.Round),
                    )
                }
            }
            rotate(giro + 180f, center) {
                listOf(6.dp.toPx() to 0.2f, 0f to 0.9f).forEach { (extra, a) ->
                    drawArc(
                        brush = cometa(contrario, a, 0.25f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcoTopLeft,
                        size = arcoSize,
                        style = Stroke(width = grosor + extra, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

@Composable
private fun EstadoCirculo(
    texto: String,
    titulo: String,
    anillo: Boolean,
    conMas: Boolean,
    onMas: () -> Unit,
    onClick: () -> Unit,
    avatar: AvatarData? = null,
    neon: Color = Color.White,
    tieneEstado: Boolean = false,
    cargando: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
            .size(width = 84.dp, height = 108.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(78.dp).aroNeon(neon, tieneEstado, if (anillo) 1f else 0.5f, cargando)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (avatar != null) {
                    Avatar(avatarData = avatar, avatarType = AvatarType.User, forcedAvatarSize = 68.dp)
                } else {
                    Text(texto, fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (conMas) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onMas),
                ) {
                    Text("+", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            text = titulo,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun NuevoEstadoDialogo(
    todos: Boolean,
    onAlcance: (Boolean) -> Unit,
    onCerrar: () -> Unit,
    onMedia: (Uri, String) -> Unit,
    onTexto: (String) -> Unit,
) {
    val contexto = LocalContext.current
    var modoTexto by remember { mutableStateOf(false) }
    var texto by remember { mutableStateOf("") }
    val selector = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = contexto.contentResolver.getType(uri) ?: "image/jpeg"
            onMedia(uri, mime)
        }
        onCerrar()
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Nuevo estado") },
        text = {
            if (modoTexto) {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.take(300) },
                    placeholder = { Text("Escribe tu estado") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column {
                    TextButton(onClick = {
                        selector.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Text("Foto o video") }
                    TextButton(onClick = { modoTexto = true }) { Text("Texto") }
                    Text(
                        "¿Quién ve mis estados?",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onAlcance(false) }.padding(horizontal = 4.dp),
                    ) {
                        androidx.compose.material3.RadioButton(selected = !todos, onClick = { onAlcance(false) })
                        Text("Solo mis contactos")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onAlcance(true) }.padding(horizontal = 4.dp),
                    ) {
                        androidx.compose.material3.RadioButton(selected = todos, onClick = { onAlcance(true) })
                        Text("Todos")
                    }
                }
            }
        },
        confirmButton = {
            if (modoTexto) {
                TextButton(onClick = {
                    if (texto.isNotBlank()) onTexto(texto.trim())
                    onCerrar()
                }) { Text("Publicar") }
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

private fun decodificar(ruta: String): androidx.compose.ui.graphics.ImageBitmap? {
    val bordes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(ruta, bordes)
    var muestra = 1
    while (bordes.outWidth / muestra > 2048 || bordes.outHeight / muestra > 2048) muestra *= 2
    val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
    return BitmapFactory.decodeFile(ruta, opciones)?.asImageBitmap()
}

/** Una persona dentro del carrusel: toca a la derecha para avanzar, a la izquierda para volver. */
@Composable
private fun PaginaEstado(
    item: ChamosEstadoItem,
    iEntrada: Int,
    activo: Boolean,
    cargar: suspend (EstadoEntrada) -> String?,
    onBorrar: (EstadoEntrada) -> Unit,
    onEditar: (EstadoEntrada, String) -> Unit,
    onCerrar: () -> Unit,
    onSiguiente: () -> Unit,
    onAnterior: () -> Unit,
) {
    val entrada = item.entradas.getOrNull(iEntrada)
    if (entrada == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    var menu by remember { mutableStateOf(false) }
    var confirmarBorrar by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf(false) }
    var textoNuevo by remember { mutableStateOf("") }
    val pausa = menu || confirmarBorrar || editando

    fun siguiente() = onSiguiente()

    fun anterior() = onAnterior()

    run {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(iEntrada) {
                    detectTapGestures { punto -> if (punto.x < size.width / 3f) anterior() else siguiente() }
                },
        ) {
            androidx.compose.runtime.key(iEntrada) {
                val progreso = remember { mutableFloatStateOf(0f) }
                val ruta by produceState<String?>(null, entrada.id) {
                    value = if (entrada.tipo == TipoEstado.TEXTO) null else cargar(entrada)
                }
                when (entrada.tipo) {
                    TipoEstado.TEXTO -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(ChamosEstilo.paletas[ChamosEstilo.paleta].acento, Color(0xFF101820)),
                                    ),
                                )
                                .padding(32.dp),
                        ) {
                            Text(
                                entrada.texto,
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    TipoEstado.IMAGEN -> {
                        val imagen by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ruta) {
                            value = ruta?.let { r -> withContext(Dispatchers.IO) { decodificar(r) } }
                        }
                        imagen?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Cargando…", color = Color.White)
                        }
                    }
                    TipoEstado.VIDEO -> {
                        val r = ruta
                        if (r == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Cargando…", color = Color.White)
                            }
                        } else {
                            VideoTextura(
                                ruta = r,
                                activo = activo,
                                pausa = pausa,
                                onProgreso = { progreso.floatValue = it },
                                // Solo el estado que se está viendo puede avanzar el carrusel.
                                onFin = { if (activo) siguiente() },
                            )
                        }
                    }
                }
                if (entrada.tipo != TipoEstado.VIDEO) {
                    val anim = remember { Animatable(0f) }
                    LaunchedEffect(activo, pausa) {
                        if (!activo || pausa) return@LaunchedEffect
                        // El tiempo empieza a correr cuando el contenido ya está listo.
                        if (entrada.tipo == TipoEstado.IMAGEN) {
                            while (ruta == null) delay(100)
                        }
                        val resto = ((1f - anim.value) * DURACION_FIJA_MS).toInt().coerceAtLeast(1)
                        anim.animateTo(1f, tween(resto, easing = LinearEasing)) {
                            progreso.floatValue = value
                        }
                        siguiente()
                    }
                }

                // Barras de progreso, nombre y cerrar
                Column(modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        item.entradas.forEachIndexed { i, _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.35f)),
                            ) {
                                val llenado = when {
                                    i < iEntrada -> 1f
                                    i == iEntrada -> progreso.floatValue
                                    else -> 0f
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth(llenado.coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(item.titulo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.propio) {
                                Box {
                                    Text(
                                        "⋮",
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { menu = true }
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Borrar estado") },
                                            onClick = { menu = false; confirmarBorrar = true },
                                        )
                                    }
                                }
                            }
                            Text(
                                "✕",
                                color = Color.White,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .clickable { onCerrar() }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                if (entrada.tipo != TipoEstado.TEXTO && entrada.texto.isNotBlank()) {
                    Text(
                        entrada.texto,
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(16.dp),
                    )
                }
                if (confirmarBorrar) {
                    AlertDialog(
                        onDismissRequest = { confirmarBorrar = false },
                        title = { Text("¿Borrar este estado?") },
                        text = { Text("Dejará de verse para todos tus contactos.") },
                        confirmButton = { TextButton(onClick = { confirmarBorrar = false; onBorrar(entrada); onCerrar() }) { Text("Borrar") } },
                        dismissButton = { TextButton(onClick = { confirmarBorrar = false }) { Text("Cancelar") } },
                    )
                }
                if (editando) {
                    AlertDialog(
                        onDismissRequest = { editando = false },
                        title = { Text(if (entrada.tipo == TipoEstado.TEXTO) "Editar texto" else "Editar descripción") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = textoNuevo,
                                onValueChange = { textoNuevo = it.take(300) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = entrada.tipo != TipoEstado.TEXTO || textoNuevo.isNotBlank(),
                                onClick = { editando = false; onEditar(entrada, textoNuevo.trim()) },
                            ) { Text("Guardar") }
                        },
                        dismissButton = { TextButton(onClick = { editando = false }) { Text("Cancelar") } },
                    )
                }
            }
        }
    }
}

/** Reproductor de estados a pantalla completa: desliza entre personas con efecto de cubo 3D, como Telegram. */
@Composable
private fun VisorEstados(
    items: List<ChamosEstadoItem>,
    inicio: Int,
    cargar: suspend (EstadoEntrada) -> String?,
    onVisto: (ChamosEstadoItem) -> Unit,
    onBorrar: (EstadoEntrada) -> Unit,
    onEditar: (EstadoEntrada, String) -> Unit,
    onCerrar: () -> Unit,
) {
    // Cada estado (de cada persona) es una página del carrusel.
    val paginas = remember(items) {
        items.flatMapIndexed { i, it -> it.entradas.indices.map { e -> i to e } }
    }
    val primera = paginas.indexOfFirst { it.first == inicio.coerceIn(0, items.lastIndex) }.coerceAtLeast(0)
    val estado = androidx.compose.foundation.pager.rememberPagerState(initialPage = primera) { paginas.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(estado.currentPage) {
        val (i, e) = paginas.getOrNull(estado.currentPage) ?: return@LaunchedEffect
        if (i in items.indices && e == items[i].entradas.lastIndex) onVisto(items[i])
    }
    Dialog(
        onDismissRequest = { onCerrar() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = estado,
            modifier = Modifier.fillMaxSize().background(Color.Black),
            beyondViewportPageCount = 1,
        ) { pagina ->
            val posicion = pagina - estado.currentPage - estado.currentPageOffsetFraction
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        cameraDistance = 24f * density
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (posicion < 0f) 1f else 0f, 0.5f)
                        rotationY = 90f * posicion.coerceIn(-1f, 1f)
                    },
            ) {
                val (iItem, iEntrada) = paginas.getOrNull(pagina) ?: return@HorizontalPager
                if (iItem !in items.indices) return@HorizontalPager
                PaginaEstado(
                    item = items[iItem],
                    iEntrada = iEntrada,
                    activo = estado.currentPage == pagina,
                    cargar = cargar,
                    onBorrar = onBorrar,
                    onEditar = onEditar,
                    onCerrar = { onVisto(items[iItem]); onCerrar() },
                    onSiguiente = {
                        if (pagina < paginas.lastIndex) scope.launch { estado.animateScrollToPage(pagina + 1) } else { onVisto(items[iItem]); onCerrar() }
                    },
                    onAnterior = {
                        if (pagina > 0) scope.launch { estado.animateScrollToPage(pagina - 1) }
                    },
                )
            }
        }
    }
}


/**
 * Video sobre TextureView (un SurfaceView se queda en negro dentro del carrusel 3D).
 * Solo se prepara el reproductor del estado que se está viendo; los demás no tocan el decodificador.
 * Un error NUNCA salta al siguiente de golpe: muestra un aviso y avanza a los 3 segundos.
 */
@Composable
internal fun VideoTextura(
    ruta: String,
    activo: Boolean,
    pausa: Boolean = false,
    repetir: Boolean = false,
    onProgreso: (Float) -> Unit,
    onFin: () -> Unit,
) {
    val finActual by androidx.compose.runtime.rememberUpdatedState(onFin)
    val pausaActual by androidx.compose.runtime.rememberUpdatedState(pausa)
    val superficie = remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }
    val vista = remember { mutableStateOf<android.view.TextureView?>(null) }
    var fallo by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        superficie.value = st
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        superficie.value = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                vista.value = this
            }
        },
    )

    LaunchedEffect(activo, superficie.value, ruta) {
        val st = superficie.value ?: return@LaunchedEffect
        if (!activo) return@LaunchedEffect
        fallo = false
        // Algunos teléfonos tardan en liberar el decodificador del video anterior: se reintenta.
        var intento = 0
        while (intento < 4) {
            if (intento > 0) delay(600L * intento)
            intento++
            val mp = android.media.MediaPlayer()
            val pantalla = android.view.Surface(st)
            var ok = false
            try {
                val listo = kotlinx.coroutines.CompletableDeferred<Boolean>()
                mp.setDataSource(ruta)
                mp.isLooping = repetir
                mp.setSurface(pantalla)
                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                    val tv = vista.value
                    if (tv != null && vw > 0 && vh > 0 && tv.width > 0 && tv.height > 0) {
                        val escala = minOf(tv.width.toFloat() / vw, tv.height.toFloat() / vh)
                        val m = android.graphics.Matrix()
                        m.setScale(vw * escala / tv.width, vh * escala / tv.height, tv.width / 2f, tv.height / 2f)
                        tv.setTransform(m)
                    }
                }
                mp.setOnPreparedListener { listo.complete(true) }
                var terminado = false
                mp.setOnCompletionListener { terminado = true; finActual() }
                mp.setOnErrorListener { _, what, extra ->
                    android.util.Log.e("Chamos", "video error $what/$extra")
                    listo.complete(false)
                    true
                }
                mp.prepareAsync()
                val preparado = kotlinx.coroutines.withTimeoutOrNull(8000) { listo.await() } == true
                if (preparado) {
                    mp.start()
                    ok = true
                    while (true) {
                        val dur = mp.duration
                        if (dur > 0) onProgreso(mp.currentPosition.toFloat() / dur)
                        if (!terminado) {
                            if (pausaActual && mp.isPlaying) mp.pause()
                            else if (!pausaActual && !mp.isPlaying) mp.start()
                        }
                        delay(200)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Chamos", "video", e)
            } finally {
                runCatching { mp.release() }
                runCatching { pantalla.release() }
            }
            if (ok) break
        }
        fallo = true
    }

    if (fallo) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No se pudo reproducir este video", color = Color.White)
        }
        LaunchedEffect(Unit) {
            delay(3000)
            finActual()
        }
    }
}
