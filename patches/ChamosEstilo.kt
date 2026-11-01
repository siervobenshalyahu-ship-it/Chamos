package io.element.android.compound.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.element.android.compound.tokens.generated.SemanticColors

/** Estilos personalizables de Chamos: paleta de colores y fondo del chat. */
object ChamosEstilo {
    class Paleta(val nombre: String, val acento: Color, val lienzoClaro: Color? = null, val lienzoOscuro: Color? = null)

    val paletas = listOf(
        Paleta("Chamos", Color(0xFF229FD7)),
        Paleta("Bandera", Color(0xFFCF142B)),
        Paleta("Selva", Color(0xFF1B9E5A)),
        Paleta("Atardecer", Color(0xFFF26B1D)),
        Paleta("Cósmico", Color(0xFF7B4DFF)),
        Paleta("Rosa", Color(0xFFE8398D)),
        Paleta("Cómic antiguo", Color(0xFFD62828), Color(0xFFF6EBCB), Color(0xFF1C1810)),
        Paleta("Noche negra", Color(0xFF22A0D8), null, Color(0xFF000000)),
    )

    val fondos = listOf(
        "Sin fondo", "Salto Ángel", "Roraima", "Los Roques", "El Ávila",
        "Médanos de Coro", "Teleférico de Mérida", "Cómic antiguo", "Estilo anime", "Ciudad de héroes",
    )

    var paleta by mutableIntStateOf(0)
        private set
    var fondo by mutableIntStateOf(0)
        private set

    /** Marco de luz de neón alrededor de toda la app (el último color de la lista es "Arcoíris"). */
    val neonColores = listOf(
        Color(0xFF00E5FF), Color(0xFFFF2D95), Color(0xFFB6FF00), Color(0xFFFF9100), Color(0xFFB388FF),
        Color(0xFFFFEA00), Color(0xFF00FF9C), Color(0xFFFF4D4D), Color(0xFF3D7BFF), Color(0xFFFF6EFF),
    )
    val neonNombres = listOf("Cian", "Rosa", "Lima", "Naranja", "Violeta", "Amarillo", "Menta", "Rojo", "Azul", "Fucsia", "Arcoíris")

    var marcoActivo by mutableStateOf(true)
        private set
    var marcoColor by mutableIntStateOf(0)
        private set
    var marcoMovimiento by mutableStateOf(true)
        private set
    var dictado by mutableStateOf(true)
        private set

    /** Fondos con imágenes propias (nombres de archivo en la carpeta de la app) y fondos distintos por chat. */
    var fondoPropio by mutableStateOf<String?>(null)
        private set
    val fondosPropios = mutableStateListOf<String>()
    val fondosPorChat = mutableStateMapOf<String, String>()
    var chatActual by mutableStateOf("")

