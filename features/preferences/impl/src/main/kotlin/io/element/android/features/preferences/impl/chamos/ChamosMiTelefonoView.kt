package io.element.android.features.preferences.impl.chamos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text as ElText
import io.element.android.libraries.designsystem.theme.components.IconSource
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Chamos: entrada "Mi teléfono" en Ajustes. Abre un cuadro para activar/desactivar y ver tus teléfonos. */
@Composable
fun ChamosMiTelefonoItem() {
    var abierto by remember { mutableStateOf(false) }
    ListItem(
        content = { ElText("Mi teléfono (encontrarlo si lo pierdo)") },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.LocationPin())),
        onClick = { abierto = true },
    )
    if (abierto) ChamosMiTelefonoDialog(onCerrar = { abierto = false })
}

@Composable
private fun ChamosMiTelefonoDialog(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var activo by remember { mutableStateOf(ChamosMiTelefono.activado(ctx)) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var telefonos by remember { mutableStateOf<List<ChamosMiTelefono.Telefono>?>(null) }
    var recarga by remember { mutableStateOf(0) }
    var permisoOk by remember { mutableStateOf(ChamosMiTelefono.tienePermiso(ctx)) }

    LaunchedEffect(recarga, activo) {
        telefonos = ChamosMiTelefono.lista()
    }

    fun activar() {
        scope.launch {
            ocupado = true
            mensaje = ChamosMiTelefono.activar(ctx)
            activo = ChamosMiTelefono.activado(ctx)
            recarga++
            ocupado = false
        }
    }

    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permisoOk = ChamosMiTelefono.tienePermiso(ctx)
        if (permisoOk) activar() else mensaje = "Sin permiso de ubicación no se puede activar."
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Mi teléfono") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Si lo activas, este teléfono guarda en Chamos su última ubicación y batería cada ~15 minutos. " +
                        "Solo tú la ves, entrando con tu cuenta desde otro dispositivo. Viene apagado y puedes quitarlo cuando quieras.",
                )
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = if (activo) "Estado: ACTIVADO en este teléfono" else "Estado: apagado",
                )
                if (activo && !ChamosMiTelefono.tienePermisoSiempre(ctx)) {
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = "Para que se actualice con la app cerrada, en Ajustes del teléfono > Chamos > Permisos > Ubicación elige \"Permitir todo el tiempo\".",
                    )
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Abrir ajustes de Chamos") }
                }
                mensaje?.let { Text(modifier = Modifier.padding(top = 8.dp), text = it) }
                Text(modifier = Modifier.padding(top = 16.dp), text = "Tus teléfonos con Mi teléfono activado:")
                val lista = telefonos
                when {
                    lista == null -> Text("Cargando…")
                    lista.isEmpty() -> Text("Ninguno todavía.")
                    else -> lista.forEach { t ->
                        val propio = t.id == ChamosMiTelefono.idLocal(ctx)
                        val cuando = if (t.ts > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.ts)) else "sin ubicación aún"
                        Text(
                            modifier = Modifier.padding(top = 8.dp),
                            text = t.nombre + (if (propio) " (este)" else "") + "\nÚltima vez: " + cuando + (if (t.ts > 0) " · batería ${t.bateria}%" else ""),
                        )
                        if (t.ts > 0 && !propio) {
                            TextButton(onClick = { ChamosMiTelefono.abrirMapa(ctx, t) }) { Text("Ver en el mapa") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activo) {
                TextButton(enabled = !ocupado, onClick = {
                    scope.launch {
                        ocupado = true
                        ChamosMiTelefono.desactivar(ctx)
                        activo = false
                        recarga++
                        ocupado = false
                    }
                }) { Text("Desactivar") }
            } else {
                TextButton(enabled = !ocupado, onClick = {
                    if (ChamosMiTelefono.tienePermiso(ctx)) activar()
                    else pedirPermiso.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Activar") }
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
