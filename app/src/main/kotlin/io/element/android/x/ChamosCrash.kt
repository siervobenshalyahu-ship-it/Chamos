package io.element.android.x

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.File

/** Guarda el último error que cerró Chamos y lo muestra al abrir, para poder arreglarlo. */
object ChamosCrash {
    private const val ARCHIVO = "ultimo_error.txt"

    fun instalar(context: Context) {
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        val carpeta = context.applicationContext.filesDir
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                File(carpeta, ARCHIVO).writeText("Hilo: ${hilo.name}\n" + Log.getStackTraceString(error))
            } catch (_: Throwable) {
            }
            previo?.uncaughtException(hilo, error)
        }
    }

    fun mostrarSiHay(activity: Activity) {
        val archivo = File(activity.filesDir, ARCHIVO)
        if (!archivo.exists()) return
        val texto = try { archivo.readText() } catch (_: Throwable) { return }
        val corto = texto.take(1800)
        AlertDialog.Builder(activity)
            .setTitle("Chamos se cerró por un error")
            .setMessage(corto)
            .setPositiveButton("Copiar y cerrar") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Error de Chamos", texto))
                Toast.makeText(activity, "Copiado", Toast.LENGTH_SHORT).show()
                archivo.delete()
            }
            .setNegativeButton("Cerrar") { _, _ -> archivo.delete() }
            .show()
    }
}

/** Pide el permiso del micrófono (necesario para grabar notas de voz) y guía a Ajustes si el teléfono lo tiene bloqueado. */
object ChamosPermisos {
    private const val PERMISO = android.Manifest.permission.RECORD_AUDIO

    fun pedirMicrofono(activity: Activity) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(activity, PERMISO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val prefs = activity.getSharedPreferences("chamos_permisos", Context.MODE_PRIVATE)
            val yaPedido = prefs.getBoolean("mic_pedido", false)
            val puedeVolverAPedir = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISO)
            if (!yaPedido || puedeVolverAPedir) {
                prefs.edit().putBoolean("mic_pedido", true).apply()
                androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(PERMISO), 4711)
                return
            }
            // El sistema ya no muestra el aviso (bloqueado): explicar cómo activarlo, como máximo una vez al día.
            val hoy = System.currentTimeMillis() / 86_400_000L
            if (prefs.getLong("mic_aviso_dia", -1L) == hoy) return
            prefs.edit().putLong("mic_aviso_dia", hoy).apply()
            AlertDialog.Builder(activity)
                .setTitle("Activa el micrófono")
                .setMessage(
                    "Para grabar notas de voz, Chamos necesita permiso del micrófono.\n\n" +
                        "Toca \"Abrir ajustes\" → Permisos → Micrófono → Permitir (solo mientras se usa la app).",
                )
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", activity.packageName, null),
                    )
                    activity.startActivity(intent)
                }
                .setNegativeButton("Ahora no", null)
                .show()
        } catch (e: Throwable) {
            Log.e("Chamos", "permisos", e)
        }
    }
}
