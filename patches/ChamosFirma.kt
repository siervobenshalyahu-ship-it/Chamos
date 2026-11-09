package io.element.android.x

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Comprueba que esta copia de Chamos fue firmada con la llave oficial. */
object ChamosFirma {
    // Huella SHA-256 de la llave oficial de Chamos (sin dos puntos, en minúsculas).
    private val OFICIALES = setOf(
        "7c13b8f28ea511a38ece5bdefdf91072e8a584cde92b96a7162907a48fee6931",
        "67b0771894605d9a978599287ba6755f039f1882891afdbc1557c71cc81d551f",
    )

    @Suppress("DEPRECATION")
    fun verificar(activity: Activity): Boolean {
        val ok = try {
            val pm = activity.packageManager
            val firmas = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            firmas?.any { f ->
                MessageDigest.getInstance("SHA-256").digest(f.toByteArray())
                    .joinToString("") { "%02x".format(it) } in OFICIALES
            } == true
        } catch (e: Exception) {
            false
        }
        avisarInstalacion(activity, ok)
        if (!ok) {
            AlertDialog.Builder(activity)
                .setTitle("Copia no oficial de Chamos")
                .setMessage("Esta copia no fue firmada por el equipo de Chamos y podría ser peligrosa. Descarga la app oficial desde la página de Chamos.")
                .setCancelable(false)
                .setPositiveButton("Salir") { _, _ -> activity.finishAffinity() }
                .show()
        }
        return ok
    }

    /**
     * Cuenta la instalación (una vez por versión): modelo del teléfono, versión y si la firma es la oficial.
     * Sin nombre, número ni mensajes; solo un identificador aleatorio de la instalación.
     */
    private fun avisarInstalacion(activity: Activity, firmaOk: Boolean) {
        try {
            val c = activity.applicationContext
            val version = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
            val p = c.getSharedPreferences("chamos_stats", android.content.Context.MODE_PRIVATE)
            if (p.getString("inst_v", "") == version) return
            val anon = p.getString("anon", null) ?: UUID.randomUUID().toString().also { p.edit().putString("anon", it).apply() }
            val cuerpo = org.json.JSONObject()
                .put("t", "instalacion").put("id", anon).put("v", version)
                .put("m", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("p", if (firmaOk) "firma oficial" else "FIRMA NO OFICIAL")
                .toString()
            Thread {
                try {
                    val con = URL("https://chamos-push.siervodeyhwh57.workers.dev/e").openConnection() as HttpURLConnection
                    con.requestMethod = "POST"
                    con.connectTimeout = 8000
                    con.readTimeout = 8000
                    con.doOutput = true
                    con.setRequestProperty("content-type", "text/plain")
                    con.setRequestProperty("x-chamos-app", activity.packageName)
                    con.outputStream.use { it.write(cuerpo.toByteArray()) }
                    if (con.responseCode in 200..299) p.edit().putString("inst_v", version).apply()
                    con.disconnect()
                } catch (e: Exception) {
                }
            }.start()
        } catch (e: Exception) {
        }
    }
}
