package io.element.android.features.messages.impl.timeline.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemAudioContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState

/**
 * Estado de entrega de mis mensajes (solo lo ve quien los envía):
 * 0 = enviándose, 1 = enviado (1 palomita verde), 2 = llegó (2 violeta), 3 = la otra persona está escribiendo (2 azul).
 * Matrix no tiene "entregado": "llegó" significa que la otra persona ya abrió el chat (recibo de lectura).
 */
object ChamosEnvio {
    /** Alguien (la otra persona) está escribiendo en el chat abierto. */
    val escribiendo = mutableStateOf(false)

    /** Hora del último mensaje mío que ya tiene recibo de lectura: todo lo anterior también llegó. */
    val leidoHasta = mutableLongStateOf(0L)

    fun reiniciar() {
        escribiendo.value = false
        leidoHasta.longValue = 0L
    }

    fun esAudio(e: TimelineItem.Event): Boolean = e.content is TimelineItemVoiceContent || e.content is TimelineItemAudioContent

    /** -1 = no mostrar. */
    fun estado(e: TimelineItem.Event): Int {
        if (!e.isMine || e.content is TimelineItemRedactedContent || e.failedToSend) return -1
        if (e.localSendState is LocalEventSendState.Sending) return 0
        val llego = e.readReceiptState.receipts.isNotEmpty() || (e.sentTimeMillis in 1..leidoHasta.longValue)
        return when {
            llego && escribiendo.value -> 3
            llego -> 2
            else -> 1
        }
    }

    /** Anota que este mensaje ya fue leído (para marcar también los anteriores). */
    @Composable
    fun anotar(e: TimelineItem.Event) {
        val ts = e.sentTimeMillis
        val leido = e.isMine && e.readReceiptState.receipts.isNotEmpty()
        SideEffect {
            if (leido && ts > leidoHasta.longValue) leidoHasta.longValue = ts
        }
    }
}

val NeonVerde = Color(0xFF00FF9C)
val NeonVioleta = Color(0xFFB388FF)
val NeonAzul = Color(0xFF3D9BFF)

/** Palomitas de neón: 1 verde (enviado), 2 violeta (llegó), 2 azul (la otra persona está escribiendo). */
@Composable
fun ChamosPalomitas(estado: Int, modifier: Modifier = Modifier) {
    if (estado < 0) return
    val color = when (estado) {
        3 -> NeonAzul
        2 -> NeonVioleta
        1 -> NeonVerde
        else -> Color(0xFF9AA0A6)
    }
    val t = rememberInfiniteTransition(label = "palomitas")
    // Solo late (animación) cuando la otra persona está escribiendo; el resto queda fijo para gastar menos batería.
    val brilloE = if (estado == 3) t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "brilloPalomitas",
    ) else null
    val brillo = brilloE?.value ?: 1f
    val texto = when (estado) {
        0 -> "◷"
        1 -> "✓"
        else -> "✓✓"
    }
    Row(modifier = modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = texto,
            style = TextStyle(
                color = color.copy(alpha = if (estado == 0) 0.9f else brillo),
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-3).sp,
                shadow = if (estado == 0) null else Shadow(color = color, offset = Offset.Zero, blurRadius = 14f * brillo),
            ),
        )
        Spacer(Modifier.width(3.dp))
    }
}
