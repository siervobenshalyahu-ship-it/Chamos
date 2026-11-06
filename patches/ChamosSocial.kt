package io.element.android.features.home.impl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Seguir / dejar de seguir a una persona. Se guarda en la base de datos de Chamos (Cloudflare D1). */
object ChamosSeguir {
    private const val URL_SEGUIR = "https://chamos-push.siervodeyhwh57.workers.dev/seguir"

    data class Estado(val n: Int, val sigo: Boolean)

    private suspend fun llamar(autorId: String, seguir: Boolean?): Estado? = withContext(Dispatchers.IO) {
        try {
            val token = ChamosNumero.tokenSesion() ?: return@withContext null
            val con = if (seguir == null) {
                URL(URL_SEGUIR + "?u=" + java.net.URLEncoder.encode(autorId, "UTF-8")).openConnection() as HttpURLConnection
            } else {
                (URL(URL_SEGUIR).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                }
            }
            con.connectTimeout = 10000
            con.readTimeout = 10000
            con.setRequestProperty("Authorization", "Bearer $token")
            if (seguir != null) {
                con.setRequestProperty("content-type", "application/json")
                con.outputStream.use { it.write(JSONObject().put("u", autorId).put("s", seguir).toString().toByteArray()) }
            }
            if (con.responseCode !in 200..299) return@withContext null
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            con.disconnect()
            Estado(j.optInt("n", 0), j.optBoolean("sigo", false))
        } catch (e: Exception) {
            android.util.Log.w("Chamos", "seguir", e)
            null
        }
    }

    suspend fun consultar(autorId: String): Estado? = llamar(autorId, null)
    suspend fun cambiar(autorId: String, seguir: Boolean): Estado? = llamar(autorId, seguir)
}

/** Botón "+ Seguir" / "✓ Siguiendo". */
@Composable
fun BotonSeguir(autorId: String, modifier: Modifier = Modifier) {
    var estado by remember(autorId) { mutableStateOf<ChamosSeguir.Estado?>(null) }
    var ocupado by remember(autorId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(autorId) { estado = ChamosSeguir.consultar(autorId) }
    val e = estado ?: return
    Text(
        text = if (e.sigo) "✓ Siguiendo · ${e.n}" else "+ Seguir · ${e.n}",
        color = if (e.sigo) Color.White else Color(0xFF1A1205),
        fontSize = 12.sp,
        modifier = modifier
            .background(if (e.sigo) Color(0x44FFFFFF) else Color(0xFFF4B54A), RoundedCornerShape(14.dp))
            .clickable(enabled = !ocupado) {
                ocupado = true
                scope.launch {
                    val nuevo = ChamosSeguir.cambiar(autorId, !e.sigo)
                    if (nuevo != null) estado = nuevo
                    ocupado = false
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Guardar fotos y videos en el teléfono con la marca de agua de Chamos y el nombre de usuario. */
object ChamosDescarga {
    private fun nombreMarca(usuario: String): String {
        val u = usuario.trim().removePrefix("@").substringBefore(":")
        return if (u.isBlank()) "" else "@" + u.take(30)
    }

    /** Imagen transparente del tamaño del cuadro con "CHAMOS" y el usuario abajo a la derecha. */
    private fun marca(ancho: Int, alto: Int, usuario: String): Bitmap {
        val b = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        pintarMarca(Canvas(b), ancho, alto, usuario)
        return b
    }

    private fun pintarMarca(c: Canvas, ancho: Int, alto: Int, usuario: String) {
        val lado = minOf(ancho, alto).toFloat()
        val tam = lado * 0.05f
        val margen = lado * 0.04f
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE; textSize = tam; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT; alpha = 235; setShadowLayer(tam * 0.2f, 0f, 0f, AColor.BLACK)
        }
        val p2 = Paint(p1).apply { textSize = tam * 0.8f; typeface = Typeface.DEFAULT; alpha = 220 }
        val x = ancho - margen
        val nombre = nombreMarca(usuario)
        var y = alto - margen
        if (nombre.isNotEmpty()) { c.drawText(nombre, x, y, p2); y -= tam * 1.0f }
        c.drawText("CHAMOS", x, y, p1)
    }

    private fun guardarEnGaleria(c: Context, archivo: File, nombre: String, mime: String, video: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 29) {
                val dir = File(c.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "Chamos")
                dir.mkdirs()
                archivo.copyTo(File(dir, nombre), overwrite = true)
                true
            } else {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Chamos")
                }
                val coleccion = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = c.contentResolver.insert(coleccion, cv) ?: return false
                val salida = c.contentResolver.openOutputStream(uri) ?: return false
                salida.use { o -> archivo.inputStream().use { it.copyTo(o) } }
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("Chamos", "guardar en galería", e)
            false
        }
    }

    suspend fun guardarFoto(c: Context, ruta: String, usuario: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val origen = BitmapFactory.decodeFile(ruta) ?: return@withContext false
            val copia = origen.copy(Bitmap.Config.ARGB_8888, true)
            pintarMarca(Canvas(copia), copia.width, copia.height, usuario)
            val tmp = File(c.cacheDir, "chamos_marca_${System.currentTimeMillis()}.jpg")
            tmp.outputStream().use { copia.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val ok = guardarEnGaleria(c, tmp, "Chamos_${System.currentTimeMillis()}.jpg", "image/jpeg", false)
            tmp.delete()
            ok
        } catch (e: Exception) {
            android.util.Log.e("Chamos", "guardar foto", e)
            false
        }
    }

    private fun medidas(ruta: String): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ruta)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
        } catch (e: Exception) {
            Pair(720, 1280)
        } finally {
            r.release()
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun guardarVideo(c: Context, ruta: String, usuario: String): Boolean {
        val tmp = File(c.cacheDir, "chamos_marca_${System.currentTimeMillis()}.mp4")
        val (w, h) = withContext(Dispatchers.IO) { medidas(ruta) }
        val ok = try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val capa = BitmapOverlay.createStaticBitmapOverlay(marca(w, h, usuario))
                    val efectos = Effects(emptyList(), listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(capa))))
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(ruta)))).setEffects(efectos).build()
                    val t = Transformer.Builder(c)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                android.util.Log.e("Chamos", "marca de agua", exportException)
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    cont.invokeOnCancellation { runCatching { t.cancel() } }
                    t.start(item, tmp.path)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Chamos", "guardar video", e)
            false
        }
        if (!ok) { tmp.delete(); return false }
        val guardado = withContext(Dispatchers.IO) {
            guardarEnGaleria(c, tmp, "Chamos_${System.currentTimeMillis()}.mp4", "video/mp4", true)
        }
        tmp.delete()
        return guardado
    }
}

