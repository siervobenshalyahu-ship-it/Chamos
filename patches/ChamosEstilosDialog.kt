package io.element.android.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ChamosEstilo

@Composable
fun ChamosEstilosDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text("Estilos") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Colores", style = MaterialTheme.typography.titleSmall)
                ChamosEstilo.paletas.forEachIndexed { i, p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ChamosEstilo.elegirPaleta(i) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(p.acento)
                                .border(
                                    if (ChamosEstilo.paleta == i) 3.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape,
                                ),
                        ) {}
                        Text(p.nombre, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("Fondo del chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                ChamosEstilo.fondos.forEachIndexed { i, nombre ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ChamosEstilo.elegirFondo(i) },
                    ) {
                        RadioButton(selected = ChamosEstilo.fondo == i, onClick = { ChamosEstilo.elegirFondo(i) })
                        Text(nombre)
                    }
                }
            }
        },
    )
}
