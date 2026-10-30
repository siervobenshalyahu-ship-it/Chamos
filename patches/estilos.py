import sys, os, shutil, glob
root, pdir, fondos = sys.argv[1], sys.argv[2], sys.argv[3]

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) tienda de estilos + tema dinámico (módulo compound)
cdir = os.path.join(root, "libraries/compound/src/main/kotlin/io/element/android/compound/theme")
shutil.copy(os.path.join(pdir, "ChamosEstilo.kt"), os.path.join(cdir, "ChamosEstilo.kt"))
edit(os.path.join(cdir, "ElementTheme.kt"), [
    ("    val darkTheme = theme.isDark()\n",
     "    val darkTheme = theme.isDark()\n    ChamosEstilo.init(LocalContext.current)\n"
     "    val compoundLight = ChamosEstilo.tinte(compoundLight, false)\n"
     "    val compoundDark = ChamosEstilo.tinte(compoundDark, true)\n"
     "    val materialColorsLight = ChamosEstilo.tinteMaterial(materialColorsLight, false)\n"
     "    val materialColorsDark = ChamosEstilo.tinteMaterial(materialColorsDark, true)\n"),
])

# 2) fondo de chat (módulo messages)
mdir = os.path.join(root, "features/messages/impl/src/main")
shutil.copy(os.path.join(pdir, "ChamosFondoChat.kt"), os.path.join(mdir, "kotlin/io/element/android/features/messages/impl/timeline/ChamosFondoChat.kt"))
rd = os.path.join(mdir, "res/drawable-nodpi"); os.makedirs(rd, exist_ok=True)
for f in glob.glob(os.path.join(fondos, "chamos_fondo_*.webp")):
    shutil.copy(f, rd)
edit(os.path.join(mdir, "kotlin/io/element/android/features/messages/impl/timeline/TimelineView.kt"), [
    ("            Box(modifier) {\n                LazyColumn(", "            Box(modifier) {\n                ChamosFondoChat()\n                LazyColumn("),
])

# 3) botón Estilos junto a Ajustes (módulo home)
hdir = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosEstilosDialog.kt"), os.path.join(hdir, "ChamosEstilosDialog.kt"))
edit(os.path.join(hdir, "HomeView.kt"), [
    ("                onSettingsClick = onOpenSettings,\n", "                onSettingsClick = onOpenSettings,\n                onStylesClick = { mostrarEstilos = true },\n"),
    ("    onSettingsClick: () -> Unit,\n    modifier", "    onSettingsClick: () -> Unit,\n    onStylesClick: () -> Unit,\n    modifier"),
    ("""            onClick = onSettingsClick,
        )
""", """            onClick = onSettingsClick,
        )
        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.Image(),
            tooltipLabel = "Estilos",
            isSelected = false,
            onClick = onStylesClick,
        )
"""),
    ("import androidx.compose.runtime.Composable\n", "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue\n"),
    ("    val roomsLazyListState = rememberLazyListState()\n",
     "    var mostrarEstilos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n    if (mostrarEstilos) ChamosEstilosDialog(onDismiss = { mostrarEstilos = false })\n    val roomsLazyListState = rememberLazyListState()\n"),
])
print("estilos OK")
