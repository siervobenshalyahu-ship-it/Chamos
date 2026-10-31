package io.element.android.features.home.impl

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
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

    // Saber cuáles son mías (soy la creadora) y entrar solo a las invitaciones de otras personas.
    LaunchedEffect(salas.map { it.roomId to it.displayType }) {
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
                client.joinRoom(sala.roomId)
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
                    val sala = client.getJoinedRoom(id) ?: return@launch
                    launch {
                        // Cargar hacia atrás hasta cubrir las últimas 24 horas.
                        repeat(8) {
                            val mas = sala.liveTimeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrNull() ?: false
                            val limiteP = System.currentTimeMillis() - CHAMOS_ESTADO_HORAS * 60 * 60 * 1000L
                            val masViejo = entradasPorSala[id]?.minOfOrNull { it.timestamp }
                            if (!mas || (masViejo != null && masViejo < limiteP)) return@launch
                            delay(300)
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

    val contactosIds = chats.filter { it.isDm && it.displayType == RoomSummaryDisplayType.ROOM }.map { it.roomId }

    val otrosChatsIds = chats.filter { !it.isDm && it.displayType == RoomSummaryDisplayType.ROOM }.map { it.roomId }

    suspend fun contactos(): List<UserId> {
        val directos = contactosIds.mapNotNull { id ->
            runCatching { client.getRoom(id)?.getDirectRoomMember()?.userId }.getOrNull()
        }
        // "Todos": además, cualquier persona con la que comparto algún grupo.
        val deGrupos = if (todos) {
            otrosChatsIds.take(40).flatMap { id ->
                runCatching { client.getRoom(id)?.getMembers(200)?.getOrNull().orEmpty().map { it.userId } }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        return (directos + deGrupos).filter { it != client.sessionId }.distinct()
    }

    // Invitar a los contactos a mi sala de estados.
    val invitados = remember { mutableSetOf<UserId>() }
    LaunchedEffect(propia?.roomId, contactosIds, otrosChatsIds, todos) {
        try {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        // Esperar a que la lista de chats se estabilice (si cambia, este bloque se cancela y reinicia).
        delay(4000)
        val permitidos = contactos()
        // Quitar a quien ya no debe verlos (cuando se vuelve a "solo contactos").
        if (!todos && contactosIds.isNotEmpty()) {
            val actuales = sala.getMembers(1000).getOrNull().orEmpty().map { it.userId }
            for (u in actuales) {
                if (u != client.sessionId && u !in permitidos) {
                    sala.kickUser(u, "Estado solo para contactos")
                    invitados.remove(u)
                }
            }
        }
        for (usuario in permitidos) {
            if (invitados.add(usuario)) sala.inviteUserById(usuario)
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.e("Chamos", "estados", e) }
    }

    // Borrar mis estados con más de 24 horas.
    LaunchedEffect(propia?.roomId) {
        try {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        val borrados = mutableSetOf<String>()
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
                val eventId = evento.eventId ?: continue
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
        cacheArchivos[e.id]?.let { return@cargar it }
        val fuente = e.fuente ?: return@cargar null
        val archivo = client.matrixMediaLoader.downloadMediaFile(fuente, e.mime, e.nombre).getOrNull() ?: return@cargar null
        val ruta = archivo.toFile().absolutePath
        cacheArchivos[e.id] = ruta
        ruta
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

    val propiosConEntradas = state?.propio?.takeIf { it.entradas.isNotEmpty() }
    val reproducibles = listOfNotNull(propiosConEntradas) + state?.otros.orEmpty()

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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EstadoCirculo(
                    texto = "Yo",
                    avatar = state?.let { AvatarData(it.yoId, it.yoNombre, it.yoAvatar, AvatarSize.UserListItem) },
                    titulo = "Mi estado",
                    anillo = propiosConEntradas != null,
                    conMas = true,
                    onMas = { creando = true },
                    onClick = { if (propiosConEntradas != null) visor = 0 else creando = true },
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
                    onClick = { visor = reproducibles.indexOfFirst { it.roomId == estado.roomId }.coerceAtLeast(0) },
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
            onCerrar = { visor = null },
        )
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
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
            .size(width = 68.dp, height = 90.dp),
    ) {
        Box(modifier = Modifier.size(62.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .border(
                        BorderStroke(if (anillo) 3.dp else 0.dp, MaterialTheme.colorScheme.primary),
                        CircleShape,
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (avatar != null) {
                    Avatar(avatarData = avatar, avatarType = AvatarType.User, forcedAvatarSize = 52.dp)
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
    onCerrar: () -> Unit,
    onSiguiente: () -> Unit,
    onAnterior: () -> Unit,
) {
    val entrada = item.entradas.getOrNull(iEntrada)
    if (entrada == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

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
                                onProgreso = { progreso.floatValue = it },
                                onFin = { siguiente() },
                            )
                        }
                    }
                }
                if (entrada.tipo != TipoEstado.VIDEO) {
                    val anim = remember { Animatable(0f) }
                    LaunchedEffect(activo) {
                        if (!activo) return@LaunchedEffect
                        // El tiempo empieza a correr cuando el contenido ya está listo.
                        if (entrada.tipo == TipoEstado.IMAGEN) {
                            while (ruta == null) delay(100)
                        }
                        anim.animateTo(1f, tween(DURACION_FIJA_MS, easing = LinearEasing)) {
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
        if (e == items[i].entradas.lastIndex) onVisto(items[i])
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
                val (iItem, iEntrada) = paginas[pagina]
                PaginaEstado(
                    item = items[iItem],
                    iEntrada = iEntrada,
                    activo = estado.currentPage == pagina,
                    cargar = cargar,
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


/** Video sobre TextureView (un SurfaceView se queda en negro dentro del carrusel 3D). */
@Composable
private fun VideoTextura(
    ruta: String,
    activo: Boolean,
    onProgreso: (Float) -> Unit,
    onFin: () -> Unit,
) {
    val activoActual by androidx.compose.runtime.rememberUpdatedState(activo)
    val finActual by androidx.compose.runtime.rememberUpdatedState(onFin)
    val reproductor = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val listo = remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(ruta) {
        onDispose {
            runCatching { reproductor.value?.release() }
            reproductor.value = null
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        val tv = this@apply
                        runCatching {
                            val mp = android.media.MediaPlayer()
                            mp.setDataSource(ruta)
                            mp.setSurface(android.view.Surface(st))
                            mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                if (vw > 0 && vh > 0 && tv.width > 0 && tv.height > 0) {
                                    val escala = minOf(tv.width.toFloat() / vw, tv.height.toFloat() / vh)
                                    val m = android.graphics.Matrix()
                                    m.setScale(vw * escala / tv.width, vh * escala / tv.height, tv.width / 2f, tv.height / 2f)
                                    tv.setTransform(m)
                                }
                            }
                            mp.setOnPreparedListener { listo.value = true }
                            mp.setOnCompletionListener { finActual() }
                            mp.setOnErrorListener { _, _, _ -> finActual(); true }
                            mp.prepareAsync()
                            reproductor.value = mp
                        }.onFailure { android.util.Log.e("Chamos", "video", it) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
            }
        },
    )
    LaunchedEffect(activo, listo.value) {
        val mp = reproductor.value ?: return@LaunchedEffect
        if (!listo.value) return@LaunchedEffect
        runCatching { if (activoActual) mp.start() else mp.pause() }
        while (true) {
            runCatching {
                if (mp.duration > 0) onProgreso(mp.currentPosition.toFloat() / mp.duration)
            }
            delay(200)
        }
    }
}
