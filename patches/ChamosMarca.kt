package io.element.android.features.home.impl

/** Identifica a esta app ante los servidores de Chamos (paquete). Lo fija MainActivity al abrir. */
object ChamosMarca {
    @Volatile
    var pkg: String = "?"
}
