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
