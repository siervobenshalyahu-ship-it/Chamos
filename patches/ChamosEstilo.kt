package io.element.android.compound.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("chamos_estilo", Context.MODE_PRIVATE)
        prefs = p
        paleta = p.getInt("paleta", 0).coerceIn(0, paletas.lastIndex)
        fondo = p.getInt("fondo", 0).coerceIn(0, fondos.lastIndex)
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
