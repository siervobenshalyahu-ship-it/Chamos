package io.element.android.features.home.impl

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Tres niños tomados de la mano (ícono de Chamo Redes). */
internal val ChamosIconoNinos: ImageVector by lazy {
    ImageVector.Builder("ChamosNinos", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                // cabezas
                "M3,6 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0 " +
                    "M10,5 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0 " +
                    "M17,6 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0 " +
                    // cuerpos
                    "M3.2,9 h3.6 v6 h-3.6 z M10.2,8 h3.6 v7 h-3.6 z M17.2,9 h3.6 v6 h-3.6 z " +
                    // manos agarradas
                    "M6.8,10 h3.4 v1.6 h-3.4 z M13.8,10 h3.4 v1.6 h-3.4 z " +
                    // piernas
                    "M3.8,15 h1.5 v6 h-1.5 z M5.7,15 h1.5 v6 h-1.5 z " +
                    "M10.8,15 h1.5 v6 h-1.5 z M12.7,15 h1.5 v6 h-1.5 z " +
                    "M17.8,15 h1.5 v6 h-1.5 z M19.7,15 h1.5 v6 h-1.5 z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Mundito con una gorra puesta hacia atrás (ícono del navegador). */
internal val ChamosIconoMundoGorra: ImageVector by lazy {
    ImageVector.Builder("ChamosMundoGorra", 24.dp, 24.dp, 24f, 24f).apply {
        // globo
        addPath(
            pathData = addPathNodes(
                "M4.5,14.5 a7.5,7.5 0 1,0 15,0 a7.5,7.5 0 1,0 -15,0 " +
                    "M4.5,14.5 h15 " +
                    "M12,7 c-3,2.5 -3,12.5 0,15 c3,-2.5 3,-12.5 0,-15",
            ),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
        )
        // gorra: copa y visera hacia atrás
        addPath(
            pathData = addPathNodes(
                "M6.8,9.6 C6.8,3.6 16.4,3.6 16.4,9.6 z " +
                    "M16,7.6 L22.2,5.6 L22.6,7.6 L16.2,9.6 z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
