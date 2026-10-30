package io.element.android.features.home.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.features.home.impl.datasource.RoomListDataSource
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Las salas de estados de Chamos empiezan con este caracter invisible. */
const val CHAMOS_ESTADO_MARCA = "⁣"
private const val CHAMOS_ESTADO_PREFIJO = "Estado de "
private const val CHAMOS_ESTADO_HORAS = 24L

fun RoomListRoomSummary.isChamosEstado(): Boolean = name?.startsWith(CHAMOS_ESTADO_MARCA) == true

data class ChamosEstadoItem(
    val roomId: RoomId,
    val titulo: String,
    val nuevo: Boolean,
)

data class ChamosEstadosState(
    val propio: ChamosEstadoItem?,
    val otros: List<ChamosEstadoItem>,
    /** Abre mi sala de estados; si no existe todavía, la crea e invita a mis contactos. */
    val abrirPropio: suspend () -> RoomId?,
)

private fun RoomListRoomSummary.tituloEstado(): String =
    (name ?: "").removePrefix(CHAMOS_ESTADO_MARCA).removePrefix(CHAMOS_ESTADO_PREFIJO).ifBlank { "Contacto" }

@Composable
fun rememberChamosEstados(client: MatrixClient, dataSource: RoomListDataSource): ChamosEstadosState {
    val salas by dataSource.statusRoomsFlow.collectAsState(initial = emptyList())
    val chats by dataSource.roomSummariesFlow.collectAsState(initial = emptyList())
    val esMia: SnapshotStateMap<RoomId, Boolean> = remember { mutableStateMapOf() }
    val intentos = remember { mutableSetOf<RoomId>() }
    val silenciadas = remember { mutableSetOf<RoomId>() }

    // Saber cuáles son mías (soy la creadora) y entrar solo a las invitaciones de otras personas.
    LaunchedEffect(salas.map { it.roomId to it.displayType }) {
        for (sala in salas) {
            if (esMia[sala.roomId] == null) {
                val info = client.getRoomInfo(sala.roomId).getOrNull()
                esMia[sala.roomId] = info?.creators?.contains(client.sessionId) == true
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
    }

    val propia = salas.firstOrNull { esMia[it.roomId] == true && it.displayType == RoomSummaryDisplayType.ROOM }
    val otras = salas.filter {
        esMia[it.roomId] == false && it.displayType == RoomSummaryDisplayType.ROOM
    }

    val contactosIds = chats.filter { it.isDm && it.displayType == RoomSummaryDisplayType.ROOM }.map { it.roomId }

    suspend fun contactos(): List<UserId> = contactosIds.mapNotNull { id ->
        runCatching { client.getRoom(id)?.getDirectRoomMember()?.userId }.getOrNull()
    }.distinct()

    // Invitar a los contactos a mi sala de estados.
    val invitados = remember { mutableSetOf<UserId>() }
    LaunchedEffect(propia?.roomId, contactosIds) {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        for (usuario in contactos()) {
            if (invitados.add(usuario)) sala.inviteUserById(usuario)
        }
    }

    // Borrar mis estados con más de 24 horas.
    LaunchedEffect(propia?.roomId) {
        val id = propia?.roomId ?: return@LaunchedEffect
        val sala = client.getJoinedRoom(id) ?: return@LaunchedEffect
        runCatching { sala.liveTimeline.paginate(Timeline.PaginationDirection.BACKWARDS) }
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
    }

    val abrirPropio: suspend () -> RoomId? = abrir@{
        propia?.roomId?.let { return@abrir it }
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
        ).getOrNull() ?: return@abrir null
        esMia[creada] = true
        client.getJoinedRoom(creada)?.let { sala ->
            // Solo yo puedo publicar; los demás solo ven.
            sala.powerLevels().getOrNull()?.let { niveles ->
                sala.updatePowerLevels(niveles.copy(eventsDefault = 50))
            }
        }
        client.notificationSettingsService.setRoomNotificationMode(creada, RoomNotificationMode.MUTE)
        creada
    }

    return ChamosEstadosState(
        propio = propia?.let { ChamosEstadoItem(it.roomId, "Mi estado", false) },
        otros = otras.map { ChamosEstadoItem(it.roomId, it.tituloEstado(), it.hasNewContent) },
        abrirPropio = abrirPropio,
    )
}

/** Fila "Estados" en la parte superior de la pantalla principal de Chamos. */
@Composable
fun ChamosEstadosRow(
    state: ChamosEstadosState?,
    onOpenRoom: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var ocupado by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Estados",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EstadoCirculo(
                    texto = if (state?.propio != null) "✓" else "+",
                    titulo = "Mi estado",
                    anillo = state?.propio != null,
                    onClick = {
                        if (state != null && !ocupado) {
                            ocupado = true
                            scope.launch {
                                val id = state.abrirPropio()
                                ocupado = false
                                if (id != null) onOpenRoom(id)
                            }
                        }
                    },
                )
            }
            items(state?.otros.orEmpty(), key = { it.roomId.value }) { estado ->
                EstadoCirculo(
                    texto = estado.titulo.firstOrNull()?.uppercase() ?: "?",
                    titulo = estado.titulo,
                    anillo = estado.nuevo,
                    onClick = { onOpenRoom(estado.roomId) },
                )
            }
        }
    }
}

@Composable
private fun EstadoCirculo(texto: String, titulo: String, anillo: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
            .size(width = 68.dp, height = 90.dp),
    ) {
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
            Text(texto, fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
