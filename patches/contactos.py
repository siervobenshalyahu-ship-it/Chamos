# Iniciar chat: quita Nueva sala / Directorio / Invitar / Unirse por dirección y muestra la lista completa
# de contactos (todos los chats directos), con scroll. Uso: python3 contactos.py RAIZ_DEL_REPO
import sys, os
root = sys.argv[1]
base = os.path.join(root, "features/startchat/impl/src/main/kotlin/io/element/android/features/startchat/impl")

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) Todos los contactos (chats directos donde estoy dentro), no solo los visitados hace poco
rec = os.path.join(root, "libraries/matrix/api/src/main/kotlin/io/element/android/libraries/matrix/api/room/recent/RecentDirectRoom.kt")
s = open(rec, encoding="utf-8").read()
s += '''
/** Chamos: todos los chats directos en los que estoy, uno por persona. */
fun MatrixClient.getTodosLosContactos(): Flow<RecentDirectRoom> = flow {
    val foundUserIds = mutableSetOf<UserId>()
    for (roomId in getJoinedRoomIds().getOrDefault(emptySet())) {
        getRoom(roomId)?.use { room ->
            val info = room.info()
            if (info.isDm && info.currentUserMembership == CurrentUserMembership.JOINED) {
                val otherUser = room.getDirectRoomMember()?.toMatrixUser()
                if (otherUser != null && foundUserIds.add(otherUser.userId)) {
                    emit(RecentDirectRoom(room.roomId, otherUser))
                }
            }
        }
    }
}
'''
open(rec, "w", encoding="utf-8").write(s)

edit(os.path.join(base, "userlist/DefaultUserListPresenter.kt"), [
    ("import io.element.android.libraries.matrix.api.room.recent.getRecentDirectRooms\n",
     "import io.element.android.libraries.matrix.api.room.recent.getRecentDirectRooms\nimport io.element.android.libraries.matrix.api.room.recent.getTodosLosContactos\n"),
    ("""            recentDirectRooms = matrixClient
                .getRecentDirectRooms()
                .take(MAX_SUGGESTIONS_COUNT)
                .toList()
""", """            recentDirectRooms = matrixClient
                .getTodosLosContactos()
                .take(Int.MAX_VALUE)
                .toList()
                .sortedBy { (it.matrixUser.displayName ?: it.matrixUser.userId.value).lowercase() }
"""),
])

edit(os.path.join(base, "root/StartChatView.kt"), [
    ("""        item {
            CreateRoomActionButton(
                iconRes = CompoundDrawables.ic_compound_plus,
                text = stringResource(id = R.string.screen_create_room_action_create_room),
                onClick = onNewRoomClick,
            )
        }
        item {
            CreateRoomActionButton(
                iconRes = CompoundDrawables.ic_compound_list_bulleted,
                text = stringResource(id = R.string.screen_room_directory_search_title),
                onClick = onRoomDirectorySearchClick,
            )
        }
        item {
            CreateRoomActionButton(
                iconRes = CompoundDrawables.ic_compound_share_android,
                text = stringResource(id = CommonStrings.action_invite_friends_to_app, state.applicationName),
                onClick = onInvitePeopleClick,
            )
        }
        item {
            CreateRoomActionButton(
                iconRes = CompoundDrawables.ic_compound_room,
                text = stringResource(R.string.screen_start_chat_join_room_by_address_action),
                onClick = onJoinByAddressClick,
            )
        }
""", ""),
    ("                    title = stringResource(id = CommonStrings.common_suggestions),\n",
     "                    title = \"Mis contactos\",\n"),
])
print("contactos OK")
