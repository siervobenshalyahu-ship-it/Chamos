# Traductor por sala (ML Kit, en el telefono): interruptor en la barra de arriba del chat, traduccion de lo que llega
# (debajo del mensaje) y de lo que escribes (sale en el idioma elegido). Uso: python3 traductor.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
m = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl")
shutil.copy(os.path.join(pdir, "ChamosTraductor.kt"), os.path.join(m, "messagecomposer/ChamosTraductor.kt"))

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# dependencias (el grupo com.google.mlkit ya esta permitido por fuentes_stickers.py)
edit(os.path.join(root, "features/messages/impl/build.gradle.kts"), [
    ('    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")\n',
     '    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")\n'
     '    implementation("com.google.mlkit:translate:17.0.3")\n'
     '    implementation("com.google.mlkit:language-id:17.0.6")\n'),
])
# interruptor arriba en cada sala
edit(os.path.join(m, "topbars/MessagesViewTopBar.kt"), [
    ("        actions = menuActions,\n",
     "        actions = {\n            io.element.android.features.messages.impl.messagecomposer.ChamosTraductorBoton()\n            menuActions()\n        },\n"),
])
# lo que llega: traduccion debajo del texto
tv = os.path.join(m, "timeline/components/event/TimelineItemTextView.kt")
edit(tv, [("@Composable\nfun TimelineItemTextView(\n    content: TimelineItemTextBasedContent,",
           "@Composable\nfun TimelineItemTextViewOriginal(\n    content: TimelineItemTextBasedContent,")])
with open(tv, "a", encoding="utf-8") as f:
    f.write('''
@Composable
fun TimelineItemTextView(
    content: TimelineItemTextBasedContent,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    androidx.compose.foundation.layout.Column(modifier) {
        TimelineItemTextViewOriginal(
            content = content,
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            onContentLayoutChange = onContentLayoutChange,
        )
        io.element.android.features.messages.impl.messagecomposer.ChamosTraduccion(content.plainText)
    }
}
''')
# lo que escribes: sale traducido
edit(os.path.join(m, "messagecomposer/MessageComposerPresenter.kt"), [
    ("        resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = capturedMode is MessageComposerMode.Edit)\n        when (capturedMode) {\n",
     "        resetComposer(markdownTextEditorState, richTextEditorState, fromEdit = capturedMode is MessageComposerMode.Edit)\n"
     "        val chamosTraducido = if (capturedMode is MessageComposerMode.Normal || capturedMode is MessageComposerMode.Reply) {\n"
     "            ChamosTraductor.traducirSaliente(room.roomId.value, message.markdown)\n"
     "        } else {\n"
     "            null\n"
     "        }\n"
     "        when (capturedMode) {\n"),
    ("            is MessageComposerMode.Normal -> timelineController.invokeOnCurrentTimeline {\n                sendMessage(\n                    body = message.markdown,\n                    htmlBody = message.html,\n",
     "            is MessageComposerMode.Normal -> timelineController.invokeOnCurrentTimeline {\n                sendMessage(\n                    body = chamosTraducido ?: message.markdown,\n                    htmlBody = if (chamosTraducido != null) null else message.html,\n"),
    ("                        replyMessage(\n                            body = message.markdown,\n                            htmlBody = message.html,\n",
     "                        replyMessage(\n                            body = chamosTraducido ?: message.markdown,\n                            htmlBody = if (chamosTraducido != null) null else message.html,\n"),
])
print("traductor OK")
