package io.element.android.features.messages.impl.messagecomposer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface as ATypeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.element.android.compound.theme.ElementTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Letras para los stickers de texto. */
private val TIPOS_STICKER = listOf("Gruesa", "Casual", "Manuscrita", "Máquina", "Elegante")

private fun tipoSticker(i: Int): ATypeface = when (i) {
    1 -> ATypeface.create("casual", ATypeface.BOLD)
    2 -> ATypeface.create("cursive", ATypeface.BOLD)
    3 -> ATypeface.create("monospace", ATypeface.BOLD)
    4 -> ATypeface.create("serif", ATypeface.BOLD)
    else -> ATypeface.create("sans-serif-black", ATypeface.NORMAL)
}

private val COLORES_STICKER = listOf(
    0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFFFF2D95.toInt(), 0xFF00E5FF.toInt(),
    0xFF7CFF4D.toInt(), 0xFFFF7A00.toInt(), 0xFFE63946.toInt(), 0xFF111111.toInt(),
)

/** Stickers de Chamos: imágenes PNG guardadas en la carpeta de la app. Se pueden crear, enviar y borrar. */
object ChamosStickersStore {
    val lista = mutableStateListOf<String>()
    private var cargado = false

    private val BASICOS = listOf(
        "😂" to 0, "❤️" to 0, "🔥" to 0, "👍" to 0,
        "😎" to 0, "😭" to 0, "🇻🇪" to 0, "🤝" to 0,
        "¡Épale!" to 1, "¡Chévere!" to 2, "¡Qué molleja!" to 3, "Vale pues" to 4,
        "Ajá" to 5, "Chamo" to 6, "¡Fino!" to 7,
    )

    private fun carpeta(c: Context) = File(c.filesDir, "chamos_stickers").apply { mkdirs() }

    private fun refrescar(c: Context) {
        val nombres = carpeta(c).listFiles { f -> f.name.endsWith(".png") }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.map { it.name } ?: emptyList()
        lista.clear()
        lista.addAll(nombres)
    }

    fun cargar(c: Context) {
        if (cargado) return
        cargado = true
        val prefs = c.getSharedPreferences("chamos_stickers", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("basicos", false)) {
            crearBasicos(c)
            prefs.edit().putBoolean("basicos", true).apply()
        }
        refrescar(c)
    }

