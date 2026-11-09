# Ubicacion en tiempo real de Chamos (propia, sin claves de mapas): opcion en el menu de adjuntos del chat.
# Uso: python3 ubicacion_vivo.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer")
shutil.copy(os.path.join(pdir, "ChamosVivo.kt"), os.path.join(d, "ChamosVivo.kt"))

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

edit(os.path.join(d, "MessageComposerPresenter.kt"), [
    ("    private val locationService: LocationService,\n",
     "    private val locationService: LocationService,\n    private val chamosVivoManager: io.element.android.features.location.api.live.ActiveLiveLocationShareManager,\n"),
    ("        val canShareLocation by remember { locationService.isServiceAvailable() }.collectAsState()\n",
     "        val canShareLocation by remember { locationService.isServiceAvailable() }.collectAsState()\n"
     "        androidx.compose.runtime.SideEffect { ChamosVivo.vincular(room, chamosVivoManager, sessionCoroutineScope) }\n"),
])
edit(os.path.join(d, "AttachmentsBottomSheet.kt"), [
    ("        ListItem(\n            modifier = Modifier.clickable {\n                state.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)\n",
     "        ChamosVivoItem()\n        ListItem(\n            modifier = Modifier.clickable {\n                state.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)\n"),
])
print("ubicacion_vivo OK")
