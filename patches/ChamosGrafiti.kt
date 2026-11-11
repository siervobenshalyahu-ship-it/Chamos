package io.element.android.features.home.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as APath
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface as ATypeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Un estilo de letra de grafiti: forma de la letra, colores, contorno, sombra 3D, brillo y degradado. */
internal class EstiloGrafiti(
    val tipo: ATypeface,
    val relleno: Int,
    val contorno: Int,
    val sombra: Int,
    val trazo: Float,
    val brillo: Int,
    val mayus: Boolean = false,
    val gradiente: IntArray? = null,
    val gradientePos: FloatArray? = null,
    val gradienteHorizontal: Boolean = false,
    val inclinar: Float = 0f,
)

private const val AMARILLO = 0xFFFFD60A.toInt()
private const val AZUL_VE = 0xFF0033A0.toInt()
private const val ROJO_VE = 0xFFCF142B.toInt()

internal val ESTILOS_GRAFITI: List<EstiloGrafiti> = listOf(
    // Burbuja: celeste con borde blanco y sombra azul
    EstiloGrafiti(ATypeface.create("sans-serif-black", ATypeface.NORMAL), 0xFF29C5FF.toInt(), AColor.WHITE, 0xFF0B3C8C.toInt(), 0.12f, 0),
    // Spray: rosa con brillo
    EstiloGrafiti(ATypeface.create("cursive", ATypeface.BOLD), 0xFFFF2D95.toInt(), AColor.BLACK, 0, 0.09f, 0xFFFF2D95.toInt()),
    // Bolígrafo azul del cuaderno
    EstiloGrafiti(ATypeface.create("casual", ATypeface.NORMAL), 0xFF1E4FD8.toInt(), 0, 0, 0f, 0, inclinar = -0.18f),
    // Grafito del liceo
    EstiloGrafiti(ATypeface.create("cursive", ATypeface.NORMAL), 0xFF2B2B2B.toInt(), 0, 0, 0f, 0, inclinar = -0.1f),
    // Neón
    EstiloGrafiti(ATypeface.create("sans-serif", ATypeface.BOLD), AColor.WHITE, 0, 0, 0f, 0xFF00E5FF.toInt()),
    // Bloque amarillo con sombra roja
    EstiloGrafiti(ATypeface.create("monospace", ATypeface.BOLD), AMARILLO, AColor.BLACK, 0xFFE63946.toInt(), 0.1f, 0, mayus = true),
    // Fuego
    EstiloGrafiti(ATypeface.create("sans-serif-black", ATypeface.NORMAL), 0xFFFF9100.toInt(), 0xFF7A1500.toInt(), 0, 0.08f, 0xFFFFEA00.toInt()),
    // Elegante dorado
    EstiloGrafiti(ATypeface.create("serif", ATypeface.BOLD_ITALIC), 0xFFFFD700.toInt(), AColor.BLACK, 0xFF333333.toInt(), 0.07f, 0),
    // Delgada
    EstiloGrafiti(ATypeface.create("sans-serif-light", ATypeface.NORMAL), AColor.WHITE, 0, 0, 0f, 0),
    // Marcador verde
    EstiloGrafiti(ATypeface.create("casual", ATypeface.BOLD), 0xFF7CFF4D.toInt(), 0xFF0B5D1E.toInt(), 0, 0.1f, 0),
    // Venezuela: amarillo, azul y rojo
    EstiloGrafiti(
        ATypeface.create("sans-serif-black", ATypeface.NORMAL), AMARILLO, AColor.WHITE, AColor.BLACK, 0.1f, 0,
        gradiente = intArrayOf(AMARILLO, AMARILLO, AZUL_VE, AZUL_VE, ROJO_VE, ROJO_VE),
        gradientePos = floatArrayOf(0f, 0.33f, 0.34f, 0.66f, 0.67f, 1f),
    ),
    // Arcoíris
    EstiloGrafiti(
        ATypeface.create("sans-serif-black", ATypeface.NORMAL), AColor.RED, AColor.BLACK, 0, 0.08f, 0,
        gradiente = intArrayOf(0xFFFF2D2D.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt(), 0xFF3DDC4A.toInt(), 0xFF2D7BFF.toInt(), 0xFFB026FF.toInt()),
        gradienteHorizontal = true,
    ),
)

