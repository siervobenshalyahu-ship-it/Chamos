package io.element.android.libraries.matrix.api

/** Puente: la app de Chamos pone aquí el filtro que deja solo cuentas de Chamos en la búsqueda de usuarios. */
object ChamosFiltroBusqueda {
    @Volatile
    var filtro: (suspend (List<String>) -> Set<String>?)? = null
}
