package io.element.android.x

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ChamosEstilo

/**
 * Marco de luz de neón alrededor de toda la app. Se puede cambiar de color, quitar el movimiento o quitarlo del todo
 * desde "Estilos". No intercepta toques.
 */
@Composable
fun ChamosMarcoNeon() {
    ChamosEstilo.init(LocalContext.current)
    if (!ChamosEstilo.marcoActivo) return
    val movimiento = ChamosEstilo.marcoMovimiento
    val arcoiris = ChamosEstilo.marcoColor >= ChamosEstilo.neonColores.size
    val transicion = rememberInfiniteTransition(label = "marco")
    val vuelta by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "vuelta",
    )
    val tono by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "tono",
    )
    val base = if (arcoiris) Color.hsv(if (movimiento) tono else 190f, 0.85f, 1f) else ChamosEstilo.neonColores[ChamosEstilo.marcoColor]

    Canvas(modifier = Modifier.fillMaxSize()) {
        val margen = 1.5.dp.toPx()
        val tam = Size(size.width - margen * 2, size.height - margen * 2)
        val radio = CornerRadius(26.dp.toPx())
        val origen = Offset(margen, margen)
        // Resplandor: capas cada vez más anchas y transparentes, como un tubo de neón.
        listOf(11.dp to 0.07f, 7.dp to 0.12f, 4.dp to 0.22f).forEach { (extra, alfa) ->
            drawRoundRect(base.copy(alpha = alfa), topLeft = origen, size = tam, cornerRadius = radio, style = Stroke(width = 2.dp.toPx() + extra.toPx()))
        }
        drawRoundRect(base.copy(alpha = 0.95f), topLeft = origen, size = tam, cornerRadius = radio, style = Stroke(width = 2.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = 0.35f), topLeft = origen, size = tam, cornerRadius = radio, style = Stroke(width = 0.8.dp.toPx()))
        if (movimiento) {
            // Una luz que recorre el borde, con cola.
            val camino = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(origen.x, origen.y, origen.x + tam.width, origen.y + tam.height, radio)) }
            val medida = PathMeasure()
            medida.setPath(camino, false)
            val largo = medida.length
            val contrario = if (arcoiris) Color.hsv((tono + 180f) % 360f, 0.85f, 1f) else Color.White
            for (i in 0 until 16) {
                val f = ((vuelta - i * 0.0035f) % 1f + 1f) % 1f
                val p = medida.getPosition(largo * f)
                val a = (1f - i / 16f)
                drawCircle(contrario.copy(alpha = 0.55f * a), radius = (7.dp.toPx() * a).coerceAtLeast(1.dp.toPx()), center = p)
                drawCircle(Color.White.copy(alpha = 0.9f * a), radius = (2.6.dp.toPx() * a).coerceAtLeast(0.6.dp.toPx()), center = p)
            }
        }
    }
}
