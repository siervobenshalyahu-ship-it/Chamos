package io.element.android.features.home.impl

import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Un estado de un contacto (o el mío) = una sala privada con este prefijo en el nombre. */
const val ESTADO_PREFIJO = "Estado · "

data class EstadoItem(
    val roomId: RoomId,
    val nombre: String,
    val sinLeer: Boolean,
    val esMio: Boolean,
)

/**
 * Lógica de "Estados" de Chamos. Cada persona tiene una sala privada "Estado · Nombre" a la que invita a
 * sus contactos (los de sus chats directos). Las salas de estado se ocultan de la lista de chats.
 */
object ChamosEstados {
    private val _items = MutableStateFlow<List<EstadoItem>>(emptyList())
    val items: StateFlow<List<EstadoItem>> = _items

    @Volatile
    private var client: MatrixClient? = null
    @Volatile
    private var scope: CoroutineScope? = null
    private val joining = mutableSetOf<RoomId>()
    private val muted = mutableSetOf<RoomId>()
    private val invitedPeers = mutableSetOf<String>()
    @Volatile
    private var creating = false
    @Volatile
    private var lastAll: List<RoomSummary> = emptyList()

    fun esEstado(s: RoomSummary): Boolean = s.info.name?.startsWith(ESTADO_PREFIJO) == true

    fun attach(c: MatrixClient, s: CoroutineScope) {
        client = c
        scope = s
    }

    /** Se llama cada vez que cambia la lista de salas. */
    fun onRooms(all: List<RoomSummary>) {
        lastAll = all
        val c = client ?: return
        val sc = scope ?: return
        val me = c.sessionId
        val estados = all.filter { esEstado(it) }
        val out = mutableListOf<EstadoItem>()
        for (s in estados) {
            val info = s.info
            when (info.currentUserMembership) {
                CurrentUserMembership.INVITED -> {
                    if (joining.add(s.roomId)) sc.launch { c.joinRoom(s.roomId) }
                }
                CurrentUserMembership.JOINED -> {
                    if (muted.add(s.roomId)) {
                        sc.launch { c.notificationSettingsService.setRoomNotificationMode(s.roomId, RoomNotificationMode.MUTE) }
                    }
                    val mio = info.creators.any { it == me }
                    out.add(
                        EstadoItem(
                            roomId = s.roomId,
                            nombre = (info.name ?: "").removePrefix(ESTADO_PREFIJO).ifBlank { "Contacto" },
                            sinLeer = !mio && (info.numUnreadMessages > 0 || info.isMarkedUnread),
                            esMio = mio,
                        )
                    )
                }
                else -> Unit
            }
        }
        _items.value = out.sortedWith(compareByDescending<EstadoItem> { it.esMio }.thenByDescending { it.sinLeer })

        // invitar a los contactos nuevos a mi sala de estado
        val miSala = out.firstOrNull { it.esMio } ?: return
        val peers = contactos(all)
        val nuevos = peers.filter { invitedPeers.add(it.value) }
        if (nuevos.isNotEmpty()) {
            sc.launch {
                val room = c.getJoinedRoom(miSala.roomId) ?: return@launch
                nuevos.forEach { room.inviteUserById(it) }
            }
        }
    }

    private fun contactos(all: List<RoomSummary>): List<UserId> =
        all.filter { it.isDm && it.info.currentUserMembership == CurrentUserMembership.JOINED }
            .mapNotNull { it.info.heroes.firstOrNull()?.userId }
            .distinct()

    /** Abre mi sala de estado; si no existe la crea e invita a mis contactos. */
    fun abrirMio(onReady: (RoomId) -> Unit) {
        val c = client ?: return
        val sc = scope ?: return
        val existente = _items.value.firstOrNull { it.esMio }
        if (existente != null) {
            onReady(existente.roomId)
            return
        }
        if (creating) return
        creating = true
        sc.launch {
            try {
                val nombre = c.userProfile.value.displayName?.takeIf { it.isNotBlank() } ?: c.sessionId.value
                val peers = contactos(lastAll)
                peers.forEach { invitedPeers.add(it.value) }
                val r = c.createRoom(
                    CreateRoomParameters(
                        name = ESTADO_PREFIJO + nombre,
                        topic = "Estados de Chamos (se borran a las 24 horas)",
                        isEncrypted = false,
                        visibility = RoomVisibility.Private,
                        preset = RoomPreset.PRIVATE_CHAT,
                        invite = peers,
                    )
                )
                r.getOrNull()?.let { id ->
                    c.notificationSettingsService.setRoomNotificationMode(id, RoomNotificationMode.MUTE)
                    muted.add(id)
                    onReady(id)
                }
            } finally {
                creating = false
            }
        }
    }
}
