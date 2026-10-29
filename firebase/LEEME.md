# Notificaciones de Chamos (Firebase)

Para activarlas hacen falta dos archivos en esta carpeta:

1. `google-services.json` — el que descargas de Firebase (paquete `com.chamos.app.debug`). No es secreto.
2. `gateway-url.txt` — una sola línea con la dirección de tu gateway de Cloudflare, por ejemplo
   `https://chamos-push.tuusuario.workers.dev`

Cuando los dos existen, la compilación usa la versión con Google Play y se conecta a tu Firebase.
Mientras falte alguno, se compila la versión de antes.

**Nunca subas aquí la clave de cuenta de servicio** (`...-firebase-adminsdk-....json`): esa va solo en Cloudflare,
como secreto `FCM_SERVICE_ACCOUNT`. El código del gateway está en `push-gateway/worker.js`.
