package io.element.android.features.messages.impl.timeline.components.event

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Mensajes borrados que el usuario quitó de su pantalla y estado de los audios recibidos:
 * 0 = nuevo (verde), 1 = ya abrió el chat (violeta), 2 = ya lo reprodujo (azul).
 */
object ChamosAvisosChat {
    private var prefs: SharedPreferences? = null
    private val ocultos = mutableStateOf<Set<String>>(emptySet())
    private val vistos = mutableStateOf<Set<String>>(emptySet())
    private val reproducidos = mutableStateOf<Set<String>>(emptySet())

    private fun p(): SharedPreferences? {
        if (prefs == null) {
            try {
                val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
                prefs = app?.getSharedPreferences("chamos_audio", Context.MODE_PRIVATE)
                prefs?.let {
                    ocultos.value = it.getStringSet("ocultos", emptySet()).orEmpty().toSet()
                    vistos.value = it.getStringSet("vistos", emptySet()).orEmpty().toSet()
                    reproducidos.value = it.getStringSet("reproducidos", emptySet()).orEmpty().toSet()
                }
            } catch (e: Exception) {
                android.util.Log.w("Chamos", "avisos chat: sin preferencias", e)
            }
        }
        return prefs
    }

    private fun guardar(clave: String, valor: Set<String>) {
        p()?.edit()?.putStringSet(clave, valor)?.apply()
    }

    fun oculto(id: String?): Boolean {
        p()
        return id != null && id in ocultos.value
    }

    fun ocultar(id: String) {
        p()
        ocultos.value = ocultos.value + id
        guardar("ocultos", ocultos.value)
    }

    fun estadoAudio(id: String?): Int {
        p()
        return when {
            id == null -> 0
            id in reproducidos.value -> 2
            id in vistos.value -> 1
            else -> 0
        }
    }

    fun marcarVisto(id: String) {
        p()
        if (id !in vistos.value) { vistos.value = vistos.value + id; guardar("vistos", vistos.value) }
    }

    fun marcarReproducido(id: String) {
        p()
        if (id !in reproducidos.value) { reproducidos.value = reproducidos.value + id; guardar("reproducidos", reproducidos.value) }
        if (id !in vistos.value) { vistos.value = vistos.value + id; guardar("vistos", vistos.value) }
    }
}

/** Círculo de luz de neón parpadeante alrededor del botón de reproducir: verde, violeta o azul. */
@Composable
fun AroAudioNeon(estado: Int, contenido: @Composable () -> Unit) {
    val color = when (estado) {
        10 -> Color(0xFFFFC107)
        11 -> Color(0xFF00FF9C)
        12 -> Color(0xFFB388FF)
        13 -> Color(0xFF3D9BFF)
        2 -> Color(0xFF3D7BFF)
        1 -> Color(0xFFB388FF)
        else -> Color(0xFF00FF9C)
    }
    val t = rememberInfiniteTransition(label = "audioNeon")
    val latido by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "latidoAudio",
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .drawBehind {
                val radio = size.minDimension / 2f - 3.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = color.copy(alpha = 0.18f * latido), radius = radio + 2.dp.toPx(), center = c, style = Stroke(width = 7.dp.toPx()))
                drawCircle(color = color.copy(alpha = 0.40f * latido), radius = radio, center = c, style = Stroke(width = 4.5.dp.toPx()))
                drawCircle(color = color.copy(alpha = latido), radius = radio, center = c, style = Stroke(width = 2.2.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) { contenido() }
}