    fun crearBasicos(c: Context) {
        val colores = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF00E5FF.toInt(), 0xFFFF2D95.toInt(), 0xFF7CFF4D.toInt(), 0xFFFF7A00.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt())
        BASICOS.reversed().forEach { (texto, k) ->
            val esEmoji = k == 0
            val bmp = bitmapTexto(texto, colores[k], tipoSticker(if (esEmoji) 0 else k - 1), !esEmoji)
            guardarArchivo(c, bmp)
            Thread.sleep(2)
        }
        refrescar(c)
    }

    private fun guardarArchivo(c: Context, bmp: Bitmap): String {
        val nombre = "s_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt() + ".png"
        File(carpeta(c), nombre).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return nombre
    }

    fun guardar(c: Context, bmp: Bitmap) {
        guardarArchivo(c, bmp)
        refrescar(c)
    }

    fun borrar(c: Context, nombre: String) {
        runCatching { File(carpeta(c), nombre).delete() }
        lista.remove(nombre)
    }

    fun cargarBitmap(c: Context, nombre: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(File(carpeta(c), nombre).path) }.getOrNull()

    /** Uri que Chamos puede enviar al chat (copia temporal en la caché + FileProvider). */
    fun uriDe(c: Context, nombre: String): Uri {
        val dir = File(c.cacheDir, "chamos_stickers_envio").apply { mkdirs() }
        val f = File(dir, nombre)
        File(carpeta(c), nombre).copyTo(f, overwrite = true)
        return FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
    }

    /** Dibuja un texto (o emoji) grande y centrado sobre fondo transparente. */
    fun bitmapTexto(texto: String, color: Int, tipo: ATypeface, contorno: Boolean): Bitmap {
        val lado = 512
        val ancho = 460
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        fun pintura(tam: Float, trazo: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = tam
            typeface = tipo
            if (trazo) {
                style = Paint.Style.STROKE
                strokeWidth = tam / 6f
                strokeJoin = Paint.Join.ROUND
                this.color = if ((0.299f * AColor.red(color) + 0.587f * AColor.green(color) + 0.114f * AColor.blue(color)) > 150f) AColor.BLACK else AColor.WHITE
            } else {
                this.color = color
            }
        }
        fun disposicion(p: TextPaint) = StaticLayout.Builder
            .obtain(texto, 0, texto.length, p, ancho)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        var tam = 300f
        var capa = disposicion(pintura(tam, false))
        while (capa.height > ancho && tam > 24f) {
            tam -= 8f
            capa = disposicion(pintura(tam, false))
        }
        canvas.save()
        canvas.translate((lado - ancho) / 2f, (lado - capa.height) / 2f)
        if (contorno) disposicion(pintura(tam, true)).draw(canvas)
        capa.draw(canvas)
        canvas.restore()
        return bmp
    }

    /** Convierte una foto en sticker: cuadrada, con esquinas redondeadas y borde blanco. */
    fun bitmapFoto(c: Context, uri: Uri): Bitmap? {
        return try {
            val cr = c.contentResolver
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
            var m = 1
            while (maxOf(medidas.outWidth, medidas.outHeight) / m > 1200) m *= 2
            val original = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = m })
            } ?: return null
            val giro = try {
                cr.openInputStream(uri)?.use { s ->
                    when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Throwable) {
                0f
            }
            val recto = if (giro != 0f) {
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(giro) }, true)
            } else {
                original
            }
            val lado = minOf(recto.width, recto.height)
            val cuadrada = Bitmap.createBitmap(recto, (recto.width - lado) / 2, (recto.height - lado) / 2, lado, lado)
            val salida = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(salida)
            val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }
            canvas.drawRoundRect(RectF(0f, 0f, 512f, 512f), 80f, 80f, borde)
            val recorte = Path().apply { addRoundRect(RectF(12f, 12f, 500f, 500f), 68f, 68f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(recorte)
            canvas.drawBitmap(cuadrada, null, RectF(12f, 12f, 500f, 500f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            salida
        } catch (_: Throwable) {
            null
        }
    }
}

/** Botón pequeño sobre el cuadro de escribir que abre el panel de stickers. */
@Composable
fun ChamosStickersBoton(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ElementTheme.colors.bgSubtleSecondary)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("😀 Stickers", fontSize = 13.sp, color = ElementTheme.colors.textPrimary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChamosStickersPanel(onEnviar: (Uri) -> Unit, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var creandoTexto by remember { mutableStateOf(false) }
    var porBorrar by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ChamosStickersStore.cargar(contexto) } }

    val elegirFoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ChamosStickersStore.bitmapFoto(contexto, uri) }
                if (bmp != null) {
                    withContext(Dispatchers.IO) { ChamosStickersStore.guardar(contexto, bmp) }
                    Toast.makeText(contexto, "Sticker creado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(contexto, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Stickers", fontWeight = FontWeight.Bold, color = ElementTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = { elegirFoto.launch("image/*") }) { Text("+ Foto") }
            TextButton(onClick = { creandoTexto = true }) { Text("+ Texto") }
            TextButton(onClick = onCerrar) { Text("Cerrar") }
        }
        if (ChamosStickersStore.lista.isEmpty()) {
            Text("No tienes stickers. Crea uno con + Foto o + Texto.", color = ElementTheme.colors.textSecondary, fontSize = 13.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp),
        ) {
            items(ChamosStickersStore.lista.toList(), key = { it }) { nombre ->
                val imagen by produceState<ImageBitmap?>(null, nombre) {
                    value = withContext(Dispatchers.IO) { ChamosStickersStore.cargarBitmap(contexto, nombre)?.asImageBitmap() }
                }
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .padding(3.dp)
                        .combinedClickable(
                            onClick = {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { ChamosStickersStore.uriDe(contexto, nombre) }
                                    onEnviar(uri)
                                }
                            },
                            onLongClick = { porBorrar = nombre },
                        ),
                ) {
                    imagen?.let { Image(bitmap = it, contentDescription = "Sticker", modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Toca para enviar. Mantén presionado para borrar.",
                color = ElementTheme.colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { ChamosStickersStore.crearBasicos(contexto) } }
            }) { Text("Restaurar básicos", fontSize = 12.sp) }
        }
    }

    val borrando = porBorrar
    if (borrando != null) {
        AlertDialog(
            onDismissRequest = { porBorrar = null },
            title = { Text("¿Borrar este sticker?") },
            text = { Text("Se borra solo de tu lista. Los que ya enviaste se quedan en los chats.") },
            confirmButton = {
                TextButton(onClick = {
                    ChamosStickersStore.borrar(contexto, borrando)
                    porBorrar = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { porBorrar = null }) { Text("Cancelar") } },
        )
    }

    if (creandoTexto) {
        var texto by remember { mutableStateOf("") }
        var tipo by remember { mutableStateOf(0) }
        var color by remember { mutableStateOf(COLORES_STICKER[1]) }
        val vista = remember(texto, tipo, color) {
            ChamosStickersStore.bitmapTexto(texto.ifBlank { "Tu texto" }, color, tipoSticker(tipo), true).asImageBitmap()
        }
        AlertDialog(
            onDismissRequest = { creandoTexto = false },
            title = { Text("Sticker de texto") },
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
                    onClick = {
                        val bmp = ChamosStickersStore.bitmapTexto(texto, color, tipoSticker(tipo), true)
                        scope.launch { withContext(Dispatchers.IO) { ChamosStickersStore.guardar(contexto, bmp) } }
                        creandoTexto = false
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { creandoTexto = false }) { Text("Cancelar") } },
        )
    }
}
