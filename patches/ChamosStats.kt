package io.element.android.features.home.impl

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Estadísticas de Chamos. Solo se envía algo si la persona aceptó el aviso de privacidad.
 * Se guarda: usuario, nombre, versión de la app, modelo del teléfono y país aproximado.
 * Nunca mensajes, contraseñas ni claves.
 */
object ChamosStats {
    private const val URL_EVENTOS = "https://chamos-push.siervodeyhwh57.workers.dev/e"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("chamos_stats", Context.MODE_PRIVATE)

    /** null = aún no ha decidido. */
    fun decision(c: Context): Boolean? = prefs(c).takeIf { it.contains("ok") }?.getBoolean("ok", false)

    fun decidir(c: Context, acepta: Boolean) {
        prefs(c).edit().putBoolean("ok", acepta).apply()
    }

    private fun anonId(c: Context): String {
        val p = prefs(c)
        return p.getString("anon", null) ?: UUID.randomUUID().toString().also { p.edit().putString("anon", it).apply() }
    }

    private fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun enviar(json: JSONObject) {
        Thread {
            runCatching {
                val con = URL(URL_EVENTOS).openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.connectTimeout = 8000
                con.readTimeout = 8000
                con.doOutput = true
                con.setRequestProperty("content-type", "text/plain")
                con.outputStream.use { it.write(json.toString().toByteArray()) }
                con.responseCode
                con.disconnect()
            }
        }.start()
    }

    /** Llamar con la sesión iniciada. Máximo un aviso de registro al día por usuario. */
    fun reportar(c: Context, userId: String, nombre: String?) {
        if (decision(c) != true) return
        val p = prefs(c)
        val modelo = "${Build.MANUFACTURER} ${Build.MODEL}"
        if (!p.getBoolean("instalacion_enviada", false)) {
            p.edit().putBoolean("instalacion_enviada", true).apply()
            enviar(JSONObject().put("t", "instalacion").put("id", anonId(c)).put("v", version(c)).put("m", modelo))
        }
        val hoy = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (p.getString("registro_$userId", "") != hoy) {
            p.edit().putString("registro_$userId", hoy).apply()
            enviar(
                JSONObject().put("t", "registro").put("id", anonId(c)).put("u", userId)
                    .put("n", nombre ?: "").put("v", version(c)).put("m", modelo),
            )
        }
    }
}
