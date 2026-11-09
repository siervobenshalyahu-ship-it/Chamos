package io.element.android.features.messages.impl.messagecomposer

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.esperar(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/**
 * Traductor de Chamos por sala: traduce en el propio teléfono (ML Kit, sin enviar tus mensajes a ningún servidor).
 * Lo que llega en otro idioma se muestra traducido a tu idioma, y lo que escribes sale en el idioma elegido para esa sala.
 */
internal object ChamosTraductor {
    data class Config(val activa: Boolean, val mio: String, val otro: String)

    val IDIOMAS = listOf(
        "en" to "Inglés", "es" to "Español", "pt" to "Portugués", "fr" to "Francés", "de" to "Alemán", "it" to "Italiano",
        "zh" to "Chino", "ja" to "Japonés", "ko" to "Coreano", "ru" to "Ruso", "ar" to "Árabe", "hi" to "Hindi",
        "tr" to "Turco", "nl" to "Neerlandés",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val estado = MutableStateFlow<Map<String, Config>>(emptyMap())
    val flow: StateFlow<Map<String, Config>> get() = estado
    private var prefs: SharedPreferences? = null
    private val traductores = HashMap<String, Translator>()
    private val cache = HashMap<String, String>()
    private val identificador by lazy { LanguageIdentification.getClient() }

    private val porDefecto: String
        get() = Locale.getDefault().language.takeIf { TranslateLanguage.fromLanguageTag(it) != null } ?: "es"

    fun iniciar(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("chamos_traductor", Context.MODE_PRIVATE)
        prefs = p
        val m = HashMap<String, Config>()
        for ((k, v) in p.all) {
            val t = (v as? String)?.split("|") ?: continue
            if (t.size == 3) m[k] = Config(t[0] == "1", t[1], t[2])
            else if (t.size == 2) m[k] = Config(t[0] == "1", porDefecto, t[1])
        }
        estado.value = m
    }

    fun config(sala: String): Config = estado.value[sala] ?: Config(false, porDefecto, if (porDefecto == "en") "es" else "en")

    fun poner(ctx: Context, sala: String, activa: Boolean, mio: String, otro: String) {
        if (mio == otro) {
            Toast.makeText(ctx.applicationContext, "Elige dos idiomas distintos", Toast.LENGTH_SHORT).show()
            return
        }
        estado.value = estado.value + (sala to Config(activa, mio, otro))
        prefs?.edit()?.putString(sala, (if (activa) "1" else "0") + "|" + mio + "|" + otro)?.apply()
        if (activa) {
            // baja los dos idiomas ahora (una sola vez), para que luego traduzca al instante
            scope.launch {
                Toast.makeText(ctx.applicationContext, "Traductor listo; la primera vez baja el idioma (necesita internet)", Toast.LENGTH_LONG).show()
                traducirCon("hola", mio, otro)
                traducirCon("hello", otro, mio)
            }
        }
    }

    private suspend fun traducirCon(texto: String, de: String, a: String): String? {
        if (de == a) return texto
        val s = TranslateLanguage.fromLanguageTag(de) ?: return null
        val t = TranslateLanguage.fromLanguageTag(a) ?: return null
        val tr = synchronized(traductores) {
            traductores.getOrPut("$s>$t") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
            }
        }
        return runCatching {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).esperar()
            tr.translate(texto).esperar()
        }.getOrNull()
    }

    private suspend fun detectar(texto: String): String? =
        runCatching { identificador.identifyLanguage(texto).esperar() }.getOrNull()?.takeIf { it != "und" }

    /** Lo que llega: si está en otro idioma, lo devuelve traducido a tu idioma; si no hace falta, null. */
    suspend fun traducirEntrante(sala: String, texto: String): String? {
        val c = config(sala)
        if (!c.activa) return null
        val t = texto.trim()
        if (t.length < 3 || t.startsWith("http")) return null
        cache["$sala|${c.mio}|$t"]?.let { return it }
        val origen = detectar(t) ?: return null
        if (origen == c.mio) return null
        val r = traducirCon(t, origen, c.mio)?.takeIf { it.isNotBlank() && it != t } ?: return null
        if (cache.size > 500) cache.clear()
        cache["$sala|${c.mio}|$t"] = r
        return r
    }

    /** Lo que escribes: sale en el idioma elegido para la sala. Si falla o ya está en ese idioma, null (se envía tal cual). */
    suspend fun traducirSaliente(sala: String, texto: String): String? {
        val c = config(sala)
        if (!c.activa || texto.isBlank()) return null
        if (detectar(texto) == c.otro) return null
        return traducirCon(texto, c.mio, c.otro)?.takeIf { it.isNotBlank() }
    }
}

