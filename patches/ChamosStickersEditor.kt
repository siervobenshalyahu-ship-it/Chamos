package io.element.android.features.messages.impl.messagecomposer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import io.element.android.compound.theme.ElementTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Recorta a la persona principal de una foto (segmentación en el teléfono, sin internet). */
object ChamosRecorte {
    suspend fun recortarPersona(foto: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val escala = minOf(1f, 640f / maxOf(foto.width, foto.height))
            val base = if (escala < 1f) {
                Bitmap.createScaledBitmap(foto, (foto.width * escala).toInt().coerceAtLeast(1), (foto.height * escala).toInt().coerceAtLeast(1), true)
            } else {
                foto
            }
            val opciones = SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
            val segmentador = Segmentation.getClient(opciones)
            val mascara: SegmentationMask? = try {
                suspendCancellableCoroutine<SegmentationMask?> { k ->
                    segmentador.process(InputImage.fromBitmap(base, 0))
                        .addOnSuccessListener { if (k.isActive) k.resume(it) }
                        .addOnFailureListener { if (k.isActive) k.resume(null) }
                }
            } finally {
                segmentador.close()
            }
            if (mascara == null) return@withContext null

            val mw = mascara.width
            val mh = mascara.height
            val conf = FloatArray(mw * mh)
            val buf = mascara.buffer
            buf.rewind()
            for (i in conf.indices) conf[i] = buf.float

            // Se queda solo con la persona más grande (el grupo de píxeles más grande).
            val f = maxOf(1, maxOf(mw, mh) / 160)
            val gw = (mw + f - 1) / f
            val gh = (mh + f - 1) / f
            val fg = BooleanArray(gw * gh) { i ->
                val gx = i % gw
                val gy = i / gw
                conf[minOf(mh - 1, gy * f + f / 2) * mw + minOf(mw - 1, gx * f + f / 2)] > 0.5f
            }
            val etiqueta = IntArray(gw * gh)
            val cola = IntArray(gw * gh)
            var mejor = 0
            var mejorTam = 0
            var n = 0
            for (i in fg.indices) {
                if (fg[i] && etiqueta[i] == 0) {
                    n++
                    var ini = 0
                    var fin = 0
                    cola[fin++] = i
                    etiqueta[i] = n
                    while (ini < fin) {
                        val u = cola[ini++]
                        val ux = u % gw
                        val uy = u / gw
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val vx = ux + dx
                                val vy = uy + dy
                                if (vx in 0 until gw && vy in 0 until gh) {
                                    val v = vy * gw + vx
                                    if (fg[v] && etiqueta[v] == 0) {
                                        etiqueta[v] = n
                                        cola[fin++] = v
                                    }
                                }
                            }
                        }
                    }
                    if (fin > mejorTam) {
                        mejorTam = fin
                        mejor = n
                    }
                }
            }
            if (mejor == 0) return@withContext null

            val bw = base.width
            val bh = base.height
            val pix = IntArray(bw * bh)
            base.getPixels(pix, 0, bw, 0, 0, bw, bh)
            var x0 = bw
            var y0 = bh
            var x1 = -1
            var y1 = -1
            var cuenta = 0
            for (y in 0 until bh) {
                val my = minOf(mh - 1, y * mh / bh)
                for (x in 0 until bw) {
                    val mx = minOf(mw - 1, x * mw / bw)
                    var a = ((conf[my * mw + mx] - 0.4f) / 0.35f).coerceIn(0f, 1f)
                    if (a > 0f) {
                        val gx = mx / f
                        val gy = my / f
                        var dentro = false
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val vx = gx + dx
                                val vy = gy + dy
                                if (vx in 0 until gw && vy in 0 until gh && etiqueta[vy * gw + vx] == mejor) dentro = true
                            }
                        }
                        if (!dentro) a = 0f
                    }
                    val ai = (a * 255f).toInt()
                    pix[y * bw + x] = (ai shl 24) or (pix[y * bw + x] and 0x00FFFFFF)
                    if (ai > 40) {
                        cuenta++
                        if (x < x0) x0 = x
                        if (y < y0) y0 = y
                        if (x > x1) x1 = x
                        if (y > y1) y1 = y
                    }
                }
            }
            if (cuenta < bw * bh * 0.03f || x1 < x0 || y1 < y0) return@withContext null

            val recortada = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            recortada.setPixels(pix, 0, bw, 0, 0, bw, bh)
            val margen = (((x1 - x0) + (y1 - y0)) / 2 * 0.03f).toInt()
            val cx0 = maxOf(0, x0 - margen)
            val cy0 = maxOf(0, y0 - margen)
            val cx1 = minOf(bw - 1, x1 + margen)
            val cy1 = minOf(bh - 1, y1 + margen)
            val cw = cx1 - cx0 + 1
            val ch = cy1 - cy0 + 1
            val persona = Bitmap.createBitmap(recortada, cx0, cy0, cw, ch)

            // Sticker final: la persona recortada, centrada, con borde blanco.
            val salida = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(salida)
            val ajuste = minOf(456f / cw, 456f / ch)
            val dw = cw * ajuste
            val dh = ch * ajuste
            val izq = (512f - dw) / 2f
            val arr = (512f - dh) / 2f
            val silueta = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(AColor.WHITE, PorterDuff.Mode.SRC_IN)
            }
            val normal = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (k in 0 until 24) {
                val ang = 2.0 * Math.PI * k / 24.0
                val dx = (Math.cos(ang) * 9.0).toFloat()
                val dy = (Math.sin(ang) * 9.0).toFloat()
                canvas.drawBitmap(persona, null, RectF(izq + dx, arr + dy, izq + dw + dx, arr + dh + dy), silueta)
            }
            canvas.drawBitmap(persona, null, RectF(izq, arr, izq + dw, arr + dh), normal)
            salida
        } catch (_: Throwable) {
            null
        }
    }
}

