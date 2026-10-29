package io.element.android.x

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    private const val CHECK_EVERY_MS = 6L * 60L * 60L * 1000L
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun checkForUpdate(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MS) return
        thread(name = "chamos-update-check", isDaemon = true) {
            try {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@thread
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                val tag = json.optString("tag_name")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@thread
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
                    if (!activity.isFinishing) askToUpdate(activity, tag, apkUrl)
                }
            } catch (_: Exception) {
                // Sin internet o GitHub no responde: se intenta otra vez en el próximo arranque.
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

    private fun startDownload(activity: Activity, tag: String, url: String) {
        val app = activity.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Activa «Permitir de esta fuente» para Chamos y vuelve a tocar Actualizar", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)))
            return
        }
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Chamos $tag")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "Chamos-$tag.apk")
        val id = manager.enqueue(request)
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
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(install)
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
