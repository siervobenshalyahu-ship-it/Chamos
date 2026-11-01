# Chamos Cuentas (servidor gratis de números)

Vincula el número de teléfono de cada usuario con su cuenta de Matrix y deja encontrar contactos por número.
El número se verifica con un bot de Telegram (sin SMS, sin costo). Corre en Cloudflare Workers, plan gratis.

## Qué hace y qué no
- La cuenta sigue siendo de matrix.org. Aquí solo se guarda "número ↔ cuenta", y el número va cifrado (nunca en claro).
- Hace falta que el usuario tenga Telegram para verificar su número.
- No crea cuentas en matrix.org ni permite iniciar sesión sin una cuenta de Matrix. Para eso haría falta un servidor Matrix propio.

## Instalación (una sola vez)
1. **Bot:** en Telegram abre @BotFather, escribe `/newbot` y guarda el token y el nombre (sin @).
2. **Cloudflare:** desde esta carpeta (`cuentas/`):
   ```
   npx wrangler login
   npx wrangler kv namespace create CUENTAS      # copia el id en wrangler.toml
   ```
   En `wrangler.toml` cambia `TG_BOT` (nombre del bot) e `id` (el del KV).
3. **Secretos** (escribe un texto largo al azar para `TG_SECRET` y `PEPPER`; guarda el PEPPER, si lo pierdes se pierden las vinculaciones):
   ```
   npx wrangler secret put TG_TOKEN
   npx wrangler secret put TG_SECRET
   npx wrangler secret put PEPPER
   ```
4. **Publicar:** `npx wrangler deploy` (te da una dirección como `https://chamos-cuentas.tuusuario.workers.dev`).
5. **Conectar Telegram con el servidor** (cambia los datos):
   ```
   curl "https://api.telegram.org/bot<TG_TOKEN>/setWebhook" \
     -d url=https://chamos-cuentas.tuusuario.workers.dev/telegram \
     -d secret_token=<TG_SECRET>
   ```

## Probar sin Cloudflare
`node cuentas/prueba.mjs`

## API para la app
Todas las llamadas llevan `openid`: el token OpenID que la app pide a matrix.org
(`POST /_matrix/client/v3/user/{userId}/openid/request_token`). Así el servidor sabe quién es el usuario sin que nadie pueda hacerse pasar por otro.

| Llamada | Para qué |
|---|---|
| `POST /vincular/iniciar` `{openid}` | Devuelve `{token, enlace}`. La app abre el `enlace` (el bot de Telegram). |
| `GET /vincular/estado?token=` | `pendiente`, `verificado` o `expirado`. La app lo consulta cada 2 segundos. |
| `POST /mi/numero` `{openid}` | Dice si ya hay número vinculado (muestra solo `•••• 67`). |
| `POST /mi/desvincular` `{openid}` | Quita el número. |
| `POST /contactos/buscar` `{openid, numeros:[...]}` | Hasta 500 números de la agenda; devuelve cuáles ya usan Chamos y su cuenta. |

Nota: la dirección de federación de matrix.org (`matrix-federation.matrix.org`) puede cambiar; si la verificación falla con 401, ajusta `FEDERATION_URL` en `wrangler.toml`.

## Conectar la app
Cuando el Worker esté publicado, escribe su dirección (una sola línea, con `https://`) en `cuentas/servicio-url.txt`,
sube el cambio y genera una versión nueva con la acción "Chamos 2.0". Mientras ese archivo esté vacío, la pantalla
«Número» de la app dice que el servidor todavía no está activado.

En la app: botón **Número** (junto a Ajustes y Estilos) → *Vincular con Telegram* → compartir contacto en el bot → *Buscar mis contactos en Chamos*.
Los números de la agenda que se guardaron sin código de país se toman como venezolanos (+58); se cambia en `patches/ChamosNumero.kt` (`PAIS`).