/** Accesorios para pegar en los stickers: unos dibujados y otros emojis. */
internal val ACCESORIOS = listOf(
    "bigote", "gato", "conejo", "gafas",
    "e:🎩", "e:👑", "e:🧢", "e:🎓", "e:👒", "e:🎀", "e:😈", "e:❤️", "e:⭐", "e:🔥", "e:💥", "e:✨", "e:👅", "e:💋",
)

internal fun bitmapAccesorio(tipo: String, lado: Int): Bitmap {
    if (tipo.startsWith("e:")) {
        return ChamosStickersStore.bitmapTexto(tipo.removePrefix("e:"), AColor.WHITE, tipoSticker(0), false, lado)
    }
    val w = lado
    val h = if (tipo == "bigote" || tipo == "gafas") lado / 2 else lado * 3 / 5
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val wf = w.toFloat()
    val hf = h.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    when (tipo) {
        "bigote" -> {
            p.color = 0xFF2B1A10.toInt()
            val r = Path()
            r.moveTo(0.5f * wf, 0.35f * hf)
            r.cubicTo(0.65f * wf, 0.02f * hf, 0.92f * wf, 0.08f * hf, 1.0f * wf, 0.62f * hf)
            r.cubicTo(0.82f * wf, 0.40f * hf, 0.64f * wf, 0.72f * hf, 0.5f * wf, 0.62f * hf)
            r.cubicTo(0.36f * wf, 0.72f * hf, 0.18f * wf, 0.40f * hf, 0.0f * wf, 0.62f * hf)
            r.cubicTo(0.08f * wf, 0.08f * hf, 0.35f * wf, 0.02f * hf, 0.5f * wf, 0.35f * hf)
            r.close()
            c.drawPath(r, p)
        }
        "gato" -> {
            p.color = 0xFF333333.toInt()
            val izq = Path().apply {
                moveTo(0.06f * wf, 0.95f * hf); lineTo(0.14f * wf, 0.04f * hf); lineTo(0.42f * wf, 0.95f * hf); close()
            }
            val der = Path().apply {
                moveTo(0.58f * wf, 0.95f * hf); lineTo(0.86f * wf, 0.04f * hf); lineTo(0.94f * wf, 0.95f * hf); close()
            }
            c.drawPath(izq, p)
            c.drawPath(der, p)
            p.color = 0xFFFF8FB1.toInt()
            val izq2 = Path().apply {
                moveTo(0.13f * wf, 0.88f * hf); lineTo(0.16f * wf, 0.30f * hf); lineTo(0.33f * wf, 0.88f * hf); close()
            }
            val der2 = Path().apply {
                moveTo(0.67f * wf, 0.88f * hf); lineTo(0.84f * wf, 0.30f * hf); lineTo(0.87f * wf, 0.88f * hf); close()
            }
            c.drawPath(izq2, p)
            c.drawPath(der2, p)
        }
        "conejo" -> {
            p.color = 0xFFF4F4F4.toInt()
            c.drawOval(RectF(0.14f * wf, 0.0f, 0.40f * wf, hf), p)
            c.drawOval(RectF(0.60f * wf, 0.0f, 0.86f * wf, hf), p)
            p.color = 0xFFFFA6C4.toInt()
            c.drawOval(RectF(0.20f * wf, 0.10f * hf, 0.34f * wf, 0.88f * hf), p)
            c.drawOval(RectF(0.66f * wf, 0.10f * hf, 0.80f * wf, 0.88f * hf), p)
        }
        "gafas" -> {
            p.color = 0xEE111111.toInt()
            c.drawRoundRect(RectF(0.03f * wf, 0.12f * hf, 0.47f * wf, 0.82f * hf), 0.12f * wf, 0.12f * wf, p)
            c.drawRoundRect(RectF(0.53f * wf, 0.12f * hf, 0.97f * wf, 0.82f * hf), 0.12f * wf, 0.12f * wf, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.05f * hf
            p.color = 0xFF111111.toInt()
            c.drawLine(0.47f * wf, 0.28f * hf, 0.53f * wf, 0.28f * hf, p)
            p.color = 0x66FFFFFF
            p.strokeWidth = 0.04f * hf
            c.drawLine(0.10f * wf, 0.25f * hf, 0.20f * wf, 0.20f * hf, p)
            c.drawLine(0.60f * wf, 0.25f * hf, 0.70f * wf, 0.20f * hf, p)
        }
    }
    return bmp
}

private class ElemEditor(val bmp: Bitmap, cx0: Float, cy0: Float, escala0: Float) {
    var cx by mutableStateOf(cx0)
    var cy by mutableStateOf(cy0)
    var escala by mutableStateOf(escala0)
    var giro by mutableStateOf(0f)
    val img: ImageBitmap = bmp.asImageBitmap()
}

private fun componerSticker(base: Bitmap, elementos: List<ElemEditor>): Bitmap {
    val salida = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val c = Canvas(salida)
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    c.drawBitmap(base, null, RectF(0f, 0f, 512f, 512f), p)
    elementos.forEach { e ->
        val w = e.escala * 512f
        val h = w * e.bmp.height / e.bmp.width
        c.save()
        c.translate(e.cx * 512f, e.cy * 512f)
        c.rotate(e.giro)
        c.drawBitmap(e.bmp, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), p)
        c.restore()
    }
    return salida
}

