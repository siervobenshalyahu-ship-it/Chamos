package io.element.android.features.messages.impl.messagecomposer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.location.LiveLocationShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Ubicación en tiempo real de Chamos: menú propio (sin claves de mapas) para compartir tu posición en el chat
 * y para ver en un mapa de OpenStreetMap a quienes la están compartiendo.
 */
internal object ChamosVivo {
    @Volatile var room: JoinedRoom? = null
    @Volatile var manager: ActiveLiveLocationShareManager? = null
    @Volatile var scope: CoroutineScope? = null

    fun vincular(r: JoinedRoom, m: ActiveLiveLocationShareManager, s: CoroutineScope) {
        room = r
        manager = m
        scope = s
    }
}

@Composable
internal fun ChamosVivoItem() {
    var abierto by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { abierto = true },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.LocationPin())),
        content = { androidx.compose.material3.Text("Ubicación en tiempo real") },
    )
    if (abierto) ChamosVivoDialogo { abierto = false }
}

private fun coords(geo: String): Pair<Double, Double>? {
    val t = geo.removePrefix("geo:").substringBefore(';').split(',')
    val la = t.getOrNull(0)?.toDoubleOrNull() ?: return null
    val lo = t.getOrNull(1)?.toDoubleOrNull() ?: return null
    return la to lo
}

@Composable
private fun ChamosVivoDialogo(cerrar: () -> Unit) {
    val ctx = LocalContext.current
    val room = ChamosVivo.room
    val manager = ChamosVivo.manager
    val scope = ChamosVivo.scope
    if (room == null || manager == null || scope == null) {
        AlertDialog(
            onDismissRequest = cerrar,
            confirmButton = { TextButton(onClick = cerrar) { androidx.compose.material3.Text("Cerrar") } },
            text = { androidx.compose.material3.Text("La ubicación en tiempo real no está disponible en este chat.") },
        )
        return
    }
    val compartiendo by manager.sharingRoomIds.collectAsState()
    val yo = room.roomId in compartiendo
    val shares by remember(room) { room.subscribeToLiveLocationShares() }.collectAsState(initial = emptyList<LiveLocationShare>())
    var pendiente by remember { mutableStateOf(0) }
    var siguiendo by remember { mutableStateOf<String?>(null) }

    fun iniciar(min: Int) {
        scope.launch {
            manager.startShare(room.roomId, min.minutes)
                .onSuccess { Toast.makeText(ctx, "Compartiendo tu ubicación en tiempo real", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(ctx, "No se pudo compartir: " + (it.message ?: "error"), Toast.LENGTH_LONG).show() }
        }
        cerrar()
    }

    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            iniciar(pendiente)
        } else {
            Toast.makeText(ctx, "Necesito el permiso de ubicación", Toast.LENGTH_LONG).show()
        }
    }
    fun pedir(min: Int) {
        pendiente = min
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.POST_NOTIFICATIONS)
        permisos.launch(lista.toTypedArray())
    }

    val info by room.roomInfoFlow.collectAsState()
    val personas = info.joinedMembersCount
    val cifrado = info.isEncrypted == true
    val ahora = System.currentTimeMillis()
    val activos = shares.filter { it.endTimestamp > ahora && it.lastLocation != null }
    val actual = siguiendo?.let { id -> activos.firstOrNull { it.userId.value == id } }

    if (siguiendo != null) {
        val punto = actual?.lastLocation?.geoUri?.let { coords(it) }
        val marca = actual?.lastLocation?.timestamp ?: 0L
        val webRef = remember { arrayOfNulls<WebView>(1) }
        // cada posición nueva mueve el punto en el mapa (sin recargar) y el mapa lo sigue
        androidx.compose.runtime.LaunchedEffect(punto, marca) {
            if (punto != null) webRef[0]?.evaluateJavascript("actualizar(${punto.first},${punto.second},$marca)", null)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { siguiendo = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.systemBarsPadding().fillMaxSize()) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        androidx.compose.material3.Text("📍 " + (siguiendo ?: ""), modifier = Modifier.weight(1f))
                        TextButton(onClick = { siguiendo = null }) { androidx.compose.material3.Text("Cerrar") }
                    }
                    if (punto == null) {
                        androidx.compose.material3.Text("Esta persona ya dejó de compartir su ubicación.", modifier = Modifier.padding(16.dp))
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            factory = { c ->
                                WebView(c).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = android.webkit.WebViewClient()
                                    webRef[0] = this
                                    loadDataWithBaseURL(
                                        "https://chamos.local/",
                                        ChamosVivoMapa.HTML.replace("LAT0", punto.first.toString()).replace("LON0", punto.second.toString()).replace("TS0", marca.toString()),
                                        "text/html", "utf-8", null,
                                    )
                                }
                            },
                        )
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = {
                                val miPunto = miUbicacion(ctx)
                                if (miPunto == null) {
                                    Toast.makeText(ctx, "No tengo tu ubicación; activa el permiso o toca el mapa para elegir el destino", Toast.LENGTH_LONG).show()
                                } else {
                                    webRef[0]?.evaluateJavascript("destino(${miPunto.first},${miPunto.second})", null)
                                }
                            }) { androidx.compose.material3.Text("Hasta mí") }
                            TextButton(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${punto.first},${punto.second}?q=${punto.first},${punto.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { androidx.compose.material3.Text("Abrir en mapas") }
                        }
                    }
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = cerrar,
        confirmButton = { TextButton(onClick = cerrar) { androidx.compose.material3.Text("Cerrar") } },
        title = { androidx.compose.material3.Text("Ubicación en tiempo real") },
        text = {
            Column {
                if (yo) {
                    androidx.compose.material3.Text("Estás compartiendo tu ubicación en este chat.")
                    TextButton(onClick = {
                        scope.launch { manager.stopShare(room.roomId) }
                        Toast.makeText(ctx, "Dejaste de compartir tu ubicación", Toast.LENGTH_SHORT).show()
                        cerrar()
                    }) { androidx.compose.material3.Text("Dejar de compartir") }
                } else {
                    androidx.compose.material3.Text(
                        "🔒 Solo las $personas personas de este chat podrán verla; no hay ningún enlace público ni nadie de afuera puede abrirla. " +
                            (if (cifrado) "Va cifrada." else "⚠️ Este chat no está cifrado.") +
                            (if (personas > 2) " Ojo: es un grupo, la verán todos." else ""),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    androidx.compose.material3.Text("Comparte dónde estás, y se actualiza solo mientras dure:")
                    for ((min, nombre) in listOf(15 to "15 minutos", 60 to "1 hora", 480 to "8 horas")) {
                        TextButton(onClick = { pedir(min) }) { androidx.compose.material3.Text(nombre) }
                    }
                }
                androidx.compose.material3.Text("Compartiendo ahora en este chat:", modifier = Modifier.padding(top = 12.dp))
                if (activos.isEmpty()) {
                    androidx.compose.material3.Text("Nadie por ahora.")
                } else {
                    for (s in activos) {
                        TextButton(onClick = { siguiendo = s.userId.value }) { androidx.compose.material3.Text("📍 " + s.userId.value) }
                    }
                }
            }
        },
    )
}