    private var prefs: SharedPreferences? = null
    private var prefsChat: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("chamos_estilo", Context.MODE_PRIVATE)
        prefs = p
        paleta = p.getInt("paleta", 0).coerceIn(0, paletas.lastIndex)
        fondo = p.getInt("fondo", 0).coerceIn(0, fondos.lastIndex)
        marcoActivo = p.getBoolean("marco", true)
        marcoColor = p.getInt("marcoColor", 0).coerceIn(0, neonColores.size)
        marcoMovimiento = p.getBoolean("marcoMov", true)
        dictado = p.getBoolean("dictado", true)
        fondoPropio = p.getString("fondoPropio", null)
        fondosPropios.clear()
        p.getString("propios", "").orEmpty().split("\n").filter { it.isNotBlank() }.forEach { fondosPropios.add(it) }
        val pc = context.applicationContext.getSharedPreferences("chamos_fondos_chat", Context.MODE_PRIVATE)
        prefsChat = pc
        fondosPorChat.clear()
        pc.all.forEach { (k, v) -> if (v is String) fondosPorChat[k] = v }
    }

    fun elegirMarcoActivo(v: Boolean) {
        marcoActivo = v
        prefs?.edit()?.putBoolean("marco", v)?.apply()
    }

    fun elegirMarcoColor(i: Int) {
        marcoColor = i.coerceIn(0, neonColores.size)
        prefs?.edit()?.putInt("marcoColor", marcoColor)?.apply()
    }

    fun elegirMarcoMovimiento(v: Boolean) {
        marcoMovimiento = v
        prefs?.edit()?.putBoolean("marcoMov", v)?.apply()
    }

    fun elegirDictado(v: Boolean) {
        dictado = v
        prefs?.edit()?.putBoolean("dictado", v)?.apply()
    }

    /** Clave del fondo de un chat: "p:N" (fondo de la app) o "u:archivo" (imagen propia). */
    fun claveFondo(roomId: String): String =
        fondosPorChat[roomId] ?: fondoPropio?.let { "u:$it" } ?: "p:$fondo"

    fun elegirFondoGeneral(clave: String) {
        if (clave.startsWith("u:")) {
            fondoPropio = clave.drop(2)
            prefs?.edit()?.putString("fondoPropio", fondoPropio)?.apply()
        } else {
            fondoPropio = null
            fondo = (clave.removePrefix("p:").toIntOrNull() ?: 0).coerceIn(0, fondos.lastIndex)
            prefs?.edit()?.remove("fondoPropio")?.putInt("fondo", fondo)?.apply()
        }
    }

    /** clave = null: el chat vuelve a usar el fondo general. */
    fun elegirFondoChat(roomId: String, clave: String?) {
        if (clave == null) {
            fondosPorChat.remove(roomId)
            prefsChat?.edit()?.remove(roomId)?.apply()
        } else {
            fondosPorChat[roomId] = clave
            prefsChat?.edit()?.putString(roomId, clave)?.apply()
        }
    }

    private fun guardarPropios() {
        prefs?.edit()?.putString("propios", fondosPropios.joinToString("\n"))?.apply()
    }

    /** Copia la imagen elegida a la carpeta de la app, reducida (llamar fuera del hilo principal). */
    fun guardarImagen(context: Context, uri: Uri): String? = try {
        val cr = context.contentResolver
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / muestra > 1800) muestra *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
        val original = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opciones) }
        if (original == null) {
            null
        } else {
            val giro = try {
                cr.openInputStream(uri)?.use { s ->
                    when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Throwable) {
                0f
            }
            val listo: Bitmap = if (giro != 0f) {
                val m = Matrix().apply { postRotate(giro) }
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
            } else {
                original
            }
            val carpeta = File(context.filesDir, "chamos_fondos").apply { mkdirs() }
            val nombre = "fondo_" + System.currentTimeMillis() + ".jpg"
            File(carpeta, nombre).outputStream().use { listo.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            nombre
        }
    } catch (_: Throwable) {
        null
    }

    fun registrarFondo(nombre: String) {
        if (nombre !in fondosPropios) fondosPropios.add(nombre)
        guardarPropios()
    }

    fun borrarFondo(context: Context, nombre: String) {
        runCatching { File(File(context.filesDir, "chamos_fondos"), nombre).delete() }
        fondosPropios.remove(nombre)
        guardarPropios()
        if (fondoPropio == nombre) {
            fondoPropio = null
            prefs?.edit()?.remove("fondoPropio")?.apply()
        }
        fondosPorChat.entries.filter { it.value == "u:$nombre" }.map { it.key }.forEach { elegirFondoChat(it, null) }
    }

    fun cargarBitmap(context: Context, nombre: String, maxLado: Int): Bitmap? = try {
        val f = File(File(context.filesDir, "chamos_fondos"), nombre)
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, medidas)
        var m = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / m > maxLado) m *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = m })
    } catch (_: Throwable) {
        null
    }

    fun elegirPaleta(i: Int) {
        paleta = i
        prefs?.edit()?.putInt("paleta", i)?.apply()
    }

    fun elegirFondo(i: Int) {
        fondo = i
        prefs?.edit()?.putInt("fondo", i)?.apply()
    }

    private fun Color.sobre(): Color = if ((0.299f * red + 0.587f * green + 0.114f * blue) > 0.6f) Color.Black else Color.White

    fun tinte(c: SemanticColors, oscuro: Boolean): SemanticColors {
        val p = paletas[paleta]
        if (paleta == 0) return c
        val a = p.acento
        val lienzo = if (oscuro) p.lienzoOscuro else p.lienzoClaro
        val base = c.copy(
            bgAccentRest = a,
            bgActionPrimaryRest = a,
            bgActionPrimaryHovered = a,
            bgActionPrimaryPressed = a,
            bgAccentSelected = a.copy(alpha = 0.25f),
            textActionAccent = a,
            iconAccentPrimary = a,
        )
        return if (lienzo != null) base.copy(bgCanvasDefault = lienzo, bgSubtlePrimary = lienzo) else base
    }

    fun tinteMaterial(m: ColorScheme, oscuro: Boolean): ColorScheme {
        val p = paletas[paleta]
        if (paleta == 0) return m
        val a = p.acento
        val lienzo = if (oscuro) p.lienzoOscuro else p.lienzoClaro
        val base = m.copy(primary = a, onPrimary = a.sobre(), primaryContainer = a.copy(alpha = 0.25f), surfaceTint = a)
        return if (lienzo != null) base.copy(background = lienzo, surface = lienzo) else base
    }
}