/** Editor: la foto recortada + frases y accesorios que se arrastran, se agrandan y se giran con los dedos. */
@Composable
fun ChamosStickerEditor(recorte: Bitmap?, cuadro: Bitmap, onGuardar: (Bitmap) -> Unit, onCancelar: () -> Unit) {
    var usarRecorte by remember { mutableStateOf(recorte != null) }
    val base = if (usarRecorte && recorte != null) recorte else cuadro
    val baseImg = remember(base) { base.asImageBitmap() }
    val elementos = remember { mutableStateListOf<ElemEditor>() }
    var elegido by remember { mutableStateOf<ElemEditor?>(null) }
    var fraseAbierta by remember { mutableStateOf(false) }
    val miniaturas = remember { ACCESORIOS.map { it to bitmapAccesorio(it, 160).asImageBitmap() } }
    val lado = 300.dp
    val ladoPx = with(LocalDensity.current) { lado.toPx() }

    Dialog(onDismissRequest = onCancelar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111111))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Editar sticker", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Toca un accesorio, arrástralo y pellizca para cambiarle el tamaño y el giro.",
                color = Color(0xFFBBBBBB),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Box(
                modifier = Modifier
                    .size(lado)
                    .background(Color(0xFF2A2A2A))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, giro ->
                            elegido?.let { e ->
                                e.cx = (e.cx + pan.x / ladoPx).coerceIn(0f, 1f)
                                e.cy = (e.cy + pan.y / ladoPx).coerceIn(0f, 1f)
                                e.escala = (e.escala * zoom).coerceIn(0.08f, 1.3f)
                                e.giro += giro
                            }
                        }
                    },
            ) {
                androidx.compose.foundation.Image(bitmap = baseImg, contentDescription = "Sticker", modifier = Modifier.fillMaxSize())
                elementos.forEach { e ->
                    val w = lado * e.escala
                    val alto = w * e.bmp.height / e.bmp.width
                    Image(
                        bitmap = e.img,
                        contentDescription = null,
                        modifier = Modifier
                            .size(w, alto)
                            .offset { IntOffset((e.cx * ladoPx - w.toPx() / 2f).roundToInt(), (e.cy * ladoPx - alto.toPx() / 2f).roundToInt()) }
                            .graphicsLayer { rotationZ = e.giro }
                            .then(if (e === elegido) Modifier.border(1.dp, Color.Cyan) else Modifier)
                            .clickable { elegido = e },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                miniaturas.forEach { (tipo, img) ->
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .clickable {
                                val e = ElemEditor(bitmapAccesorio(tipo, 256), 0.5f, 0.35f, if (tipo.startsWith("e:")) 0.3f else 0.45f)
                                elementos.add(e)
                                elegido = e
                            }
                            .padding(4.dp),
                    ) {
                        Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { fraseAbierta = true }) { Text("+ Frase") }
                TextButton(
                    enabled = elegido != null,
                    onClick = {
                        elegido?.let { elementos.remove(it) }
                        elegido = null
                    },
                ) { Text("Quitar elemento") }
                if (recorte != null) {
                    TextButton(onClick = { usarRecorte = !usarRecorte }) {
                        Text(if (usarRecorte) "Recorte: sí" else "Recorte: no")
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onCancelar) { Text("Cancelar") }
                TextButton(onClick = { onGuardar(componerSticker(base, elementos.toList())) }) { Text("Guardar sticker", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (fraseAbierta) {
        ChamosTextoDialogo(
            titulo = "Agregar frase",
            onListo = { bmp ->
                val e = ElemEditor(bmp, 0.5f, 0.8f, 0.8f)
                elementos.add(e)
                elegido = e
                fraseAbierta = false
            },
            onCancelar = { fraseAbierta = false },
        )
    }
}

/** Cuadro para escribir un texto (o emoji) con letra y color; devuelve la imagen con fondo transparente. */
@Composable
fun ChamosTextoDialogo(titulo: String, onListo: (Bitmap) -> Unit, onCancelar: () -> Unit) {
    var texto by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf(0) }
    var color by remember { mutableStateOf(COLORES_STICKER[1]) }
    val vista = remember(texto, tipo, color) {
        ChamosStickersStore.bitmapTexto(texto.ifBlank { "Tu texto" }, color, tipoSticker(tipo), true, 256).asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            Column {
                TextField(value = texto, onValueChange = { if (it.length <= 40) texto = it }, placeholder = { Text("Escribe algo o un emoji") })
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TIPOS_STICKER.forEachIndexed { i, n ->
                        TextButton(onClick = { tipo = i }) {
                            Text(n, fontWeight = if (tipo == i) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    COLORES_STICKER.forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(if (color == col) 3.dp else 1.dp, ElementTheme.colors.textPrimary, CircleShape)
                                .clickable { color = col },
                        )
                    }
                }
                Image(bitmap = vista, contentDescription = "Vista previa", modifier = Modifier.size(140.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = texto.isNotBlank(),
                onClick = { onListo(ChamosStickersStore.bitmapTexto(texto, color, tipoSticker(tipo), true)) },
            ) { Text("Listo") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}
