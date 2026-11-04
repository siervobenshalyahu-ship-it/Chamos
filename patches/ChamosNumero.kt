package io.element.android.features.home.impl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Vincula el número de teléfono con la cuenta de Matrix (verificado con el bot de Telegram) y
 * encuentra qué contactos de la agenda ya usan Chamos. Habla con el servidor de /cuentas.
 * La identidad se prueba con un token OpenID que pide el propio servidor Matrix: la contraseña
 * y el token de acceso nunca salen hacia el servidor de números.
 */
object ChamosNumero {
    /** Se rellena al compilar con la dirección de cuentas/servicio-url.txt. */
    private const val SERVICIO = "__CUENTAS_URL__"

    /** Código de país que se usa para los números de la agenda guardados sin él (58 = Venezuela). */
    private const val PAIS = "58"

    val configurado: Boolean get() = SERVICIO.startsWith("https://")

    private var client: MatrixClient? = null
    private var sesiones: SessionStore? = null

    fun attach(c: MatrixClient, s: SessionStore) {
        client = c
        sesiones = s
    }

    class Estado(val vinculado: Boolean, val telefono: String?)
    class Intento(val token: String, val enlace: String)
    class Encontrado(val numero: String, val matrixId: String)

    private suspend fun http(metodo: String, url: String, cuerpo: String?, bearer: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.requestMethod = metodo
            con.connectTimeout = 15000
            con.readTimeout = 20000
            con.setRequestProperty("content-type", "application/json")
            if (bearer != null) con.setRequestProperty("Authorization", "Bearer $bearer")
            if (cuerpo != null) {
                con.doOutput = true
                con.outputStream.use { it.write(cuerpo.toByteArray()) }
            }
            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val texto = flujo?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = if (texto.isBlank()) JSONObject() else JSONObject(texto)
            j.put("_http", codigo)
            j
        } catch (e: Exception) {
            null
        }
    }

    /** Token de la sesion (para subir videos grandes al servidor de Chamos). */
    suspend fun tokenSesion(): String? {
        val c = client ?: return null
        return sesiones?.getSession(c.sessionId.value)?.accessToken
    }

    /** Pide al servidor Matrix un token OpenID: sirve para demostrar quién eres sin compartir tu sesión. */
    private suspend fun openId(): JSONObject? {
        val c = client ?: return null
        val datos = sesiones?.getSession(c.sessionId.value) ?: return null
        val base = datos.homeserverUrl.trimEnd('/')
        val uid = URLEncoder.encode(datos.userId, "UTF-8")
        val r = http("POST", "$base/_matrix/client/v3/user/$uid/openid/request_token", "{}", datos.accessToken) ?: return null
        return if (r.has("access_token")) r else null
    }

    private suspend fun llamar(ruta: String, extra: JSONObject.() -> Unit = {}): JSONObject? {
        if (!configurado) return null
        val o = openId() ?: return null
        val cuerpo = JSONObject().put("openid", o)
        cuerpo.extra()
        return http("POST", SERVICIO.trimEnd('/') + ruta, cuerpo.toString())
    }

    suspend fun miNumero(): Estado? {
        val r = llamar("/mi/numero") ?: return null
        if (r.optInt("_http") != 200) return null
        return Estado(r.optBoolean("vinculado"), r.optString("telefono").ifEmpty { null })
    }

    suspend fun desvincular(): Boolean = llamar("/mi/desvincular")?.optInt("_http") == 200

    suspend fun iniciar(): Intento? {
        val r = llamar("/vincular/iniciar") ?: return null
        if (r.optInt("_http") != 200) return null
        val token = r.optString("token")
        val enlace = r.optString("enlace")
        return if (token.isNotEmpty() && enlace.startsWith("https://")) Intento(token, enlace) else null
    }

    suspend fun estadoIntento(token: String): String {
        if (!configurado) return "error"
        val r = http("GET", SERVICIO.trimEnd('/') + "/vincular/estado?token=" + URLEncoder.encode(token, "UTF-8"), null)
        return r?.optString("estado").orEmpty().ifEmpty { "error" }
    }

    /** "0414-123 4567" -> "+584141234567". Devuelve null si no parece un número. */
    fun normalizarLocal(crudo: String?): String? {
        if (crudo == null) return null
        val conMas = crudo.trim().startsWith("+")
        var d = crudo.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (!conMas) {
            d = when {
                d.startsWith("00") -> d.drop(2)
                d.startsWith("0") -> PAIS + d.drop(1)
                d.length == 10 -> PAIS + d
                else -> d
            }
        }
        return if (d.length in 8..15) "+$d" else null
    }

    fun numerosDeLaAgenda(ctx: Context): List<String> {
        val salida = LinkedHashSet<String>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )
        cursor?.use { c ->
            while (c.moveToNext() && salida.size < 2000) {
                normalizarLocal(c.getString(0))?.let { salida.add(it) }
            }
        }
        return salida.toList()
    }

    suspend fun buscar(numeros: List<String>): List<Encontrado>? {
        val salida = mutableListOf<Encontrado>()
        for (trozo in numeros.chunked(500)) {
            val r = llamar("/contactos/buscar") { put("numeros", JSONArray(trozo)) } ?: return null
            if (r.optInt("_http") != 200) return null
            val a = r.optJSONArray("encontrados") ?: continue
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                salida.add(Encontrado(o.optString("numero"), o.optString("matrixId")))
            }
        }
        return salida
    }

    suspend fun chatear(matrixId: String): Boolean {
        val c = client ?: return false
        return c.createDM(UserId(matrixId), true).isSuccess
    }
}

