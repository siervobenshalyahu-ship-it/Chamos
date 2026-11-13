/*
 * Chamos: fila propia de la lista de chats (tarjeta con borde neón azul y violeta, avatar con anillo neón, globo neón de no leídos).
 * Reemplaza el diseño de Element para las salas normales; las invitaciones y los placeholders siguen igual.
 */

package io.element.android.features.home.impl.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.home.impl.model.LatestEvent
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.libraries.core.extensions.orEmpty
import io.element.android.libraries.core.extensions.toSafeLength
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.ui.strings.CommonStrings

// Neón de Chamos: azul y violeta
private val AzulNeon = Color(0xFF29C5FF)
private val VioletaNeon = Color(0xFF9B5CFF)
private val TintaNeon = Color(0xFF06081A)
private val DegradadoNeon = Brush.horizontalGradient(listOf(AzulNeon, VioletaNeon))

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChamosFilaChat(
    room: RoomListRoomSummary,
    onClick: (RoomListRoomSummary) -> Unit,
    onLongClick: (RoomListRoomSummary) -> Unit,
    showUnreadCount: Boolean,
    showAllActivity: Boolean,
    modifier: Modifier = Modifier,
) {
    val forma = RoundedCornerShape(20.dp)
    val sinLeer = room.hasNewContent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(forma)
            .background(ElementTheme.colors.bgSubtleSecondary, forma)
            .border(BorderStroke(if (sinLeer) 1.6.dp else 1.dp, DegradadoNeon), forma)
            .combinedClickable(onClick = { onClick(room) }, onLongClick = { onLongClick(room) })
            .heightIn(min = 76.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.border(BorderStroke(2.dp, DegradadoNeon), CircleShape).padding(3.dp)) {
            Avatar(
                avatarData = room.avatarData,
                avatarType = if (room.isSpace) {
                    AvatarType.Space(isTombstoned = room.isTombstoned)
                } else {
                    AvatarType.Room(heroes = room.heroes, isTombstoned = room.isTombstoned)
                },
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = room.name?.toSafeLength(ellipsize = true) ?: stringResource(CommonStrings.common_no_room_name),
                    fontStyle = FontStyle.Italic.takeIf { room.name == null },
                    style = estiloChamos(17, if (sinLeer) FontWeight.Bold else FontWeight.SemiBold).let { if (sinLeer) it.copy(shadow = Shadow(AzulNeon, Offset.Zero, 14f)) else it },
                    color = ElementTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = room.timestamp ?: "",
                    style = estiloChamos(12, if (sinLeer) FontWeight.Bold else FontWeight.Normal),
                    color = if (sinLeer || room.isHighlighted) AzulNeon else ElementTheme.colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    room.isTombstoned -> Texto("Esta sala ya no se usa", Color.Unspecified, Modifier.weight(1f))
                    room.latestEvent is LatestEvent.Error -> {
                        Icon(
                            modifier = Modifier.size(15.dp),
                            imageVector = CompoundIcons.ErrorSolid(),
                            contentDescription = null,
                            tint = ElementTheme.colors.iconCriticalPrimary,
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Texto(stringResource(CommonStrings.common_message_failed_to_send), ElementTheme.colors.textCriticalPrimary, Modifier.weight(1f))
                    }
                    else -> {
                        if (room.latestEvent is LatestEvent.Sending) {
                            Icon(
                                modifier = Modifier.size(15.dp),
                                imageVector = CompoundIcons.Time(),
                                contentDescription = stringResource(CommonStrings.common_sending),
                                tint = ElementTheme.colors.iconTertiary,
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        val previo = room.latestEvent.content()
                        val texto = previo as? AnnotatedString ?: AnnotatedString(text = previo.orEmpty().toString())
                        Text(
                            modifier = Modifier.weight(1f),
                            text = texto,
                            style = estiloChamos(14, FontWeight.Normal),
                            color = if (sinLeer) ElementTheme.colors.textPrimary else ElementTheme.colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (room.hasRoomCall) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = CompoundIcons.VideoCallSolid(),
                            contentDescription = stringResource(CommonStrings.a11y_notifications_ongoing_call),
                            tint = AzulNeon,
                        )
                    }
                    if (room.userDefinedNotificationMode == RoomNotificationMode.MUTE) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = CompoundIcons.NotificationsOffSolid(),
                            contentDescription = stringResource(CommonStrings.a11y_notifications_muted),
                            tint = ElementTheme.colors.iconQuaternary,
                        )
                    }
                    if (sinLeer) {
                        val cuenta = room.unreadIndicatorCount(showUnreadCount = showUnreadCount, showAllActivity = showAllActivity)
                        if (cuenta != null) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 22.dp)
                                    .background(DegradadoNeon, RoundedCornerShape(11.dp))
                                    .padding(horizontal = if (cuenta > 0) 7.dp else 0.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cuenta > 0) {
                                    Text(
                                        text = if (cuenta > 99) "99+" else cuenta.toString(),
                                        style = estiloChamos(12, FontWeight.Bold),
                                        color = TintaNeon,
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun estiloChamos(tam: Int, peso: FontWeight) = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = tam.sp, fontWeight = peso)

@Composable
private fun Texto(texto: String, color: Color, modifier: Modifier) {
    Text(
        modifier = modifier,
        text = texto,
        style = estiloChamos(14, FontWeight.Normal),
        color = if (color == Color.Unspecified) ElementTheme.colors.textSecondary else color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
