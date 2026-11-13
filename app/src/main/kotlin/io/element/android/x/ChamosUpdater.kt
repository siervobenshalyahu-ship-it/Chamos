package io.element.android.x

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Actualizaciones de Chamos desde la propia app.
 * Mira el último release de GitHub y, si hay una versión más nueva, ofrece descargarla
 * e instalarla encima de la actual (sin desinstalar y sin perder los chats).
 */
object ChamosUpdater {
    private const val REPO = "siervobenshalyahu-ship-it/Chamos"
    private const val PREFS = "chamos_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_EVERY_MS = 60L * 60L * 1000L
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val KEY_AUTO = "auto"
    private const val KEY_DL_ID = "dl_id"
    private const val KEY_DL_TAG = "dl_tag"
    private var actividad: WeakReference<Activity>? = null
    private const val ACCION_INSTALAR = "io.element.android.x.CHAMOS_INSTALAR_RESULTADO"

    /** Siempre se actualiza sola: descarga e instala sin preguntar. */
    @JvmStatic
    fun autoActivo(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    @JvmStatic
    fun alternarAuto(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    /** Botón «Buscar actualización»: ignora el intervalo y avisa del resultado. */
    @JvmStatic
    fun checkNow(activity: Activity) = checkForUpdate(activity, force = true)

    fun checkForUpdate(activity: Activity, force: Boolean = false) {
        actividad = WeakReference(activity)
        ChamosInstalaciones.avisar(activity)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (instalarDescargaPendiente(activity)) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MS) return
        fun aviso(texto: String) {
            if (force) activity.runOnUiThread { Toast.makeText(activity, texto, Toast.LENGTH_LONG).show() }
        }
        aviso("Buscando actualizaciones…")
        thread(name = "chamos-update-check", isDaemon = true) {
            try {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) {
                    aviso("No se pudo buscar la actualización. Intenta de nuevo en un rato.")
                    return@thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                val tag = json.optString("tag_name")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                    aviso("Ya tienes la última versión de Chamos (${BuildConfig.VERSION_NAME}).")
                    return@thread
                }
                val assets = json.optJSONArray("assets") ?: return@thread
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                // Solo se descarga desde los releases del propio repositorio de Chamos.
                if (!apkUrl.startsWith("https://github.com/$REPO/")) return@thread
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    askToUpdate(activity, tag, apkUrl)
                }
            } catch (_: Exception) {
                aviso("No se pudo buscar la actualización. Revisa tu internet.")
            }
        }
    }

    private fun isNewer(tag: String, current: String): Boolean {
        fun numbers(s: String): List<Long> = Regex("\\d+").findAll(s).map { it.value.toLongOrNull() ?: 0L }.toList()
        val latest = numbers(tag)
        val installed = numbers(current)
        if (latest.isEmpty()) return false
        for (i in 0 until maxOf(latest.size, installed.size)) {
            val a = latest.getOrElse(i) { 0L }
            val b = installed.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }

    private fun askToUpdate(activity: Activity, tag: String, url: String) {
        AlertDialog.Builder(activity)
            .setTitle("Nueva versión de Chamos")
            .setMessage("Hay una versión nueva ($tag). Se instala encima de la que tienes, sin perder tus chats. ¿Actualizar ahora?")
            .setPositiveButton("Actualizar") { _, _ -> startDownload(activity, tag, url) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun abrirInstalador(contexto: Context, uri: Uri) {
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contexto.startActivity(install)
    }

    /** Si ya hay una actualización descargada que no se instaló, se ofrece instalarla (en primer plano). */
    private fun instalarDescargaPendiente(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DL_ID, -1L)
        val tag = prefs.getString(KEY_DL_TAG, null) ?: return false
        if (id < 0 || !isNewer(tag, BuildConfig.VERSION_NAME)) {
            prefs.edit().remove(KEY_DL_ID).remove(KEY_DL_TAG).apply()
            return false
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) return false
        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            AlertDialog.Builder(activity)
                .setTitle("Chamos $tag está lista")
                .setMessage("Ya se descargó la versión más reciente. ¿Quieres instalarla ahora? Se instala encima de la que tienes, sin perder tus chats.")
                .setPositiveButton("Instalar") { _, _ -> abrirInstalador(activity, uri) }
                .setNegativeButton("Más tarde", null)
                .show()
        }
        return true
    }

    private fun startDownload(activity: Activity, tag: String, url: String) {
        val app = activity.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Activa «Permitir de esta fuente» para Chamos y vuelve a tocar Actualizar", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)))
            return
        }
        val carpeta = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        // Se borran los APK de actualizaciones anteriores para no llenar el teléfono.
        carpeta?.listFiles()?.forEach { if (it.name.startsWith("Chamos-") && it.name.endsWith(".apk")) it.delete() }
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Chamos $tag")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "Chamos-$tag.apk")
        val id = manager.enqueue(request)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DL_ID, id).putString(KEY_DL_TAG, tag).apply()
        Toast.makeText(app, "Descargando la actualización… mira la barra de notificaciones", Toast.LENGTH_LONG).show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                app.unregisterReceiver(this)
                val uri = manager.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "No se pudo descargar la actualización. Intenta de nuevo más tarde.", Toast.LENGTH_LONG).show()
                    return
                }
                // Con la app abierta se lanza el instalador de Android (en Redmi pasa primero por su revisión de seguridad).
                val act = actividad?.get()
                if (act != null && !act.isFinishing) {
                    runCatching { abrirInstalador(act, uri) }.onFailure {
                        Toast.makeText(app, "Descarga lista. Toca la notificación para instalar.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(app, "Descarga lista. Toca la notificación para instalar.", Toast.LENGTH_LONG).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}

/** Cuenta cuántas personas logran instalar y abrir Chamos: manda una sola vez un código al azar, sin datos personales. */
object ChamosInstalaciones {
    private const val URL_SERVICIO = "https://chamos-cuentas.siervodeyhwh57.workers.dev/chamos/instalacion"

    @JvmStatic
    fun avisar(contexto: Context) {
        val prefs = contexto.getSharedPreferences("chamos_instalacion", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enviado", false)) return
        val id = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        val version = runCatching { contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName }.getOrNull() ?: ""
        thread {
            runCatching {
                val conn = URL(URL_SERVICIO).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("id", id).put("version", version).toString().toByteArray()) }
                if (conn.responseCode in 200..299) prefs.edit().putBoolean("enviado", true).apply()
                conn.disconnect()
            }
        }
    }
}
