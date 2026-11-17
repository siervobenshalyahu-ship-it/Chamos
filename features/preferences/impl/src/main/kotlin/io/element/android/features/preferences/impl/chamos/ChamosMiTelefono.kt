package io.element.android.features.preferences.impl.chamos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Chamos: "Mi teléfono". Si la persona lo activa, este teléfono guarda en el servidor de Chamos su última
 * ubicación (y la batería) cada ~15 minutos, para poder verla desde otro dispositivo con la misma cuenta.
 * Viene apagado. El teléfono usa un secreto propio que solo sirve para reportar su ubicación.
 */
object ChamosMiTelefono {
    private const val BASE = "https://chamos-push.siervodeyhwh57.workers.dev/telefono"
    private const val PREFS = "chamos_mi_telefono"
    private const val TRABAJO = "chamos_mi_telefono_trabajo"

    private var client: MatrixClient? = null
    private var sesiones: SessionStore? = null

    fun attach(c: MatrixClient, s: SessionStore) {
        client = c
        sesiones = s
    }

    data class Telefono(val id: String, val nombre: String, val lat: Double, val lon: Double, val precision: Double, val bateria: Int, val ts: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activado(ctx: Context): Boolean = prefs(ctx).getBoolean("activado", false)

    fun idLocal(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }

    fun tienePermiso(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun tienePermisoSiempre(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun token(): String? {
        val c = client ?: return null
        return sesiones?.getSession(c.sessionId.value)?.accessToken
    }

    private fun llamar(metodo: String, ruta: String, cuerpo: JSONObject?, token: String?): Pair<Int, JSONObject?> {
        val con = URL(BASE + ruta).openConnection() as HttpURLConnection
        return try {
            con.requestMethod = metodo
            con.connectTimeout = 15000
            con.readTimeout = 15000
            if (token != null) con.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                con.doOutput = true
                con.setRequestProperty("content-type", "application/json")
                con.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to runCatching { JSONObject(txt) }.getOrNull()
        } catch (e: Exception) {
            -1 to null
        } finally {
            con.disconnect()
        }
    }

    /** Activa: registra este teléfono en la cuenta, guarda el secreto y programa el aviso cada ~15 minutos. */
    suspend fun activar(ctx: Context): String? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext "No hay sesión abierta."
        val nombre = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60)
        val (code, j) = llamar("POST", "/activar", JSONObject().put("id", idLocal(ctx)).put("nombre", nombre), t)
        if (code != 200 || j == null) {
            return@withContext when (code) {
                -1 -> "Sin conexión. Intenta de nuevo."
                409 -> "Este teléfono ya está en otra cuenta."
                429 -> "Ya tienes 5 teléfonos. Quita uno primero."
                else -> "No se pudo activar (código $code)."
            }
        }
        prefs(ctx).edit().putString("secreto", j.optString("secreto")).putBoolean("activado", true).apply()
        programar(ctx)
        reportarAhora(ctx)
        null
    }

    suspend fun desactivar(ctx: Context): String? = withContext(Dispatchers.IO) {
        WorkManager.getInstance(ctx).cancelUniqueWork(TRABAJO)
        val t = token()
        if (t != null) llamar("POST", "/desactivar", JSONObject().put("id", idLocal(ctx)), t)
        prefs(ctx).edit().remove("secreto").putBoolean("activado", false).apply()
        null
    }

    fun programar(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ReportarWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    suspend fun lista(): List<Telefono>? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext null
        val (code, j) = llamar("GET", "/lista", null, t)
        if (code != 200 || j == null) return@withContext null
        val a = j.optJSONArray("telefonos") ?: return@withContext emptyList()
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Telefono(o.optString("device_id"), o.optString("nombre"), o.optDouble("lat", 0.0), o.optDouble("lon", 0.0),
                o.optDouble("precision", 0.0), o.optInt("bateria", 0), o.optLong("ts", 0))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ubicacion(ctx: Context): Location? {
        if (!tienePermiso(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val proveedores = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) || it == LocationManager.PASSIVE_PROVIDER }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in proveedores.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                val l = withTimeoutOrNull(15000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cs = CancellationSignal()
                        cont.invokeOnCancellation { cs.cancel() }
                        runCatching {
                            lm.getCurrentLocation(p, cs, ContextCompat.getMainExecutor(ctx)) { cont.resume(it) }
                        }.onFailure { cont.resume(null) }
                    }
                }
                if (l != null) return l
            }
        }
        return proveedores.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 6 * 3600 * 1000 }
            .maxByOrNull { it.time }
    }

    /** Manda la ubicación actual (o la última conocida) al servidor. Devuelve el mensaje de alarma si el dueño la pidió. */
    suspend fun reportar(ctx: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val secreto = p.getString("secreto", null) ?: return@withContext null
        if (!p.getBoolean("activado", false)) return@withContext null
        val loc = ubicacion(ctx)
        val cuerpo = JSONObject().put("id", idLocal(ctx)).put("secreto", secreto)
        if (loc == null) {
            // Sin GPS o sin permiso: no hay ubicación nueva, pero igual se pregunta si hay alarma pendiente.
            cuerpo.put("sin_gps", true)
        } else {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            cuerpo.put("lat", loc.latitude).put("lon", loc.longitude).put("prec", loc.accuracy.toDouble())
                .put("bat", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }
        val (code, j) = llamar("POST", "/reportar", cuerpo, null)
        if (code != 200 || j == null || j.isNull("alarma") || !j.has("alarma")) null else j.optString("alarma").ifBlank { null }
    }

    /** Usado al activar: manda la primera ubicación (ignora cualquier alarma). */
    suspend fun reportarAhora(ctx: Context) {
        reportar(ctx)
    }

    /** Hace sonar la alarma del teléfono ~60 segundos y muestra el mensaje del dueño. */
    suspend fun sonarAlarma(ctx: Context, mensaje: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel("chamos_alarma", "Teléfono perdido", android.app.NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, "chamos_alarma")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Este teléfono está perdido")
            .setContentText(mensaje)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(mensaje))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setOngoing(false)
            .build()
        runCatching { nm.notify(7771, notif) }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val volumenAntes = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
        var mp: android.media.MediaPlayer? = null
        try {
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0)
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            mp = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
            kotlinx.coroutines.delay(60_000)
        } catch (_: Exception) {
        } finally {
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, volumenAntes, 0) }
        }
    }

    fun abrirMapa(ctx: Context, t: Telefono) {
        val uri = android.net.Uri.parse("geo:${t.lat},${t.lon}?q=${t.lat},${t.lon}(${android.net.Uri.encode(t.nombre)})")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

class ReportarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val alarma = ChamosMiTelefono.reportar(applicationContext)
        if (alarma != null) ChamosMiTelefono.sonarAlarma(applicationContext, alarma)
        return Result.success()
    }
}
