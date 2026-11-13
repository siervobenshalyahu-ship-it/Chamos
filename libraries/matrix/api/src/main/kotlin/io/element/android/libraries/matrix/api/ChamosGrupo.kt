package io.element.android.libraries.matrix.api

/** Cuando es true, la proxima pantalla de "nuevo chat" abre directo la creacion de un grupo. */
object ChamosGrupo {
    @Volatile var directo: Boolean = false
    fun tomar(): Boolean { val v = directo; directo = false; return v }
}
