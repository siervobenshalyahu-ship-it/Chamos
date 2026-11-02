# Bots de Reels de Chamos

Tres cuentas que publican videos en los Reels (`#chamos-reels:matrix.org`) sin servidor: GitHub Actions corre
dos veces al día (12:00 y 19:00 hora de Venezuela) y cada cuenta publica el próximo video de su cola.

| Carpeta | Nombre público | Tema | Secreto en GitHub |
|---|---|---|---|
| `cuentas/raul` | Chamo Raúl | Salsa baúl | `MATRIX_TOKEN_RAUL` |
| `cuentas/nery` | Chama Nery | Belleza | `MATRIX_TOKEN_NERY` |
| `cuentas/maria` | Chama María | Naturaleza | `MATRIX_TOKEN_MARIA` |

## Puesta en marcha (una sola vez, por cada cuenta)
1. Crea la cuenta en https://app.element.io (servidor matrix.org) con un correo distinto para cada una
   (por ejemplo `chamo_raul`, `chama_nery`, `chama_maria`). Ponle el nombre público de la tabla y una foto.
2. Saca su token: Element web → Ajustes → Ayuda y acerca de → Avanzado → Token de acceso. Cópialo y cierra la pestaña
   **sin** cerrar sesión (salir invalida el token). Usa una ventana de incógnito distinta para cada cuenta.
3. GitHub → repositorio Chamos → Settings → Secrets and variables → Actions → New repository secret:
   el nombre de la tabla y el token como valor.
4. La sala de Reels debe existir: abre Chamos con una cuenta de matrix.org y entra a Reels una vez.

Una cuenta sin secreto simplemente se omite, así que puedes empezar con una y sumar las otras.

## Agregar videos
1. Sube los videos (mp4, vertical, máximo 50 MB) a `bot/cuentas/<cuenta>/videos/`.
2. Anótalos en `bot/cuentas/<cuenta>/cola.json`, en el orden de salida:

```json
[
  {"archivo": "videos/salsa1.mp4", "descripcion": "Así se baila la salsa baúl 🎺 #salsa"}
]
```
El bot marca cada uno como publicado. Para publicar ya: Actions → "Bot de Reels de Chamos" → Run workflow.

## Cuidado
- Usa solo videos tuyos, libres de derechos o con permiso (la música también cuenta: mejor salsa libre de derechos o de tu autoría).
- Ritmo normal: 1 o 2 videos al día por cuenta, para que matrix.org no limite las cuentas.
- Para detener un bot, borra su secreto. Para detenerlos todos, desactiva el workflow.
- Los Reels de Chamos solo muestran videos; las fotos no aparecen ahí.
