# Palomitas de neón en mis mensajes (que no son audio) y luz de neón en mis audios según el estado de entrega.
# Uso: python3 palomitas.py RAIZ CARPETA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
base = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl")
tl = os.path.join(base, "timeline")
shutil.copy(os.path.join(pdir, "ChamosPalomitas.kt"), os.path.join(tl, "components/ChamosPalomitas.kt"))

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:90], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) hora + palomitas en la burbuja
edit(os.path.join(tl, "components/TimelineEventTimestampView.kt"), [
    ("        Text(\n            formattedTime,\n            style = ElementTheme.typography.fontBodyXsRegular,\n            color = tint,\n        )\n        if (hasError) {",
     "        Text(\n            formattedTime,\n            style = ElementTheme.typography.fontBodyXsRegular,\n            color = tint,\n        )\n"
     "        ChamosEnvio.anotar(event)\n"
     "        if (!ChamosEnvio.esAudio(event)) ChamosPalomitas(ChamosEnvio.estado(event))\n"
     "        if (hasError) {"),
])

# 2) si la otra persona escribe, las palomitas pasan a azul; al salir del chat se reinicia
edit(os.path.join(base, "typing/TypingNotificationPresenter.kt"), [
    ("        return TypingNotificationState(\n            renderTypingNotifications = renderTypingNotifications,",
     "        androidx.compose.runtime.LaunchedEffect(typingMembersState) {\n"
     "            io.element.android.features.messages.impl.timeline.components.ChamosEnvio.escribiendo.value = typingMembersState.isNotEmpty()\n"
     "        }\n"
     "        androidx.compose.runtime.DisposableEffect(Unit) {\n"
     "            onDispose { io.element.android.features.messages.impl.timeline.components.ChamosEnvio.reiniciar() }\n"
     "        }\n"
     "        return TypingNotificationState(\n            renderTypingNotifications = renderTypingNotifications,"),
])

# 3) el audio: pasa el estado de entrega hasta la vista de voz
fila = os.path.join(tl, "components/TimelineItemRow.kt")
edit(fila, [
    ("                esMio = event.isMine,\n",
     "                esMio = event.isMine,\n                estadoEnvio = io.element.android.features.messages.impl.timeline.components.ChamosEnvio.estado(event),\n"),
])
ev = os.path.join(tl, "components/event")
edit(os.path.join(ev, "TimelineItemEventContentView.kt"), [
    ("    esMio: Boolean = false,\n) {\n    val hideMediaContent", "    esMio: Boolean = false,\n    estadoEnvio: Int = -1,\n) {\n    val hideMediaContent"),
    ("                        esMio = esMio,\n", "                        esMio = esMio,\n                        estadoEnvio = estadoEnvio,\n"),
])
edit(os.path.join(ev, "TimelineItemVoiceView.kt"), [
    ("    esMio: Boolean = false,\n) {\n    val idAudio", "    esMio: Boolean = false,\n    estadoEnvio: Int = -1,\n) {\n    val idAudio"),
    ("if (esMio || idAudio == null) PlayButton(onClick = ::playPause) else AroAudioNeon(estadoAudio) { PlayButton(onClick = ::playPause) }",
     "if (esMio && estadoEnvio >= 0) AroAudioNeon(10 + estadoEnvio) { PlayButton(onClick = ::playPause) } else if (esMio || idAudio == null) PlayButton(onClick = ::playPause) else AroAudioNeon(estadoAudio) { PlayButton(onClick = ::playPause) }"),
    ("if (esMio || idAudio == null) PauseButton(onClick = ::playPause) else AroAudioNeon(2) { PauseButton(onClick = ::playPause) }",
     "if (esMio && estadoEnvio >= 0) AroAudioNeon(10 + estadoEnvio) { PauseButton(onClick = ::playPause) } else if (esMio || idAudio == null) PauseButton(onClick = ::playPause) else AroAudioNeon(2) { PauseButton(onClick = ::playPause) }"),
])
edit(os.path.join(ev, "ChamosAvisosChat.kt"), [
    ("    val color = when (estado) {\n        2 -> Color(0xFF3D7BFF)",
     "    val color = when (estado) {\n        10 -> Color(0xFFFFC107)\n        11 -> Color(0xFF00FF9C)\n        12 -> Color(0xFFB388FF)\n        13 -> Color(0xFF3D9BFF)\n        2 -> Color(0xFF3D7BFF)"),
])
print("palomitas: listo")
