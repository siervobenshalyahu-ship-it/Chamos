# Bot de Reels de Chamos

Publica videos en los Reels de la app (sala `#chamos-reels:matrix.org`) sin que tengas un servidor:
corre en GitHub Actions dos veces al día y publica el próximo video de la cola.

## Puesta en marcha (una sola vez)
1. **Crea la cuenta del bot** en Chamos o en https://app.element.io con un correo nuevo (por ejemplo `chamos_oficial`).
   Ponle nombre y foto de perfil.
2. **Saca su token**: en Element web, Ajustes → Ayuda y acerca de → Avanzado → Token de acceso. Cópialo.
   (No cierres sesión en esa web después, porque invalida el token: cierra la pestaña sin salir.)
3. En GitHub: repositorio Chamos → Settings → Secrets and variables → Actions → New repository secret:
   nombre `MATRIX_TOKEN`, valor el token.
4. Asegúrate de que la sala de Reels exista: abre Chamos con una cuenta de matrix.org y entra a Reels una vez.

## Agregar videos
1. Sube los videos (mp4, máximo 50 MB, verticales) a la carpeta `bot/videos/`.
2. Anótalos en `bot/cola.json`, en el orden en que quieres que salgan:

```json
[
  {"archivo": "videos/humor1.mp4", "descripcion": "Así se saluda en Venezuela 😂 #chamos"},
  {"archivo": "videos/comida.mp4", "descripcion": "Arepa de domingo"}
]
```
El bot marca cada uno como publicado solo. Para publicar uno ya mismo: Actions → "Bot de Reels de Chamos" → Run workflow.

## Cuidado
- Usa solo videos tuyos, libres de derechos o de gente que te dio permiso.
- Mantén un ritmo normal (1 o 2 al día) para que matrix.org no limite la cuenta.
- Cuando la app ya tenga usuarios activos, borra el secreto `MATRIX_TOKEN` o desactiva el workflow y el bot se detiene.
