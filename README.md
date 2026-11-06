# Chamos

App de mensajes hecha para venezolanos: chats, Estados, Videos, Chamo Muro, juegos y más.
Es una versión modificada (fork) de [Element X Android](https://github.com/element-hq/element-x-android),
publicada bajo **AGPL-3.0** (ver [LICENSE](LICENSE) y [NOTICE](NOTICE)).

- Descargar la app: https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/
- Política de privacidad: https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/privacidad.html

## Código fuente
Este repositorio contiene todo lo que Chamos añade o cambia sobre Element X:

| Carpeta | Qué es |
|---|---|
| `patches/` | Los parches (Python y Kotlin) que convierten Element X en Chamos |
| `assets/` | Íconos, portadas y fondos |
| `.github/workflows/build-chamos-2.yml` | La receta de compilación: baja Element X, aplica los parches en orden y firma el APK |
| `push-gateway/` | Servidor de notificaciones y de videos (Cloudflare Workers) |
| `cuentas/` | Servidor de números (Cloudflare Workers) |
| `bot/` | Bots que publican videos |
| `firebase/` | Configuración pública de notificaciones |

## Cómo compilar
La compilación se hace con GitHub Actions: Actions → **build-chamos-2** → *Run workflow*.
Para firmar con tu propia llave, crea los secretos `CHAMOS_KEYSTORE_B64` y los demás que pide el workflow; sin ellos se firma con la llave de depuración.
El APK sale como *Release* del repositorio.

## Licencia
AGPL-3.0, igual que Element X. Si distribuyes una versión modificada, debes publicar su código fuente.
