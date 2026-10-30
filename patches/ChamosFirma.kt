package io.element.android.x

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Comprueba que esta copia de Chamos fue firmada con la llave oficial. */
object ChamosFirma {
    // Huella SHA-256 de la llave oficial de Chamos (sin dos puntos, en minúsculas).
    private val OFICIALES = setOf(
        "7c13b8f28ea511a38ece5bdefdf91072e8a584cde92b96a7162907a48fee6931",
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
}
