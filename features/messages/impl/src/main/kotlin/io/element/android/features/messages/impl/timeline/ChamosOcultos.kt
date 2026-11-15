package io.element.android.features.messages.impl.timeline

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Chamos: "Eliminar para mí". Los mensajes se ocultan solo en este teléfono (el servidor no cambia),
 * guardando sus ids en un archivo local.
 */
object ChamosOcultos {
    private const val ARCHIVO = "chamos_ocultos"
    private const val CLAVE = "ids"
    private const val MAXIMO = 5000

    private var prefs: android.content.SharedPreferences? = null
    private val _ids = mutableStateOf<Set<String>>(emptySet())
    val ids: State<Set<String>> get() = _ids

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        prefs = p
        _ids.value = p.getStringSet(CLAVE, emptySet()).orEmpty().toSet()
    }

    fun ocultar(context: Context, eventIds: Collection<String>) {
        init(context)
        val nuevo = (_ids.value + eventIds).toList().takeLast(MAXIMO).toSet()
        _ids.value = nuevo
        prefs?.edit()?.putStringSet(CLAVE, nuevo)?.apply()
    }
}
