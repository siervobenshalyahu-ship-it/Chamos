package io.element.android.features.home.impl

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Recorte de videos antes de subirlos (Estados: hasta 60 s, Videos: hasta 90 s). */
object ChamosRecorteVideo {
    fun duracion(c: Context, uri: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }.getOrDefault(0L)

    /** Un cuadro del video en el instante [ms], reducido a [ancho] píxeles. */
    fun cuadroEn(c: Context, uri: Uri, ms: Long, ancho: Int, exacto: Boolean): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val opcion = if (exacto) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val b = r.getFrameAtTime(ms * 1000L, opcion)
            if (b == null) {
                null
            } else if (b.width <= ancho) {
                b
            } else {
                val reducido = Bitmap.createScaledBitmap(b, ancho, (b.height.toLong() * ancho / b.width).toInt().coerceAtLeast(1), true)
                if (reducido !== b) b.recycle()
                reducido
            }
        } finally {
            r.release()
        }
    }.getOrNull()

    /** Corta el tramo [iniMs, finMs]. Devuelve el archivo nuevo o null si falla. */
    @OptIn(UnstableApi::class)
    suspend fun recortar(c: Context, uri: Uri, iniMs: Long, finMs: Long): Uri? {
        val tmp = File(c.cacheDir, "chamos_recorte_${System.currentTimeMillis()}.mp4")
        val ok = try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val corte = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(iniMs)
                        .setEndPositionMs(finMs)
                        .build()
                    val medio = MediaItem.Builder().setUri(uri).setClippingConfiguration(corte).build()
                    val item = EditedMediaItem.Builder(medio).build()
                    val t = Transformer.Builder(c)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                android.util.Log.e("Chamos", "recortar video", exportException)
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    cont.invokeOnCancellation { runCatching { t.cancel() } }
                    t.start(item, tmp.path)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Chamos", "recortar video", e)
            false
        }
        return if (ok && tmp.length() > 0L) {
            Uri.fromFile(tmp)
        } else {
            tmp.delete()
            null
        }
    }
}

private fun tiempoRecorte(ms: Long): String {
    val s = (ms / 1000L).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Pantalla para recortar un video antes de subirlo. Se elige el tramo con dos manijas (máximo [maxMs]);
 * arriba se ve el cuadro donde está la manija que mueves y debajo una tira con el video completo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecortarVideoDialogo(
    uri: Uri,
    maxMs: Long,
    onListo: (Uri) -> Unit,
    onCancelar: () -> Unit,
) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var dur by remember(uri) { mutableStateOf(0L) }
    var leido by remember(uri) { mutableStateOf(false) }
    var rango by remember(uri) { mutableStateOf(0f..1f) }
    var pos by remember(uri) { mutableStateOf(0L) }
    var cuadro by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var tira by remember(uri) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var trabajando by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val d = withContext(Dispatchers.IO) { ChamosRecorteVideo.duracion(c, uri) }
        dur = d
        rango = 0f..minOf(d, maxMs).toFloat().coerceAtLeast(1f)
        leido = true
        if (d > 0L) {
            val n = 8
            for (i in 0 until n) {
                val b = withContext(Dispatchers.IO) {
                    ChamosRecorteVideo.cuadroEn(c, uri, d * i / n, 160, false)
                }
                if (b != null) tira = tira + b.asImageBitmap()
            }
        }
    }
    LaunchedEffect(pos, dur) {
        if (dur > 0L) {
            delay(90)
            val b = withContext(Dispatchers.IO) { ChamosRecorteVideo.cuadroEn(c, uri, pos, 720, true) }
            if (b != null) cuadro = b.asImageBitmap()
        }
    }

    Dialog(
        onDismissRequest = { if (!trabajando) onCancelar() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = !trabajando),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text("Recortar video", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (dur > maxMs) "Dura ${tiempoRecorte(dur)}. Elige un tramo de hasta ${maxMs / 1000} segundos."
                    else "Mueve las manijas para quedarte con la parte que quieras.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val img = cuadro
                    if (img != null) {
                        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                if (leido && dur > 0L) {
                    val ini = rango.start.toLong()
                    val fin = rango.endInclusive.toLong()
                    Text(
                        "Inicio ${tiempoRecorte(ini)}   ·   Fin ${tiempoRecorte(fin)}   ·   Dura ${(fin - ini + 500L) / 1000L} s (máx. ${maxMs / 1000} s)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF222222))) {
                        for (t in tira) {
                            Image(bitmap = t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                        }
                    }
                    RangeSlider(
                        value = rango,
                        onValueChange = { nuevo ->
                            var a = nuevo.start
                            var b = nuevo.endInclusive
                            val movioIni = a != rango.start
                            val max = maxMs.toFloat()
                            if (b - a > max) {
                                if (movioIni) b = a + max else a = b - max
                            }
                            val minimo = minOf(1000f, dur.toFloat())
                            if (b - a < minimo) {
                                if (movioIni) a = (b - minimo).coerceAtLeast(0f) else b = (a + minimo).coerceAtMost(dur.toFloat())
                            }
                            rango = a..b
                            pos = if (movioIni) a.toLong() else (b - 200f).coerceAtLeast(0f).toLong()
                        },
                        valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD400),
                            activeTrackColor = Color(0xFFFFD400),
                            inactiveTrackColor = Color(0xFF555555),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (leido) {
                    Text(
                        "No se pudo leer la duración del video. Puedes subirlo tal cual.",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(enabled = !trabajando, onClick = onCancelar) { Text("Cancelar", color = Color.White) }
                    Button(
                        enabled = leido && !trabajando,
                        onClick = {
                            val a = rango.start.toLong()
                            val b = rango.endInclusive.toLong()
                            if (dur <= 0L || (a < 400L && b > dur - 400L)) {
                                onListo(uri)
                            } else {
                                trabajando = true
                                scope.launch {
                                    val nuevo = ChamosRecorteVideo.recortar(c, uri, a, b)
                                    trabajando = false
                                    if (nuevo != null) onListo(nuevo)
                                    else Toast.makeText(c, "No se pudo recortar el video", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(if (trabajando) "Recortando…" else "Usar este tramo") }
                }
            }
        }
    }
}