private val PALETA_GRAFITI = listOf(
    AColor.WHITE, 0xFFFFD60A.toInt(), 0xFFFF2D95.toInt(), 0xFF00E5FF.toInt(), 0xFF7CFF4D.toInt(),
    0xFFFF7A00.toInt(), 0xFFE63946.toInt(), 0xFFB026FF.toInt(), 0xFF1E4FD8.toInt(), AColor.BLACK,
)

private val NOMBRES_FONDOS = listOf("Ladrillo", "Cuaderno", "Cemento", "Pared negra", "Pared blanca", "Noche neón")

/** Dibuja una palabra (o varias líneas) con el estilo elegido, sobre fondo transparente. */
internal fun bitmapGrafiti(texto0: String, e: EstiloGrafiti, relleno: Int?, contorno: Int?, tamBase: Float = 200f): Bitmap {
    val texto = (if (e.mayus) texto0.uppercase() else texto0).ifBlank { " " }
    val colorRelleno = relleno ?: e.relleno
    val colorContorno = contorno ?: e.contorno
    val usaGradiente = relleno == null && e.gradiente != null
    val trazoRel = if (colorContorno != 0) (if (e.trazo > 0f) e.trazo else 0.08f) else 0f

    fun pintura(tam: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tam
        typeface = e.tipo
        textSkewX = e.inclinar
    }
    fun anchoMax(p: TextPaint) = texto.split("\n").maxOf { Layout.getDesiredWidth(it, p) }

    var tam = tamBase
    var ancho = anchoMax(pintura(tam))
    while (ancho > 1000f && tam > 30f) {
        tam -= 10f
        ancho = anchoMax(pintura(tam))
    }
    val w = Math.ceil(ancho.toDouble()).toInt().coerceAtLeast(1)
    fun capa(p: TextPaint) = StaticLayout.Builder.obtain(texto, 0, texto.length, p, w)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .build()

    val base = capa(pintura(tam))
    val pasosSombra = if (e.sombra != 0) (tam * 0.07f).toInt().coerceAtLeast(4) else 0
    val margen = (tam * 0.3f).toInt() + pasosSombra + 12
    val bmp = Bitmap.createBitmap(w + margen * 2, base.height + margen * 2, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.translate(margen.toFloat(), margen.toFloat())

    // Sombra en 3D
    if (e.sombra != 0) {
        for (i in pasosSombra downTo 1) {
            val p = pintura(tam).apply {
                color = e.sombra
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = tam * trazoRel
                strokeJoin = Paint.Join.ROUND
            }
            c.save()
            c.translate(i.toFloat(), i.toFloat())
            capa(p).draw(c)
            c.restore()
        }
    }
    // Brillo de neón
    if (e.brillo != 0) {
        val p = pintura(tam).apply {
            color = e.brillo
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = tam * trazoRel
            setShadowLayer(tam * 0.3f, 0f, 0f, e.brillo)
        }
        capa(p).draw(c)
    }
    // Contorno
    if (trazoRel > 0f) {
        val p = pintura(tam).apply {
            color = colorContorno
            style = Paint.Style.STROKE
            strokeWidth = tam * trazoRel
            strokeJoin = Paint.Join.ROUND
        }
        capa(p).draw(c)
    }
    // Relleno
    val p = pintura(tam).apply {
        color = colorRelleno
        style = Paint.Style.FILL
        if (usaGradiente) {
            val g = e.gradiente!!
            shader = if (e.gradienteHorizontal) {
                LinearGradient(0f, 0f, w.toFloat(), 0f, g, e.gradientePos, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, 0f, 0f, base.height.toFloat(), g, e.gradientePos, Shader.TileMode.CLAMP)
            }
        }
    }
    capa(p).draw(c)
    return bmp
}

private fun dibujarFondoGrafiti(c: Canvas, cual: Int, w: Int, h: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    when (cual) {
        0 -> {
            c.drawColor(0xFFCDBFAE.toInt())
            val rnd = java.util.Random(7)
            val bw = 120f
            val bh = 56f
            val g = 6f
            var fila = 0
            var y = 0f
            while (y < h) {
                var x = if (fila % 2 == 0) 0f else -bw / 2f
                while (x < w) {
                    val tono = rnd.nextInt(40) - 20
                    p.color = AColor.rgb((150 + tono).coerceIn(0, 255), (62 + tono / 2).coerceIn(0, 255), (44 + tono / 3).coerceIn(0, 255))
                    c.drawRect(x + g / 2f, y + g / 2f, x + bw - g / 2f, y + bh - g / 2f, p)
                    x += bw
                }
                y += bh
                fila++
            }
        }
        1 -> {
            c.drawColor(0xFFFBF6E3.toInt())
            p.color = 0xFF9CC3E6.toInt()
            p.strokeWidth = 3f
            var y = 140f
            while (y < h) {
                c.drawLine(0f, y, w.toFloat(), y, p)
                y += 54f
            }
            p.color = 0xFFE57373.toInt()
            p.strokeWidth = 4f
            c.drawLine(150f, 0f, 150f, h.toFloat(), p)
        }
        2 -> {
            c.drawColor(0xFF9C9C9C.toInt())
            val rnd = java.util.Random(3)
            for (i in 0 until 2200) {
                val t = 120 + rnd.nextInt(70)
                p.color = AColor.rgb(t, t, t)
                c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 2.5f, p)
            }
        }
        3 -> c.drawColor(0xFF121212.toInt())
        4 -> c.drawColor(0xFFF2F2F2.toInt())
        else -> {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2B0A4D.toInt(), 0xFF0A2A5C.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
    }
}

private class TrazoGrafiti(val color: Int, val ancho: Float, val spray: Boolean) {
    val puntos = mutableStateListOf<Offset>()
}

private fun dibujarTrazos(c: Canvas, trazos: List<TrazoGrafiti>, w: Float, h: Float) {
    val k = w / 1080f
    for (t in trazos) {
        val pts = t.puntos.toList()
        if (pts.isEmpty()) continue
        val path = APath()
        path.moveTo(pts[0].x * w, pts[0].y * h)
        if (pts.size == 1) {
            path.lineTo(pts[0].x * w + 0.1f, pts[0].y * h)
        } else {
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                path.quadTo(a.x * w, a.y * h, (a.x + b.x) / 2f * w, (a.y + b.y) / 2f * h)
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (t.spray) {
            listOf(2.2f to 40, 1.5f to 70, 1f to 150).forEach { (m, al) ->
                p.color = t.color
                p.alpha = al
                p.strokeWidth = t.ancho * k * m
                c.drawPath(path, p)
            }
        } else {
            p.color = t.color
            p.strokeWidth = t.ancho * k
            c.drawPath(path, p)
        }
    }
}

private class ElemGrafiti(var texto: String, estilo0: Int, cx0: Float, cy0: Float) {
    var estilo by mutableStateOf(estilo0)
    var relleno by mutableStateOf<Int?>(null)
    var contorno by mutableStateOf<Int?>(null)
    var cx by mutableStateOf(cx0)
    var cy by mutableStateOf(cy0)
    var escala by mutableStateOf(1f)
    var giro by mutableStateOf(0f)
    var bmp by mutableStateOf(bitmapGrafiti(texto, ESTILOS_GRAFITI[estilo0], null, null))

    fun actualizar() {
        bmp = bitmapGrafiti(texto, ESTILOS_GRAFITI[estilo], relleno, contorno)
    }
}


private val NEON_AZUL = Color(0xFF29C5FF)
private val NEON_FUCSIA = Color(0xFFFF2DD1)

/** Texto de los menús del grafiti: letras azul y fucsia alternadas con brillo de luz de neón, legibles sobre el fondo oscuro. */
@Composable
private fun NeonTexto(
    texto: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    fontSize: TextUnit = 15.sp,
    textAlign: TextAlign? = null,
) {
    val cuerpo = remember(texto) {
        buildAnnotatedString {
            var n = 0
            texto.forEach { ch ->
                if (ch.isWhitespace()) {
                    append(ch)
                } else {
                    val col = if (n % 2 == 0) NEON_AZUL else NEON_FUCSIA
                    withStyle(SpanStyle(color = col, shadow = Shadow(color = col.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 14f))) { append(ch) }
                    n++
                }
            }
        }
    }
    Text(cuerpo, modifier = modifier, fontWeight = fontWeight, fontSize = fontSize, textAlign = textAlign, style = TextStyle(shadow = Shadow(color = Color.White.copy(alpha = 0.25f), blurRadius = 2f)))
}

/** Editor de grafiti: fondo de pared o cuaderno, textos con distintas letras, colores y tamaños, y dibujo a mano. */
@Composable
fun ChamosGrafitiEditor(onListo: (Uri) -> Unit, onCancelar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var fondo by remember { mutableStateOf(0) }
    val elementos = remember { mutableStateListOf<ElemGrafiti>() }
    val trazos = remember { mutableStateListOf<TrazoGrafiti>() }
    var elegido by remember { mutableStateOf<ElemGrafiti?>(null) }
    var dibujando by remember { mutableStateOf(false) }
    var estiloNuevo by remember { mutableStateOf(0) }
    var dialogoTexto by remember { mutableStateOf(0) } // 0 cerrado, 1 agregar, 2 editar
    var colorTrazo by remember { mutableStateOf(PALETA_GRAFITI[1]) }
    var anchoTrazo by remember { mutableStateOf(24f) }
    var spray by remember { mutableStateOf(false) }
    var guardando by remember { mutableStateOf(false) }

    val fondoImg: ImageBitmap = remember(fondo) {
        val b = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        dibujarFondoGrafiti(Canvas(b), fondo, 1080, 1080)
        b.asImageBitmap()
    }
    val miniaturasFondo = remember {
        NOMBRES_FONDOS.indices.map { i ->
            val b = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
            dibujarFondoGrafiti(Canvas(b), i, 1080, 1080)
            val chica = Bitmap.createScaledBitmap(b, 120, 120, true)
            b.recycle()
            chica.asImageBitmap()
        }
    }
    val muestras = remember { ESTILOS_GRAFITI.map { bitmapGrafiti("Chamo", it, null, null, 110f).asImageBitmap() } }

    val lado = 320.dp
    val ladoPx = with(LocalDensity.current) { lado.toPx() }
    val k = lado.value / 1080f

    Dialog(onDismissRequest = onCancelar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111111))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancelar) { NeonTexto("Cancelar") }
                NeonTexto("Grafiti", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(
                    enabled = !guardando,
                    onClick = {
                        guardando = true
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                val salida = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
                                val c = Canvas(salida)
                                dibujarFondoGrafiti(c, fondo, 1080, 1080)
                                dibujarTrazos(c, trazos.toList(), 1080f, 1080f)
                                val pintura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                elementos.forEach { e ->
                                    val b = e.bmp
                                    val ew = b.width * e.escala
                                    val eh = b.height * e.escala
                                    c.save()
                                    c.translate(e.cx * 1080f, e.cy * 1080f)
                                    c.rotate(e.giro)
                                    c.drawBitmap(b, null, RectF(-ew / 2f, -eh / 2f, ew / 2f, eh / 2f), pintura)
                                    c.restore()
                                }
                                val dir = File(contexto.cacheDir, "chamos_grafiti").apply { mkdirs() }
                                val f = File(dir, "grafiti_" + System.currentTimeMillis() + ".jpg")
                                f.outputStream().use { salida.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                FileProvider.getUriForFile(contexto, contexto.packageName + ".fileprovider", f)
                            }
                            guardando = false
                            onListo(uri)
                        }
                    },
                ) { NeonTexto(if (guardando) "Guardando…" else "Listo", fontWeight = FontWeight.Bold) }
            }

            // Lienzo
            Box(
                modifier = Modifier
                    .size(lado)
                    .clip(RoundedCornerShape(6.dp))
                    .pointerInput(dibujando) {
                        if (dibujando) {
                            detectDragGestures(
                                onDragStart = { o ->
                                    val t = TrazoGrafiti(colorTrazo, anchoTrazo, spray)
                                    t.puntos.add(Offset(o.x / ladoPx, o.y / ladoPx))
                                    trazos.add(t)
                                },
                                onDrag = { cambio, _ ->
                                    trazos.lastOrNull()?.puntos?.add(Offset(cambio.position.x / ladoPx, cambio.position.y / ladoPx))
                                },
                            )
                        } else {
                            detectTransformGestures { _, pan, zoom, giro ->
                                elegido?.let { e ->
                                    e.cx = (e.cx + pan.x / ladoPx).coerceIn(0f, 1f)
                                    e.cy = (e.cy + pan.y / ladoPx).coerceIn(0f, 1f)
                                    e.escala = (e.escala * zoom).coerceIn(0.1f, 4f)
                                    e.giro += giro
                                }
                            }
                        }
                    },
            ) {
                Image(bitmap = fondoImg, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                ComposeCanvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c -> dibujarTrazos(c.nativeCanvas, trazos.toList(), size.width, size.height) }
                }
                elementos.forEach { e ->
                    val b = e.bmp
                    val ew = (b.width * e.escala * k).dp
                    val eh = (b.height * e.escala * k).dp
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(ew, eh)
                            .offset { IntOffset((e.cx * ladoPx - ew.toPx() / 2f).roundToInt(), (e.cy * ladoPx - eh.toPx() / 2f).roundToInt()) }
                            .graphicsLayer { rotationZ = e.giro }
                            .then(if (e === elegido && !dibujando) Modifier.border(1.dp, Color.Cyan) else Modifier)
                            .clickable(enabled = !dibujando) { elegido = e },
                    )
                }
            }

            // Fondos
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                miniaturasFondo.forEachIndexed { i, img ->
                    Image(
                        bitmap = img,
                        contentDescription = NOMBRES_FONDOS[i],
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(if (fondo == i) 3.dp else 1.dp, if (fondo == i) Color.Cyan else Color(0xFF555555), RoundedCornerShape(8.dp))
                            .clickable { fondo = i },
                    )
                }
            }

            // Acciones
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { dialogoTexto = 1 }) { NeonTexto("+ Texto") }
                if (!dibujando && elegido != null) {
                    TextButton(onClick = { dialogoTexto = 2 }) { NeonTexto("Cambiar texto") }
                    TextButton(onClick = {
                        elegido?.let { elementos.remove(it) }
                        elegido = null
                    }) { NeonTexto("Quitar texto") }
                }
                TextButton(onClick = { dibujando = !dibujando }) { NeonTexto(if (dibujando) "Volver a textos" else "✏️ Dibujar") }
                if (dibujando) {
                    TextButton(onClick = { if (trazos.isNotEmpty()) trazos.removeAt(trazos.lastIndex) }) { NeonTexto("Deshacer") }
                    TextButton(onClick = { trazos.clear() }) { NeonTexto("Borrar dibujo") }
                }
            }

            if (dibujando) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PALETA_GRAFITI.forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(if (colorTrazo == col) 3.dp else 1.dp, Color.Cyan.copy(alpha = if (colorTrazo == col) 1f else 0.3f), CircleShape)
                                .clickable { colorTrazo = col },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                    listOf("Fino" to 10f, "Medio" to 24f, "Grueso" to 50f).forEach { (n, a) ->
                        TextButton(onClick = { anchoTrazo = a }) { NeonTexto(n, fontWeight = if (anchoTrazo == a) FontWeight.Bold else FontWeight.Normal) }
                    }
                    TextButton(onClick = { spray = false }) { NeonTexto("Marcador", fontWeight = if (!spray) FontWeight.Bold else FontWeight.Normal) }
                    TextButton(onClick = { spray = true }) { NeonTexto("Spray", fontWeight = if (spray) FontWeight.Bold else FontWeight.Normal) }
                }
            } else {
                // Tabla de estilos de letra (se desplaza)
                val estiloActual = elegido?.estilo ?: estiloNuevo
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    muestras.withIndex().toList().chunked(3).forEach { fila ->
                        Row(Modifier.fillMaxWidth()) {
                            fila.forEach { (i, img) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(3.dp)
                                        .aspectRatio(1.9f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2A2A2A))
                                        .border(if (estiloActual == i) 2.dp else 0.dp, Color.Cyan, RoundedCornerShape(10.dp))
                                        .clickable {
                                            val e = elegido
                                            if (e != null) {
                                                e.estilo = i
                                                e.relleno = null
                                                e.contorno = null
                                                e.actualizar()
                                            } else {
                                                estiloNuevo = i
                                            }
                                        }
                                        .padding(4.dp),
                                ) {
                                    Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                            repeat(3 - fila.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
                val e = elegido
                if (e != null) {
                    NeonTexto("Relleno", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { e.relleno = null; e.actualizar() }) { NeonTexto("Original") }
                        PALETA_GRAFITI.forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(col))
                                    .border(if (e.relleno == col) 3.dp else 1.dp, Color.Cyan.copy(alpha = if (e.relleno == col) 1f else 0.3f), CircleShape)
                                    .clickable { e.relleno = col; e.actualizar() },
                            )
                        }
                    }
                    NeonTexto("Contorno", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { e.contorno = null; e.actualizar() }) { NeonTexto("Original") }
                        PALETA_GRAFITI.forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(col))
                                    .border(if (e.contorno == col) 3.dp else 1.dp, Color.Cyan.copy(alpha = if (e.contorno == col) 1f else 0.3f), CircleShape)
                                    .clickable { e.contorno = col; e.actualizar() },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { e.escala = (e.escala / 1.2f).coerceAtLeast(0.1f) }) { NeonTexto("Letra más chica") }
                        TextButton(onClick = { e.escala = (e.escala * 1.2f).coerceAtMost(4f) }) { NeonTexto("Letra más grande") }
                    }
                }
            }
        }
    }

    if (dialogoTexto != 0) {
        var texto by remember { mutableStateOf(if (dialogoTexto == 2) elegido?.texto.orEmpty() else "") }
        AlertDialog(
            onDismissRequest = { dialogoTexto = 0 },
            title = { Text(if (dialogoTexto == 2) "Cambiar texto" else "Escribe tu grafiti") },
            text = {
                TextField(
                    value = texto,
                    onValueChange = { texto = it.take(60) },
                    placeholder = { Text("Ej: Chamos 4ever") },
                    maxLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = texto.isNotBlank(),
                    onClick = {
                        if (dialogoTexto == 2) {
                            elegido?.let { it.texto = texto; it.actualizar() }
                        } else {
                            val e = ElemGrafiti(texto, estiloNuevo, 0.5f, (0.3f + 0.12f * elementos.size).coerceAtMost(0.85f))
                            e.escala = minOf(1f, 900f / e.bmp.width)
                            elementos.add(e)
                            elegido = e
                        }
                        dialogoTexto = 0
                    },
                ) { Text("Listo") }
            },
            dismissButton = { TextButton(onClick = { dialogoTexto = 0 }) { Text("Cancelar") } },
        )
    }
}
