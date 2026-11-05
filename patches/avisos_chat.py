# Chat: (1) "mensaje borrado" se puede quitar de la pantalla; (2) el botón de reproducir de un audio recibido lleva un aro de neón
# parpadeante: verde (nuevo), violeta (ya abrió el chat), azul (ya lo reprodujo). Uso: python3 avisos_chat.py RAIZ
import sys, os, shutil, re
root = sys.argv[1]
tl = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline")
ev = os.path.join(tl, "components/event")
here = os.path.dirname(os.path.abspath(__file__))
shutil.copy(os.path.join(here, "ChamosAvisosChat.kt"), os.path.join(ev, "ChamosAvisosChat.kt"))

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) fila del timeline: si es un mensaje borrado ya quitado, no se dibuja
fila = os.path.join(tl, "components/TimelineItemRow.kt")
edit(fila, [
    ("    modifier: Modifier = Modifier,\n    eventContentView: @Composable (TimelineItem.Event, Modifier, (ContentAvoidingLayoutData) -> Unit) -> Unit =\n        { event, contentModifier, onContentLayoutChange ->\n            TimelineItemEventContentView(\n                eventId = event.eventId,\n",
     "    modifier: Modifier = Modifier,\n    eventContentView: @Composable (TimelineItem.Event, Modifier, (ContentAvoidingLayoutData) -> Unit) -> Unit =\n        { event, contentModifier, onContentLayoutChange ->\n            TimelineItemEventContentView(\n                eventId = event.eventId,\n                esMio = event.isMine,\n"),
    (") {\n    val isSelectionModeActive = selectionData.isSelectionModeActive\n",
     ") {\n    if (timelineItem is TimelineItem.Event &&\n        timelineItem.content is io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent &&\n        io.element.android.features.messages.impl.timeline.components.event.ChamosAvisosChat.oculto(timelineItem.eventId?.value)\n    ) {\n        return\n    }\n    val isSelectionModeActive = selectionData.isSelectionModeActive\n"),
])

# 2) contenido: pasa el id al borrado y si es mío al audio
cv = os.path.join(ev, "TimelineItemEventContentView.kt")
edit(cv, [
    ("    modifier: Modifier = Modifier,\n    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},\n) {\n    val hideMediaContent",
     "    modifier: Modifier = Modifier,\n    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},\n    esMio: Boolean = false,\n) {\n    val hideMediaContent"),
    ("                is TimelineItemRedactedContent -> TimelineItemRedactedView(\n                    content = content,\n",
     "                is TimelineItemRedactedContent -> TimelineItemRedactedView(\n                    content = content,\n                    eventId = eventId,\n"),
    ("                        contentValidationValue = overallValidationState,\n                    )\n                }\n                is TimelineItemRtcNotificationContent",
     "                        contentValidationValue = overallValidationState,\n                        esMio = esMio,\n                    )\n                }\n                is TimelineItemRtcNotificationContent"),
])

# 3) vista del borrado con "Quitar"
rv = os.path.join(ev, "TimelineItemRedactedView.kt")
edit(rv, [
    ("import androidx.compose.runtime.Composable\n",
     "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.width\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.runtime.Composable\nimport io.element.android.compound.theme.ElementTheme\nimport io.element.android.libraries.designsystem.theme.components.Text\nimport io.element.android.libraries.matrix.api.core.EventId\n"),
    ("    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,\n    modifier: Modifier = Modifier\n) {\n    TimelineItemInformativeView(\n        text = stringResource(id = CommonStrings.common_message_removed),\n        iconDescription = stringResource(id = CommonStrings.common_message_removed),\n        iconResourceId = CompoundDrawables.ic_compound_delete,\n        onContentLayoutChange = onContentLayoutChange,\n        modifier = modifier\n    )\n}",
     "    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,\n    modifier: Modifier = Modifier,\n    eventId: EventId? = null,\n) {\n    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {\n        TimelineItemInformativeView(\n            text = stringResource(id = CommonStrings.common_message_removed),\n            iconDescription = stringResource(id = CommonStrings.common_message_removed),\n            iconResourceId = CompoundDrawables.ic_compound_delete,\n            onContentLayoutChange = onContentLayoutChange,\n        )\n        if (eventId != null) {\n            Spacer(modifier = Modifier.width(10.dp))\n            Text(\n                text = \"✕ Quitar\",\n                color = ElementTheme.colors.textActionAccent,\n                style = ElementTheme.typography.fontBodyMdRegular,\n                modifier = Modifier\n                    .clickable { ChamosAvisosChat.ocultar(eventId.value) }\n                    .padding(vertical = 4.dp),\n            )\n        }\n    }\n}"),
])

# 4) audio con aro de neón
vv = os.path.join(ev, "TimelineItemVoiceView.kt")
edit(vv, [
    ("    contentValidationValue: ContentValidationValue,\n    modifier: Modifier = Modifier,\n) {\n    fun playPause() {",
     "    contentValidationValue: ContentValidationValue,\n    modifier: Modifier = Modifier,\n    esMio: Boolean = false,\n) {\n    val idAudio = content.eventId?.value\n    val estadoAudio = ChamosAvisosChat.estadoAudio(idAudio)\n    LaunchedEffect(idAudio) {\n        if (idAudio != null) {\n            delay(3000L)\n            ChamosAvisosChat.marcarVisto(idAudio)\n        }\n    }\n    LaunchedEffect(state.isPlaying) {\n        if (state.isPlaying && idAudio != null) ChamosAvisosChat.marcarReproducido(idAudio)\n    }\n    fun playPause() {"),
    ("                    VoiceMessageState.ButtonType.Play -> PlayButton(onClick = ::playPause)\n                    VoiceMessageState.ButtonType.Pause -> PauseButton(onClick = ::playPause)\n",
     "                    VoiceMessageState.ButtonType.Play -> if (esMio || idAudio == null) PlayButton(onClick = ::playPause) else AroAudioNeon(estadoAudio) { PlayButton(onClick = ::playPause) }\n                    VoiceMessageState.ButtonType.Pause -> if (esMio || idAudio == null) PauseButton(onClick = ::playPause) else AroAudioNeon(2) { PauseButton(onClick = ::playPause) }\n"),
])
print("avisos_chat: listo")
