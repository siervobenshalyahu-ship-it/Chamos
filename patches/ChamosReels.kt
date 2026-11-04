package io.element.android.features.home.impl

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** La sala de los Reels empieza con este caracter invisible, para ocultarla de la lista de chats. */
const val REELS_MARCA = "⁢"
private const val REELS_ALIAS_LOCAL = "chamos-reels"
private const val REELS_SERVIDOR = "matrix.org"
private const val REELS_ALIAS = "#$REELS_ALIAS_LOCAL:$REELS_SERVIDOR"
private const val REELS_LIMITE_MB = 50L

fun RoomListRoomSummary.isChamosReels(): Boolean = name?.startsWith(REELS_MARCA) == true

data class ReelItem(
    val id: String,
    val timestamp: Long,
    val autorId: String,
    val autor: String,
    val autorAvatar: String?,
    val descripcion: String,
    val fuente: MediaSource,
    val mime: String?,
    val nombreArchivo: String,
    val likes: Int,
    val yoLike: Boolean,
    val comentarios: Int,
    val propio: Boolean,
    val reportes: Int = 0,
)

data class ReelComentario(
    val id: String,
    val timestamp: Long,
    val autor: String,
    val texto: String,
    val propio: Boolean,
)

data class ChamosReelsState(
    val mensaje: String = "",
    val cargando: Boolean = false,
    val reels: List<ReelItem> = emptyList(),
    val comentarios: Map<String, List<ReelComentario>> = emptyMap(),
    val muro: ChamosMuroState = ChamosMuroState(),
    val envio: String = "",
    val soyAdmin: Boolean = false,
    val chats: List<Pair<RoomId, String>> = emptyList(),
    val activar: () -> Unit = {},
    val masAntiguos: () -> Unit = {},
    val publicar: suspend (Uri, String, String) -> String? = { _, _, _ -> "No disponible" },
    val borrar: suspend (ReelItem) -> Boolean = { false },
    val editar: suspend (ReelItem, String) -> Boolean = { _, _ -> false },
    val meGusta: suspend (ReelItem) -> Unit = {},
    val comentar: suspend (ReelItem, String) -> Boolean = { _, _ -> false },
    val borrarComentario: suspend (ReelComentario) -> Boolean = { false },
    val reportar: suspend (ReelItem, String) -> Boolean = { _, _ -> false },
    val enviarAChat: suspend (ReelItem, RoomId) -> Boolean = { _, _ -> false },
    val cargarArchivo: suspend (ReelItem) -> String? = { null },
)

private fun InReplyTo.idRespondido(): EventId = when (this) {
    is InReplyTo.NotLoaded -> eventId
    is InReplyTo.Pending -> eventId
    is InReplyTo.Ready -> eventId
    is InReplyTo.Error -> eventId
}

/** Busca la sala común de los Reels; si no existe y la persona está en matrix.org, la crea. */
private suspend fun asegurarSalaReels(client: MatrixClient, prefs: android.content.SharedPreferences): Pair<RoomId?, String> {
    val guardada = prefs.getString("sala", null)
    if (guardada != null) {
        val id = RoomId(guardada)
        if (client.getJoinedRoom(id) != null) return Pair(id, "")
    }
    val alias = RoomAlias(REELS_ALIAS)
    val resuelto = client.resolveRoomAlias(alias).getOrNull()?.orElse(null)
    val id: RoomId
    if (resuelto != null) {
        id = resuelto.roomId
        if (client.getJoinedRoom(id) == null) {
            val r = client.joinRoomByIdOrAlias(RoomIdOrAlias.Alias(alias), listOf(REELS_SERVIDOR))
            if (r.isFailure) return Pair(null, "No pude entrar a los Reels: " + (r.exceptionOrNull()?.message ?: "error"))
        }
    } else {
        val dominio = client.sessionId.value.substringAfter(":")
        if (dominio != REELS_SERVIDOR) {
            return Pair(null, "Los Reels todavía no están disponibles para tu servidor. Intenta más tarde.")
        }
        val creada = client.createRoom(
            CreateRoomParameters(
                name = REELS_MARCA + "Chamos Reels",
                topic = "Videos de la comunidad Chamos",
                isEncrypted = false,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PUBLIC_CHAT,
                roomAliasName = Optional.of(REELS_ALIAS_LOCAL),
            )
        )
        id = creada.getOrNull() ?: return Pair(null, "No pude crear los Reels: " + (creada.exceptionOrNull()?.message ?: "error"))
    }
    repeat(30) {
        if (client.getJoinedRoom(id) != null) {
            prefs.edit().putString("sala", id.value).apply()
            return Pair(id, "")
        }
        delay(500)
    }
    return Pair(null, "Los Reels tardan en cargar. Intenta de nuevo en un momento.")
}