/** Debajo del mensaje que llegó, su traducción. */
@Composable
internal fun ChamosTraduccion(texto: String) {
    val ctx = LocalContext.current
    ChamosTraductor.iniciar(ctx)
    val sala = ChamosVivo.room?.roomId?.value
    val cfg by ChamosTraductor.flow.collectAsState()
    val activa = sala != null && cfg[sala]?.activa == true
    var resultado by remember(texto, activa) { mutableStateOf<String?>(null) }
    if (activa && sala != null) {
        LaunchedEffect(texto, sala) { resultado = ChamosTraductor.traducirEntrante(sala, texto) }
        val r = resultado
        if (r != null) {
            androidx.compose.material3.Text(
                text = "🌐 $r",
                color = Color.Unspecified,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Interruptor del traductor en la barra de arriba de cada sala. */
@Composable
internal fun ChamosTraductorBoton() {
    val ctx = LocalContext.current
    ChamosTraductor.iniciar(ctx)
    val sala = ChamosVivo.room?.roomId?.value ?: return
    val cfg by ChamosTraductor.flow.collectAsState()
    val c = cfg[sala] ?: ChamosTraductor.config(sala)
    var abierto by remember { mutableStateOf(false) }
    androidx.compose.material3.Text(
        text = "文A",
        color = if (c.activa) Color(0xFF2F8BFF) else Color.Unspecified,
        fontSize = 16.sp,
        modifier = Modifier.clickable { abierto = true }.padding(horizontal = 10.dp, vertical = 8.dp),
    )
    if (abierto) {
        AlertDialog(
            onDismissRequest = { abierto = false },
            confirmButton = { TextButton(onClick = { abierto = false }) { androidx.compose.material3.Text("Listo") } },
            title = { androidx.compose.material3.Text("Traductor de este chat") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        androidx.compose.material3.Text("Traducir automáticamente", modifier = Modifier.weight(1f))
                        Switch(checked = c.activa, onCheckedChange = { ChamosTraductor.poner(ctx, sala, it, c.mio, c.otro) })
                    }
                    androidx.compose.material3.Text(
                        "Elige los dos idiomas de este chat: lo que te escriban en el otro idioma lo ves en el tuyo, y lo que escribas en el tuyo sale en el otro. Se traduce en tu teléfono.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    androidx.compose.material3.Text("Mi idioma:")
                    for ((codigo, nombre) in ChamosTraductor.IDIOMAS) {
                        TextButton(onClick = { ChamosTraductor.poner(ctx, sala, c.activa, codigo, c.otro) }) {
                            androidx.compose.material3.Text((if (codigo == c.mio) "✓ " else "") + nombre)
                        }
                    }
                    androidx.compose.material3.Text("Idioma de la otra persona:", modifier = Modifier.padding(top = 8.dp))
                    for ((codigo, nombre) in ChamosTraductor.IDIOMAS) {
                        TextButton(onClick = { ChamosTraductor.poner(ctx, sala, c.activa, c.mio, codigo) }) {
                            androidx.compose.material3.Text((if (codigo == c.otro) "✓ " else "") + nombre)
                        }
                    }
                }
            },
        )
    }
}