private fun abrirEnlace(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo abrir Telegram. ¿Lo tienes instalado?", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ChamosNumeroDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var estado by remember { mutableStateOf<ChamosNumero.Estado?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var esperando by remember { mutableStateOf<String?>(null) }
    var enlace by remember { mutableStateOf<String?>(null) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var buscando by remember { mutableStateOf(false) }
    var encontrados by remember { mutableStateOf<List<ChamosNumero.Encontrado>?>(null) }

    fun recargar() {
        scope.launch {
            cargando = true
            estado = ChamosNumero.miNumero()
            cargando = false
        }
    }

    fun vincular() {
        scope.launch {
            mensaje = null
            val i = ChamosNumero.iniciar()
            if (i == null) {
                mensaje = "No se pudo iniciar. Revisa tu internet e inténtalo otra vez."
            } else {
                enlace = i.enlace
                esperando = i.token
                abrirEnlace(ctx, i.enlace)
            }
        }
    }

    fun buscarAhora() {
        scope.launch {
            buscando = true
            mensaje = null
            val numeros = withContext(Dispatchers.IO) { ChamosNumero.numerosDeLaAgenda(ctx) }
            val r = ChamosNumero.buscar(numeros)
            encontrados = r
            if (r == null) mensaje = "No se pudo buscar. Inténtalo más tarde."
            buscando = false
        }
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            buscarAhora()
        } else {
            mensaje = "Sin permiso para leer tus contactos no puedo buscarlos."
        }
    }

    LaunchedEffect(Unit) {
        estado = ChamosNumero.miNumero()
        cargando = false
    }

    LaunchedEffect(esperando) {
        val t = esperando ?: return@LaunchedEffect
        var vueltas = 0
        while (vueltas < 300) {
            delay(2000)
            vueltas++
            val e = ChamosNumero.estadoIntento(t)
            if (e == "verificado") {
                estado = ChamosNumero.miNumero()
                esperando = null
                mensaje = "¡Listo! Tu número quedó vinculado."
                return@LaunchedEffect
            }
            if (e == "expirado") {
                esperando = null
                mensaje = "Se venció el intento. Toca «Vincular» otra vez."
                return@LaunchedEffect
            }
        }
        esperando = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Mi número") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (!ChamosNumero.configurado) {
                    Text("El servidor de números todavía no está activado en esta versión de Chamos.")
                } else if (cargando) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Cargando…")
                    }
                } else if (estado == null) {
                    Text("No se pudo conectar con el servidor. Revisa tu internet.")
                    TextButton(onClick = { recargar() }) { Text("Reintentar") }
                } else if (esperando != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Esperando… toca «Compartir mi número» en Telegram y vuelve aquí.", modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { enlace?.let { abrirEnlace(ctx, it) } }) { Text("Abrir Telegram otra vez") }
                } else if (estado?.vinculado == true) {
                    Text("Número vinculado: ${estado?.telefono.orEmpty()}")
                    Text(
                        "Tus contactos con Chamos podrán encontrarte por tu número.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        enabled = !buscando,
                        onClick = {
                            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                buscarAhora()
                            } else {
                                permiso.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                    ) { Text(if (buscando) "Buscando…" else "Buscar mis contactos en Chamos") }
                    TextButton(onClick = { vincular() }) { Text("Cambiar de número") }
                    TextButton(onClick = {
                        scope.launch {
                            if (ChamosNumero.desvincular()) {
                                encontrados = null
                                recargar()
                            } else {
                                mensaje = "No se pudo quitar el número."
                            }
                        }
                    }) { Text("Quitar mi número") }
                } else {
                    Text("Vincula tu número de teléfono para que tus contactos te encuentren en Chamos. Se verifica con Telegram (gratis): compartes tu contacto con el bot y listo.")
                    TextButton(onClick = { vincular() }) { Text("Vincular con Telegram") }
                }

                mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                encontrados?.let { lista ->
                    if (lista.isEmpty()) {
                        Text("Ninguno de tus contactos usa Chamos con número vinculado todavía.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Contactos en Chamos", style = MaterialTheme.typography.titleSmall)
                        lista.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.matrixId, style = MaterialTheme.typography.bodyMedium)
                                    Text(f.numero, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        if (ChamosNumero.chatear(f.matrixId)) {
                                            Toast.makeText(ctx, "Chat creado. Lo verás en tu lista.", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else {
                                            Toast.makeText(ctx, "No se pudo crear el chat.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) { Text("Chatear") }
                            }
                        }
                    }
                }
            }
        },
    )
}