@Composable
fun rememberChamosReels(
    client: MatrixClient,
    dataSource: RoomListDataSource,
    mediaSenderFactory: MediaSenderRoomFactory,
    mediaConfigFactory: MediaOptimizationConfigProviderFactory,
): ChamosReelsState {
    // OJO: el Presenter corre con Molecule y NO tiene LocalContext; se usa el contexto de la aplicación.
    val contexto: android.content.Context = remember {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
    }
    val prefs = remember { contexto.getSharedPreferences("chamos_reels", android.content.Context.MODE_PRIVATE) }
    var activo by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(false) }
    var salaId by remember { mutableStateOf<RoomId?>(null) }
    var soyAdmin by remember { mutableStateOf(false) }
    var reels by remember { mutableStateOf<List<ReelItem>>(emptyList()) }
    var envio by remember { mutableStateOf("") }
    val reintentados = remember { mutableSetOf<String>() }
    var comentarios by remember { mutableStateOf<Map<String, List<ReelComentario>>>(emptyMap()) }
    val cacheArchivos = remember { mutableMapOf<String, String>() }
    val chats by dataSource.roomSummariesFlow.collectAsState(initial = emptyList())
    val yo = client.sessionId
    val scopeReels = rememberCoroutineScope()
    val muro = rememberChamosMuro(client, dataSource, mediaSenderFactory, mediaConfigFactory)

    // 1) Encontrar o crear la sala (solo cuando la persona abre los Reels).
    LaunchedEffect(activo) {
        if (!activo || salaId != null) return@LaunchedEffect
        try {
            cargando = true
            mensaje = "Conectando…"
            val (id, error) = asegurarSalaReels(client, prefs)
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
            android.util.Log.e("Chamos", "reels", e)
            mensaje = "No se pudo abrir los Reels"
            cargando = false
        }
    }

    // 2) Leer los videos, los me gusta y los comentarios de la sala.
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
                val lista = mutableListOf<ReelItem>()
                var pendientes = 0
                var fallos = 0
                var detalle = ""
                val coment = mutableMapOf<String, MutableList<ReelComentario>>()
                val reportesPor = mutableMapOf<String, MutableSet<String>>()
                for (item in items) {
                    val ev = (item as? MatrixTimelineItem.Event)?.event ?: continue
                    val c = ev.content as? MessageContent ?: continue
                    if (ev.eventId == null) {
                        // Video propio que todavía no sale (o que falló): se muestra el estado y se reintenta una vez.
                        if (ev.isOwn) {
                            when (val st = ev.localSendState) {
                                is LocalEventSendState.Failed -> {
                                    fallos++
                                    detalle = st.toString().take(120)
                                    val tx = ev.transactionId
                                    if (tx != null && reintentados.add(tx.value)) {
                                        android.util.Log.w("Chamos", "reels: video sin enviar, reintentando: $st")
                                        runCatching { sala.liveTimeline.retrySend(EventOrTransactionId.Transaction(tx), SendTarget.Event) }
                                    }
                                }
                                is LocalEventSendState.Sending -> pendientes++
                                else -> {}
                            }
                        }
                        continue
                    }
                    val eid = ev.eventId?.value ?: continue
                    val perfil = ev.senderProfile as? ProfileDetails.Ready
                    val nombre = perfil?.displayName ?: ev.sender.value.substringBefore(":").removePrefix("@")
                    when (val t = c.type) {
                        is VideoMessageType -> {
                            val corazon = ev.reactions.firstOrNull { it.key.startsWith("❤") }
                            lista.add(
                                ReelItem(
                                    id = eid,
                                    timestamp = ev.timestamp,
                                    autorId = ev.sender.value,
                                    autor = nombre,
                                    autorAvatar = perfil?.avatarUrl,
                                    descripcion = t.caption ?: "",
                                    fuente = t.source,
                                    mime = t.info?.mimetype,
                                    nombreArchivo = t.filename,
                                    likes = corazon?.senders?.size ?: 0,
                                    yoLike = corazon?.senders?.any { it.senderId == yo } == true,
                                    comentarios = 0,
                                    propio = ev.isOwn,
                                )
                            )
                        }
                        is TextMessageType -> {
                            val resp = c.inReplyTo?.idRespondido()
                            if (resp != null) {
                                if (t.body.startsWith("\uD83D\uDEA9")) {
                                    // Un reporte (🚩) de la comunidad: no es un comentario.
                                    reportesPor.getOrPut(resp.value) { mutableSetOf() }.add(ev.sender.value)
                                } else {
                                    coment.getOrPut(resp.value) { mutableListOf() }
                                        .add(ReelComentario(eid, ev.timestamp, nombre, t.body, ev.isOwn))
                                }
                            }
                        }
                        else -> {}
                    }
                }
                val ordenados = coment.mapValues { e -> e.value.sortedBy { it.timestamp } }
                comentarios = ordenados
                reels = lista.map { it.copy(comentarios = ordenados[it.id]?.size ?: 0, reportes = reportesPor[it.id]?.size ?: 0) }
                    // Con 3 o más reportes distintos el video se esconde para todos (menos su dueño y el administrador).
                    .filter { it.reportes < 3 || it.propio || soyAdmin }
                    .sortedByDescending { it.timestamp }
                envio = if (fallos > 0) "⚠️ No se pudo subir tu video ($detalle)" else if (pendientes > 0) "⏳ Subiendo tu video…" else ""
                cargando = false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("Chamos", "reels", e)
            mensaje = "No se pudieron cargar los videos"
            cargando = false
        }
    }

    val masAntiguos: () -> Unit = {
        val id = salaId
        if (id != null) {
            // Se lanza sin esperar: si ya no hay más, no pasa nada.
            scopeReels.launch {
                runCatching { client.getJoinedRoom(id)?.liveTimeline?.paginate(Timeline.PaginationDirection.BACKWARDS) }
            }
        }
    }

    val publicar: suspend (Uri, String, String) -> String? = publicar@{ uri, mime, desc ->
        val id = salaId ?: return@publicar "Los Reels aún no están listos"
        val sala = client.getJoinedRoom(id) ?: return@publicar "Los Reels aún no están listos"
        val tam = runCatching {
            contexto.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (tam > REELS_LIMITE_MB * 1024 * 1024) return@publicar "El video pesa más de $REELS_LIMITE_MB MB"
        val sender = mediaSenderFactory.create(client, sala)
        val config = mediaConfigFactory.create(client).get()
        val r = sender.sendMedia(uri = uri, mimeType = mime, caption = desc.ifBlank { null }, mediaOptimizationConfig = config)
        sender.cleanUp()
        if (r.isSuccess) null else "No se pudo subir el video"
    }

    val evento: (String) -> EventOrTransactionId = { EventOrTransactionId.Event(EventId(it)) }

    val borrar: suspend (ReelItem) -> Boolean = borrar@{ r ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@borrar false
        runCatching { sala.liveTimeline.redactEvent(evento(r.id), "Reel borrado").isSuccess }.getOrDefault(false)
    }
    val editar: suspend (ReelItem, String) -> Boolean = editar@{ r, nuevo ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@editar false
        runCatching { sala.liveTimeline.editCaption(evento(r.id), nuevo.ifBlank { null }, null).isSuccess }.getOrDefault(false)
    }
    val meGusta: suspend (ReelItem) -> Unit = gusta@{ r ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@gusta
        runCatching { sala.liveTimeline.toggleReaction("❤️", evento(r.id)) }
    }
    val comentar: suspend (ReelItem, String) -> Boolean = comenta@{ r, texto ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@comenta false
        runCatching { sala.liveTimeline.replyMessage(EventId(r.id), texto, null, emptyList()).isSuccess }.getOrDefault(false)
    }
    val borrarComentario: suspend (ReelComentario) -> Boolean = bc@{ c ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@bc false
        runCatching { sala.liveTimeline.redactEvent(evento(c.id), "Comentario borrado").isSuccess }.getOrDefault(false)
    }
    val reportar: suspend (ReelItem, String) -> Boolean = reporta@{ r, motivo ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@reporta false
        runCatching { sala.reportContent(EventId(r.id), motivo, null) }
        runCatching { sala.liveTimeline.replyMessage(EventId(r.id), "\uD83D\uDEA9 " + motivo, null, emptyList()).isSuccess }.getOrDefault(false)
    }
    val enviarAChat: suspend (ReelItem, RoomId) -> Boolean = enviar@{ r, destino ->
        val sala = salaId?.let { client.getJoinedRoom(it) } ?: return@enviar false
        runCatching { sala.liveTimeline.forwardEvents(listOf(EventId(r.id)), listOf(destino)).isSuccess }.getOrDefault(false)
    }

    val cargarArchivo: suspend (ReelItem) -> String? = cargar@{ r ->
        cacheArchivos[r.id]?.let { if (java.io.File(it).exists()) return@cargar it }
        val carpeta = java.io.File(contexto.filesDir, "reels_cache")
        val local = java.io.File(carpeta, r.id.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(80) + ".mp4")
        if (local.exists() && local.length() > 0) {
            cacheArchivos[r.id] = local.absolutePath
            return@cargar local.absolutePath
        }
        var archivo: java.io.File? = null
        for (intento in 0 until 4) {
            archivo = runCatching { client.matrixMediaLoader.downloadMediaFile(r.fuente, r.mime, r.nombreArchivo).getOrNull()?.toFile() }.getOrNull()
            if (archivo != null) break
            delay(1000L * (intento + 1))
        }
        val descargado: java.io.File = archivo ?: return@cargar null
        val ruta = try {
            withContext(Dispatchers.IO) {
                carpeta.mkdirs()
                // Se conservan 5 días; los más viejos se borran para no llenar el teléfono.
                carpeta.listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 5L * 24 * 3600_000L) f.delete()
                }
                descargado.copyTo(local, overwrite = true)
                local.absolutePath
            }
        } catch (_: Throwable) {
            descargado.absolutePath
        }
        cacheArchivos[r.id] = ruta
        ruta
    }

    return ChamosReelsState(
        mensaje = mensaje,
        cargando = cargando,
        reels = reels,
        comentarios = comentarios,
        muro = muro,
        envio = envio,
        soyAdmin = soyAdmin,
        chats = chats.filter { it.displayType == io.element.android.features.home.impl.model.RoomSummaryDisplayType.ROOM && !it.isChamosEstado() && !it.isChamosReels() && !it.isChamosMuro() && !it.isSpace }
            .map { Pair(it.roomId, it.name ?: "Chat") },
        activar = { activo = true },
        masAntiguos = masAntiguos,
        publicar = publicar,
        borrar = borrar,
        editar = editar,
        meGusta = meGusta,
        comentar = comentar,
        borrarComentario = borrarComentario,
        reportar = reportar,
        enviarAChat = enviarAChat,
        cargarArchivo = cargarArchivo,
    )
}

