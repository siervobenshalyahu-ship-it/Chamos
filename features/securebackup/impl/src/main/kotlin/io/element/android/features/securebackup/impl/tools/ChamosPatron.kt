package io.element.android.features.securebackup.impl.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton

/**
 * Patrón de Chamos: 9 puntos que se tocan en orden (mínimo 4). Se convierte en un texto
 * ("patron-1-5-9-...") que sirve como contraseña de recuperación.
 */
@Composable
fun ChamosPatron(
    onPatron: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var orden by remember { mutableStateOf(listOf<Int>()) }
    fun cambiar(nuevo: List<Int>) {
        orden = nuevo
        onPatron(if (nuevo.size >= 4) "patron-" + nuevo.joinToString("-") else null)
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        for (fila in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                for (col in 0..2) {
                    val n = fila * 3 + col + 1
                    val pos = orden.indexOf(n)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (pos >= 0) Color(0xFF29C5FF) else Color(0x3329C5FF))
                            .border(2.dp, Color(0xFF7A3FE0), CircleShape)
                            .clickable(enabled = pos < 0) { cambiar(orden + n) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pos >= 0) Text(text = (pos + 1).toString(), color = Color.Black)
                    }
                }
            }
        }
        Text(
            text = if (orden.size >= 4) "Patrón listo (${orden.size} puntos)" else "Toca al menos 4 puntos en orden",
        )
        TextButton(text = "Borrar patrón", onClick = { cambiar(emptyList()) })
    }
}