/** Estados: quién los vio y con qué emoji reaccionó cada persona (base de datos de Chamos). */
object ChamosEstadoSocial {
    private const val BASE = "https://chamos-push.siervodeyhwh57.workers.dev"

    @Volatile
    var miNombre: String = ""

    data class Persona(val usuario: String, val nombre: String)
    data class Reaccion(val usuario: String, val nombre: String, val emoji: String)
    data class Info(val vistos: List<Persona>, val reacciones: List<Reaccion>, val mia: String)

    private suspend fun peticion(metodo: String, ruta: String, cuerpo: JSONObject?): String? = withContext(Dispatchers.IO) {
        try {
            val token = ChamosNumero.tokenSesion() ?: return@withContext null
            val con = URL(BASE + ruta).openConnection() as HttpURLConnection
            con.requestMethod = metodo
            con.connectTimeout = 10000
            con.readTimeout = 10000
            con.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                con.doOutput = true
                con.setRequestProperty("content-type", "application/json")
                con.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            }
            val ok = con.responseCode in 200..299
            val txt = if (ok) con.inputStream.bufferedReader().readText() else null
            con.disconnect()
            txt
        } catch (e: Exception) {
            android.util.Log.w("Chamos", "estado social", e)
            null
        }
    }

    suspend fun visto(evento: String, dueno: String): Boolean =
        peticion("POST", "/estado/visto", JSONObject().put("e", evento).put("d", dueno).put("n", miNombre)) != null

    /** emoji vacío = quitar mi reacción. */
    suspend fun reaccionar(evento: String, dueno: String, emoji: String): Boolean =
        peticion("POST", "/estado/reaccion", JSONObject().put("e", evento).put("d", dueno).put("n", miNombre).put("x", emoji)) != null

    suspend fun info(evento: String): Info? {
        val txt = peticion("GET", "/estado/info?e=" + java.net.URLEncoder.encode(evento, "UTF-8"), null) ?: return null
        return try {
            val j = JSONObject(txt).optJSONObject(evento) ?: return Info(emptyList(), emptyList(), "")
            val v = j.optJSONArray("v")
            val r = j.optJSONArray("r")
            Info(
                vistos = (0 until (v?.length() ?: 0)).map { val o = v!!.getJSONObject(it); Persona(o.optString("usuario"), o.optString("nombre")) },
                reacciones = (0 until (r?.length() ?: 0)).map { val o = r!!.getJSONObject(it); Reaccion(o.optString("usuario"), o.optString("nombre"), o.optString("emoji")) },
                mia = j.optString("mia", ""),
            )
        } catch (e: Exception) {
            null
        }
    }
}