/* ---------------------------------------------------------------------------------------------------- */
/* Pantalla                                                                                              */
/* ---------------------------------------------------------------------------------------------------- */

@Composable
fun ChamosReelsPantalla(state: ChamosReelsState, inicioMuro: Boolean = false, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var subiendo by remember { mutableStateOf(false) }
    val verMuro = inicioMuro
    LaunchedEffect(Unit) { if (!inicioMuro) state.activar() }
    // La pantalla de neón se queda hasta que los Reels terminan de cargar (mínimo 2 segundos).
    var intro by remember { mutableStateOf(true) }
    var terminado by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2200)
        intro = false
        kotlinx.coroutines.delay(8000)
        if (state.reels.isNotEmpty()) terminado = true
    }
    LaunchedEffect(intro, state.cargando, state.reels.size) {
        if (!intro && !state.cargando && state.reels.isNotEmpty()) terminado = true
    }
    LaunchedEffect(verMuro) { if (verMuro) state.muro.activar() }

    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!terminado) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ChamosReelNeon()
                    if (state.reels.isEmpty()) {
                        Text(
                            text = when {
                                state.mensaje.isNotEmpty() -> state.mensaje
                                state.cargando -> "Cargando videos…"
                                else -> "Todavía no hay videos. ¡Sube el primero!"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                }
            } else {
                val pager = rememberPagerState { state.reels.size }
                LaunchedEffect(pager.currentPage, state.reels.size) {
                    if (pager.currentPage >= state.reels.size - 3) state.masAntiguos()
                }
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { pagina ->
                    val reel = state.reels.getOrNull(pagina)
                    if (reel != null) {
                        PaginaReel(
                            reel = reel,
                            state = state,
                            activo = pager.currentPage == pagina,
                            cerca = kotlin.math.abs(pager.currentPage - pagina) <= 1,
                        )
                    }
                }
            }
            if (verMuro) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101114))) { ChamosMuroPantalla(state.muro) }
            }
            if (!verMuro && state.envio.isNotEmpty()) {
                Text(
                    state.envio, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp, start = 16.dp, end = 16.dp)
                        .background(if (state.envio.startsWith("⚠")) Color(0xFF8A2A2A) else Color(0xCC2A3558), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
            // Barra superior
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✕", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { onCerrar() }.padding(8.dp))
                Text(if (verMuro || !terminado) "" else "Reels", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (verMuro) Text("   ", fontSize = 30.sp, modifier = Modifier.padding(8.dp))
                else Text("＋", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { subiendo = true }.padding(8.dp))
            }
        }
    }

    if (subiendo) {
        var desc by remember { mutableStateOf("") }
        val selector = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                val mime = contexto.contentResolver.getType(uri) ?: "video/mp4"
                val descripcion = desc.trim()
                subiendo = false
                Toast.makeText(contexto, "Subiendo tu video…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val error = state.publicar(uri, mime, descripcion)
                    Toast.makeText(contexto, error ?: "Video publicado", Toast.LENGTH_LONG).show()
                }
            }
        }
        AlertDialog(
            onDismissRequest = { subiendo = false },
            title = { Text("Subir un video") },
            text = {
                Column {
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it.take(200) },
                        placeholder = { Text("Cuéntales algo a los chamos (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Máximo $REELS_LIMITE_MB MB. Se publica para toda la comunidad Chamos.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selector.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("Elegir video") }
            },
            dismissButton = { TextButton(onClick = { subiendo = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PaginaReel(reel: ReelItem, state: ChamosReelsState, activo: Boolean, cerca: Boolean) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var ruta by remember(reel.id) { mutableStateOf<String?>(null) }
    var pausa by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var verComentarios by remember { mutableStateOf(false) }
    var compartir by remember { mutableStateOf(false) }
    var confirmarBorrar by remember { mutableStateOf(false) }
    var reportando by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf(false) }
    var textoNuevo by remember { mutableStateOf("") }

    LaunchedEffect(reel.id, cerca) {
        if (cerca && ruta == null) ruta = state.cargarArchivo(reel)
    }
    LaunchedEffect(activo) { if (!activo) pausa = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(reel.id) {
                detectTapGestures(
                    onTap = { pausa = !pausa },
                    onDoubleTap = { if (!reel.yoLike) scope.launch { state.meGusta(reel) } },
                )
            },
    ) {
        val r = ruta
        if (r != null) {
            VideoTextura(ruta = r, activo = activo, pausa = pausa, onProgreso = {}, onFin = {}, repetir = true)
        } else {
            Text("Cargando…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        if (pausa) Text("⏸", fontSize = 64.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center))

        // Degradado inferior para que se lea el texto
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )

        if (state.soyAdmin && reel.reportes > 0) {
            Text("\uD83D\uDEA9 Reportado " + reel.reportes + (if (reel.reportes == 1) " vez" else " veces"), color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 90.dp))
        }
        // Autor y lo que dice
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 40.dp, end = 90.dp)) {
            Text("@" + reel.autor, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (reel.descripcion.isNotBlank()) {
                Text(reel.descripcion, color = Color.White, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Botones de la derecha
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Avatar(
                avatarData = AvatarData(reel.autorId, reel.autor, reel.autorAvatar, AvatarSize.UserListItem),
                avatarType = AvatarType.User,
                forcedAvatarSize = 44.dp,
            )
            AccionReel(if (reel.yoLike) "❤️" else "🤍", reel.likes.toString()) { scope.launch { state.meGusta(reel) } }
            AccionReel("💬", reel.comentarios.toString()) { verComentarios = true }
            AccionReel("↗️", "Enviar") { compartir = true }
            Box {
                AccionReel("⋮", "") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (reel.propio) {
                        DropdownMenuItem(
                            text = { Text("Cambiar lo que dice") },
                            onClick = { menu = false; textoNuevo = reel.descripcion; editando = true },
                        )
                    }
                    if (reel.propio || state.soyAdmin) {
                        DropdownMenuItem(text = { Text("Borrar video") }, onClick = { menu = false; confirmarBorrar = true })
                    }
                    if (!reel.propio) {
                        DropdownMenuItem(text = { Text("Reportar este video") }, onClick = { menu = false; reportando = true })
                    }
                }
            }
        }
    }

    if (reportando) {
        val motivos = listOf("Contenido sexual", "Violencia o crueldad", "Acoso o insultos", "Estafa o spam", "Otro problema")
        AlertDialog(
            onDismissRequest = { reportando = false },
            title = { Text("¿Qué pasa con este video?") },
            text = {
                Column {
                    motivos.forEach { m ->
                        Text(m, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable {
                            reportando = false
                            scope.launch {
                                val ok = state.reportar(reel, m)
                                Toast.makeText(contexto, if (ok) "Gracias, ya lo reportamos" else "No se pudo reportar", Toast.LENGTH_SHORT).show()
                            }
                        }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reportando = false }) { Text("Cancelar") } },
        )
    }
    if (confirmarBorrar) {
        AlertDialog(
            onDismissRequest = { confirmarBorrar = false },
            title = { Text("¿Borrar este video?") },
            text = { Text("Se borra para todos y no se puede recuperar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrar = false
                    scope.launch {
                        val ok = state.borrar(reel)
                        Toast.makeText(contexto, if (ok) "Video borrado" else "No se pudo borrar", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrar = false }) { Text("Cancelar") } },
        )
    }
    if (editando) {
        AlertDialog(
            onDismissRequest = { editando = false },
            title = { Text("Cambiar lo que dice") },
            text = { OutlinedTextField(value = textoNuevo, onValueChange = { textoNuevo = it.take(200) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    editando = false
                    scope.launch {
                        val ok = state.editar(reel, textoNuevo.trim())
                        Toast.makeText(contexto, if (ok) "Listo, ya lo cambié" else "No se pudo editar", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editando = false }) { Text("Cancelar") } },
        )
    }
    if (verComentarios) ComentariosDialogo(reel, state) { verComentarios = false }
    if (compartir) CompartirDialogo(reel, state) { compartir = false }
}

private fun textoCompartido(contexto: android.content.Context, reel: ReelItem) {
    val texto = "Mira este video de " + reel.autor + " en Chamos" +
        (if (reel.descripcion.isNotBlank()) ": «" + reel.descripcion + "»" else "") +
        ". Descarga Chamos: https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, texto)
    }
    val chooser = android.content.Intent.createChooser(intent, "Compartir").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { contexto.startActivity(chooser) }
}

@Composable
private fun AccionReel(icono: String, texto: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Text(icono, fontSize = 32.sp, color = Color.White)
        if (texto.isNotEmpty()) Text(texto, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ComentariosDialogo(reel: ReelItem, state: ChamosReelsState, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var texto by remember { mutableStateOf("") }
    val lista = state.comentarios[reel.id].orEmpty()
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
                                    Text("🗑", fontSize = 18.sp, modifier = Modifier.clickable {
                                        scope.launch { state.borrarComentario(c) }
                                    }.padding(6.dp))
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.take(300) },
                    placeholder = { Text("Escribe un comentario") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = texto.trim()
                if (t.isNotEmpty()) {
                    texto = ""
                    scope.launch {
                        val ok = state.comentar(reel, t)
                        if (!ok) Toast.makeText(contexto, "No se pudo comentar", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("Enviar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

@Composable
private fun CompartirDialogo(reel: ReelItem, state: ChamosReelsState, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var elegirChat by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (elegirChat) "Enviar a un chat" else "Compartir") },
        text = {
            if (elegirChat) {
                LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                    items(state.chats, key = { it.first.value }) { (id, nombre) ->
                        Text(
                            nombre,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onCerrar()
                                scope.launch {
                                    val ok = state.enviarAChat(reel, id)
                                    Toast.makeText(contexto, if (ok) "Enviado a " + nombre else "No se pudo enviar", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            } else {
                Column {
                    TextButton(onClick = { elegirChat = true }) { Text("Enviar a un chat de Chamos") }
                    TextButton(onClick = { onCerrar(); textoCompartido(contexto, reel) }) { Text("Compartir fuera de Chamos") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

private val NEON_AZUL = Color(0xFF1E90FF)
private val NEON_VIOLETA = Color(0xFFB026FF)

/** Dos líneas (por defecto "CHAMOS REELS") en luz de neón azul y violeta que parpadea, sobre fondo negro. */
@Composable
internal fun ChamosReelNeon(linea1: String = "CHAMOS", linea2: String = "REELS") {
    val transicion = androidx.compose.animation.core.rememberInfiniteTransition(label = "neon")
    @Composable
    fun parpadeo(retraso: Int) = transicion.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 2600
                1f at 0
                1f at 700
                0.25f at 760
                1f at 820
                0.45f at 900
                1f at 960
                1f at 1900
                0.2f at 1960
                1f at 2040
                0.6f at 2120
                1f at 2200
            },
            initialStartOffset = androidx.compose.animation.core.StartOffset(retraso),
        ),
        label = "parpadeo",
    )
    val brilloAzul by parpadeo(0)
    val brilloVioleta by parpadeo(900)
    @Composable
    fun Linea(texto: String, color: Color, brillo: Float) {
        Text(
            text = texto,
            color = color.copy(alpha = brillo),
            fontSize = 52.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            style = androidx.compose.ui.text.TextStyle(
                color = color.copy(alpha = brillo),
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = color.copy(alpha = brillo),
                    offset = androidx.compose.ui.geometry.Offset.Zero,
                    blurRadius = 40f * brillo,
                ),
            ),
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Linea(linea1, NEON_AZUL, brilloAzul)
        Linea(linea2, NEON_VIOLETA, brilloVioleta)
    }
}
