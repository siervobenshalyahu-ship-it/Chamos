package io.element.android.libraries.matrix.api

/** Puente: la app de Chamos pone aquí el filtro que deja solo cuentas de Chamos en la búsqueda de usuarios. */
object ChamosFiltroBusqueda {
    @Volatile
    var filtro: (suspend (List<String>) -> Set<String>?)? = null

    /** Token de la sesión de Matrix, para hablar con el servidor de Chamos desde otros módulos. */
    @Volatile
    var token: (suspend () -> String?)? = null

    /** Interruptor de la búsqueda: true = solo cuentas de Chamos, false = todos (Chamos, Matrix y Element). */
    val soloChamos = kotlinx.coroutines.flow.MutableStateFlow(true)
}
