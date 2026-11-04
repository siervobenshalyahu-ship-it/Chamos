# Reels de Chamos: pantalla de videos verticales (estilo TikTok) dentro de la app.
# Uso: python3 reels.py RAIZ_DEL_REPO CARPETA_PATCHES   (se ejecuta después de home_estados.py)
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosReels.kt"), os.path.join(d, "ChamosReels.kt"))
shutil.copy(os.path.join(pdir, "ChamosMuro.kt"), os.path.join(d, "ChamosMuro.kt"))
shutil.copy(os.path.join(pdir, "ChamosGrafiti.kt"), os.path.join(d, "ChamosGrafiti.kt"))
# letrero de la Red de Chamos (fondo del cuadro para publicar)
res = os.path.join(root, "features/home/impl/src/main/res/drawable-nodpi")
os.makedirs(res, exist_ok=True)
shutil.copy(os.path.join(pdir, "..", "assets", "muro_banner.jpg"), os.path.join(res, "chamos_muro_banner.jpg"))
shutil.copy(os.path.join(pdir, "..", "assets", "muro_robot.jpg"), os.path.join(res, "chamos_muro_robot.jpg"))
shutil.copy(os.path.join(pdir, "..", "assets", "mercado_banner.jpg"), os.path.join(res, "chamos_mercado_banner.jpg"))
shutil.copy(os.path.join(pdir, "..", "assets", "muro_portada.jpg"), os.path.join(res, "chamos_red_portada.jpg"))
shutil.copy(os.path.join(pdir, "..", "assets", "mercado_portada.jpg"), os.path.join(res, "chamos_mercado_portada.jpg"))

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# estado
edit(os.path.join(d, "HomeState.kt"), [
    ("    val chamosEstados: ChamosEstadosState? = null,\n) {",
     "    val chamosEstados: ChamosEstadosState? = null,\n    val chamosReels: ChamosReelsState? = null,\n) {"),
])
# presenter
edit(os.path.join(d, "HomePresenter.kt"), [
    ("        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()\n",
     "        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()\n"
     "        val chamosReels = rememberChamosReels(client, roomListDataSource, mediaSenderFactory, mediaConfigFactory)\n"),
    ("            chamosEstados = chamosEstados,\n        )",
     "            chamosEstados = chamosEstados,\n            chamosReels = chamosReels,\n        )"),
])
# la sala de Reels no sale en la lista de chats
edit(os.path.join(d, "datasource/RoomListDataSource.kt"), [
    ("        .map { list -> list.filterNot { it.isChamosEstado() }.toImmutableList() }\n",
     "        .map { list -> list.filterNot { it.isChamosEstado() || it.isChamosReels() || it.isChamosMuro() || it.isChamosAvisos() }.toImmutableList() }\n"),
    ("import io.element.android.features.home.impl.isChamosEstado\n",
     "import io.element.android.features.home.impl.isChamosEstado\nimport io.element.android.features.home.impl.isChamosReels\nimport io.element.android.features.home.impl.isChamosMuro\nimport io.element.android.features.home.impl.isChamosAvisos\n"),
])
# pantalla principal: botón en la barra de abajo + pantalla de Reels
edit(os.path.join(d, "HomeView.kt"), [
    ("    val firstThrottler = remember { FirstThrottler(300, coroutineScope) }\n",
     "    val firstThrottler = remember { FirstThrottler(300, coroutineScope) }\n"
     "    val chamosReelsAbierto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n"),
    ("        leaveRoomView()\n\n        HomeScaffold(\n",
     "        leaveRoomView()\n\n"
     "        if (chamosReelsAbierto.value) {\n"
     "            val chamosReelsEstado = homeState.chamosReels\n"
     "            if (chamosReelsEstado != null) ChamosReelsPantalla(chamosReelsEstado) { chamosReelsAbierto.value = false }\n"
     "        }\n\n"
     "        HomeScaffold(\n"),
    ("            onOpenSettings = { if (firstThrottler.canHandle()) onSettingsClick() },\n",
     "            onOpenSettings = { if (firstThrottler.canHandle()) onSettingsClick() },\n"
     "            onOpenReels = { chamosReelsAbierto.value = true },\n"),
    ("    onOpenSettings: () -> Unit,\n    onStartChatClick: () -> Unit,\n",
     "    onOpenSettings: () -> Unit,\n    onOpenReels: () -> Unit,\n    onStartChatClick: () -> Unit,\n"),
    ("                onSettingsClick = onOpenSettings,\n",
     "                onSettingsClick = onOpenSettings,\n                onReelsClick = onOpenReels,\n"),
    ("    onSettingsClick: () -> Unit,\n    modifier: Modifier = Modifier,\n",
     "    onSettingsClick: () -> Unit,\n    onReelsClick: () -> Unit,\n    modifier: Modifier = Modifier,\n"),
    ("            onClick = onSettingsClick,\n        )\n",
     "            onClick = onSettingsClick,\n        )\n"
     "        HorizontalFloatingToolbarSeparator()\n"
     "        HorizontalFloatingToolbarItem(\n"
     "            icon = CompoundIcons.PlaySolid(),\n"
     "            tooltipLabel = \"Reels\",\n"
     "            isSelected = false,\n"
     "            onClick = onReelsClick,\n"
     "        )\n"),
])
print("reels OK")
