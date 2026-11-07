package io.element.android.features.home.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val AzulNeon = Color(0xFF2F8BFF)
private val VioletaNeon = Color(0xFFB026FF)

private data class ResumenVC(val likes: Int, val seguidores: Int, val compartidos: Int, val denuncias: Int)

private suspend fun pedirResumen(): ResumenVC? = withContext(Dispatchers.IO) {
    try {
        val token = ChamosNumero.tokenSesion() ?: return@withContext null
        val con = URL("https://chamos-push.siervodeyhwh57.workers.dev/video/resumen").openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 10000
        con.setRequestProperty("Authorization", "Bearer $token")
        if (con.responseCode != 200) return@withContext null
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        con.disconnect()
        ResumenVC(j.optInt("likes"), j.optInt("seguidores"), j.optInt("compartidos") + j.optInt("guardados"), j.optInt("denuncias"))
    } catch (e: Exception) {
        null
    }
}

/**
 * Barra ovalada bajo la foto del perfil: "VC" en neón (azul y violeta que se intercambian entre letras y parpadean)
 * y los números de Video Chamos: likes a tus videos, seguidores, videos compartidos o guardados y denuncias.
 */
@Composable
fun ChamosBarraVC(modifier: Modifier = Modifier) {
    var datos by remember { mutableStateOf<ResumenVC?>(null) }
    var recarga by remember { mutableStateOf(0) }
    // El botón 🔄 vuelve a pedir los números (no borra nada).
    DisposableEffect(Unit) {
        val accion: () -> Unit = { recarga++ }
        io.element.android.libraries.matrix.api.ChamosReinicio.acciones.add(accion)
        onDispose { io.element.android.libraries.matrix.api.ChamosReinicio.acciones.remove(accion) }
    }
    // Se actualiza sola cada 15 segundos para que los likes, compartidos, seguidores y denuncias aparezcan al momento.
    LaunchedEffect(recarga) {
        while (true) {
            pedirResumen()?.let { datos = it }
            delay(15_000)
        }
    }
    val t = rememberInfiniteTransition(label = "vc")
    val mezcla by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mezclaVC",
    )
    val parpadeo by t.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2600
                1f at 0
                0.35f at 300
                1f at 520
                0.55f at 900
                1f at 1100
                1f at 2000
                0.4f at 2200
                1f at 2400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "parpadeoVC",
    )
    val colorV = lerp(AzulNeon, VioletaNeon, mezcla)
    val colorC = lerp(VioletaNeon, AzulNeon, mezcla)
    val borde = lerp(AzulNeon, VioletaNeon, mezcla)

    @Composable
    fun letra(c: String, color: Color) = Text(
        text = c,
        style = TextStyle(
            color = color.copy(alpha = parpadeo),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            shadow = Shadow(color = color, offset = Offset.Zero, blurRadius = 26f * parpadeo),
        ),
    )

    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        letra("V", colorV)
        letra("C", colorC)
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFF0A0A14),
            border = BorderStroke(2.dp, borde.copy(alpha = 0.4f + 0.6f * parpadeo)),
            modifier = Modifier.weight(1f),
        ) {
            val d = datos
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("❤" to d?.likes, "👥" to d?.seguidores, "↗" to d?.compartidos, "🚩" to d?.denuncias).forEach { (icono, n) ->
                    Text(
                        text = "$icono ${n ?: "–"}",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(color = borde, offset = Offset.Zero, blurRadius = 10f),
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