@android.annotation.SuppressLint("MissingPermission")
private fun miUbicacion(ctx: android.content.Context): Pair<Double, Double>? = runCatching {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { it.latitude to it.longitude }
}.getOrNull()

/** Mapa que sigue a la persona (OpenStreetMap + Leaflet) con distancia y tiempo aproximados hasta un destino. */
internal object ChamosVivoMapa {
    const val HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
<style>
html,body{margin:0;height:100%;background:#0B1026}#m{position:absolute;inset:0}
#i{position:absolute;left:8px;right:8px;bottom:12px;z-index:1000;background:rgba(11,16,38,.92);color:#fff;font:15px sans-serif;padding:10px 14px;border-radius:14px;border:2px solid #22D3EE}
#s{position:absolute;right:10px;top:10px;z-index:1000;background:#1A73E8;color:#fff;font:14px sans-serif;padding:8px 14px;border-radius:20px;display:none}
</style></head><body><div id="m"></div><div id="s">Seguir</div><div id="i">Toca el mapa para elegir el destino</div>
<script>
var la0=LAT0,lo0=LON0,hist=[[la0,lo0,TS0]],seguir=true,dest=null,destM=null,anim=null;
var map=L.map('m').setView([la0,lo0],16);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
var persona=L.circleMarker([la0,lo0],{radius:9,color:'#fff',weight:3,fillColor:'#1A73E8',fillOpacity:1}).addTo(map);
var ruta=L.polyline([[la0,lo0]],{color:'#1A73E8',weight:4}).addTo(map);
var lineaD=L.polyline([],{color:'#EF4444',weight:3,dashArray:'6 8'}).addTo(map);
var btn=document.getElementById('s'),info=document.getElementById('i');
function km(a,b,c,d){var R=6371,r=Math.PI/180,x=(c-a)*r,y=(d-b)*r;var h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(a*r)*Math.cos(c*r)*Math.sin(y/2)*Math.sin(y/2);return 2*R*Math.asin(Math.sqrt(h));}
function actual(){return hist[hist.length-1];}
function velocidad(){var a=hist[0],b=actual();var h=(b[2]-a[2])/3600000;if(hist.length<2||h<=0)return 0;return km(a[0],a[1],b[0],b[1])/h;}
function texto(){var p=actual();if(!dest){info.textContent='Toca el mapa para elegir el destino';return;}
var d=km(p[0],p[1],dest[0],dest[1]),v=velocidad(),usa=v>=5?v:40,calle=d*1.3,min=Math.round(calle/usa*60);
var t=min<1?'menos de 1 min':(min<60?min+' min':Math.floor(min/60)+' h '+(min%60)+' min');
info.textContent='A '+(d<1?Math.round(d*1000)+' m':d.toFixed(1)+' km')+' en línea recta · llega en ≈ '+t+(v>=5?' (a '+Math.round(v)+' km/h)':' (calculado a 40 km/h)')+' · aproximado';
if(destM)lineaD.setLatLngs([[p[0],p[1]],dest]);}
function actualizar(la,lo,t){var a=actual();if(a[0]===la&&a[1]===lo&&a[2]===t)return;hist.push([la,lo,t]);if(hist.length>8)hist.shift();
ruta.addLatLng([la,lo]);var d0=a,t0=Date.now();if(anim)cancelAnimationFrame(anim);
function paso(){var f=Math.min(1,(Date.now()-t0)/1000),x=d0[0]+(la-d0[0])*f,y=d0[1]+(lo-d0[1])*f;persona.setLatLng([x,y]);if(seguir)map.panTo([x,y],{animate:false});if(f<1)anim=requestAnimationFrame(paso);}
paso();texto();}
function destino(la,lo){dest=[la,lo];if(destM)destM.setLatLng(dest);else destM=L.marker(dest).addTo(map);texto();}
map.on('click',function(e){destino(e.latlng.lat,e.latlng.lng);});
map.on('dragstart',function(){seguir=false;btn.style.display='block';});
btn.onclick=function(){seguir=true;btn.style.display='none';var p=actual();map.panTo([p[0],p[1]]);};
</script></body></html>"""
}
