package io.element.android.features.home.impl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                Text("Marco de luz de neón", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Mostrar el marco", modifier = Modifier.weight(1f))
                    Switch(checked = ChamosEstilo.marcoActivo, onCheckedChange = { ChamosEstilo.elegirMarcoActivo(it) })
                }
                if (ChamosEstilo.marcoActivo) {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChamosEstilo.neonNombres.forEachIndexed { i, nombre ->
                            val color = ChamosEstilo.neonColores.getOrNull(i)
                            Row(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color ?: Color.Magenta)
                                    .border(if (ChamosEstilo.marcoColor == i) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { ChamosEstilo.elegirMarcoColor(i) },
                            ) {}
                        }
                    }
                    Text("Color: " + ChamosEstilo.neonNombres[ChamosEstilo.marcoColor.coerceIn(0, ChamosEstilo.neonNombres.lastIndex)], style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Luz en movimiento", modifier = Modifier.weight(1f))
                        Switch(checked = ChamosEstilo.marcoMovimiento, onCheckedChange = { ChamosEstilo.elegirMarcoMovimiento(it) })
                    }
                    TextButton(onClick = { ChamosEstilo.elegirMarcoActivo(false) }) { Text("Quitar el marco") }
                }
                Text("Dictado por voz", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Botón \"Dictar\" en los chats (hablas y se escribe solo)", modifier = Modifier.weight(1f))
                    Switch(checked = ChamosEstilo.dictado, onCheckedChange = { ChamosEstilo.elegirDictado(it) })
                }
                Text("Tipo de letra", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                ChamosEstilo.fuentes.forEachIndexed { i, nombre ->
                    val previa = when (i) {
                        1 -> androidx.compose.ui.text.font.FontFamily.Serif
                        2 -> androidx.compose.ui.text.font.FontFamily.Monospace
                        3 -> androidx.compose.ui.text.font.FontFamily.Cursive
                        4 -> androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)))
                        5 -> androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)))
                        6 -> androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)))
                        7 -> androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)))
                        else -> androidx.compose.ui.text.font.FontFamily.Default
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { ChamosEstilo.elegirFuente(i) }.padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = ChamosEstilo.fuente == i, onClick = { ChamosEstilo.elegirFuente(i) })
                        Text("$nombre: Épale chamo, ¿qué más?", fontFamily = previa, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text("Colores", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
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
                Text("Fondo de los chats", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                Text("Dentro de cada chat, el botón de imagen de arriba cambia el fondo solo de ese chat.", style = MaterialTheme.typography.bodySmall)
                ChamosFondoPanel(null)
                val contexto = LocalContext.current
                Text("Arreglar notificaciones (Xiaomi / Redmi)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                TextButton(onClick = { abrirAjuste(contexto, notificacionesIntent(contexto)) }) { Text("1. Sonido y ventana emergente") }
                TextButton(onClick = { abrirAjuste(contexto, *autoInicioIntents(contexto)) }) { Text("2. Inicio automático") }
                TextButton(onClick = { abrirAjuste(contexto, *bateriaIntents(contexto)) }) { Text("3. Batería: sin restricciones") }
                TextButton(onClick = { abrirAjuste(contexto, *otrosPermisosIntents(contexto)) }) { Text("4. Otros permisos (pantalla de bloqueo)") }
                var verCandado by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                TextButton(onClick = { verCandado = true }) { Text("5. Candado (fijar Chamos en recientes)") }
                if (verCandado) {
                    AlertDialog(
                        onDismissRequest = { verCandado = false },
                        title = { Text("Poner el candado") },
                        text = {
                            Text(
                                "Android no deja que una app se ponga el candado sola, pero toma 5 segundos:\n\n" +
                                    "1. Cierra este aviso y abre la lista de apps recientes (el botón de las tres rayas o desliza desde abajo y mantén).\n" +
                                    "2. Mantén presionada la tarjeta de Chamos, o toca el menú de arriba de la tarjeta.\n" +
                                    "3. Toca el candado (Fijar o Bloquear).\n\n" +
                                    "Con el candado, MIUI no cierra Chamos para ahorrar memoria y las notificaciones siguen llegando."
                            )
                        },
                        confirmButton = { TextButton(onClick = { verCandado = false }) { Text("Entendido") } },
                    )
                }
            }
        },
    )
}

private fun notificacionesIntent(c: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)

private fun autoInicioIntents(c: Context) = arrayOf(
    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)),
)

private fun bateriaIntents(c: Context) = arrayOf(
    Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", c.packageName).putExtra("package_label", "Chamos"),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
)

private fun otrosPermisosIntents(c: Context) = arrayOf(
    Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", c.packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)),
)

/** Abre la primera pantalla de ajustes que el teléfono sepa abrir. */
private fun abrirAjuste(c: Context, vararg intents: Intent) {
    for (i in intents) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    Toast.makeText(c, "No se pudo abrir esa pantalla. Búscala en Ajustes del teléfono, Aplicaciones, Chamos.", Toast.LENGTH_LONG).show()
}
