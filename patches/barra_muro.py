# Barra de abajo: quita la gota de mensajes y el lápiz, agrega "Chamo Muro"; el lápiz (nuevo chat / buscar personas) pasa arriba.
# Se ejecuta DESPUÉS de reels.py y ajustes_ui.py. Uso: python3 barra_muro.py RAIZ_DEL_REPO
import sys, os
root = sys.argv[1]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

hv = os.path.join(d, "HomeView.kt")
s = open(hv, encoding="utf-8").read()
lapiz = ("        HorizontalFloatingToolbarItem(\n            icon = CompoundIcons.Compose(),\n            tooltipLabel = \"Nuevo chat\",\n"
         "            isSelected = false,\n            onClick = onStartChatClick,\n        )\n")
assert s.count(lapiz) == 1
s = s.replace(lapiz, "")
a = s.index("        HomeNavigationBarItem.entries.filter { it != HomeNavigationBarItem.Spaces }.forEachIndexed { index, item ->\n")
fin = "counter = totalUnreadCount.takeIf { item == HomeNavigationBarItem.Chats && it > 0 }\n            )\n        }\n"
b = s.index(fin, a) + len(fin)
muro = ("        HorizontalFloatingToolbarSeparator()\n"
        "        HorizontalFloatingToolbarItem(\n"
        "            icon = CompoundIcons.Public(),\n"
        "            tooltipLabel = \"Chamo Muro\",\n"
        "            isSelected = false,\n"
        "            onClick = onMuroClick,\n"
        "        )\n")
s = s[:a] + muro + s[b:]
open(hv, "w", encoding="utf-8").write(s)

edit(hv, [
    # estado: abrir los Reels directo en el Muro
    ("    val chamosReelsAbierto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n",
     "    val chamosReelsAbierto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n"
     "    val chamosMuroInicial = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n"),
    ("if (chamosReelsEstado != null) ChamosReelsPantalla(chamosReelsEstado) { chamosReelsAbierto.value = false }",
     "if (chamosReelsEstado != null) ChamosReelsPantalla(chamosReelsEstado, chamosMuroInicial.value) { chamosReelsAbierto.value = false }"),
    ("            onOpenReels = { chamosReelsAbierto.value = true },\n",
     "            onOpenReels = { chamosMuroInicial.value = false; chamosReelsAbierto.value = true },\n"
     "            onOpenMuro = { chamosMuroInicial.value = true; chamosReelsAbierto.value = true },\n"),
    ("    onOpenReels: () -> Unit,\n", "    onOpenReels: () -> Unit,\n    onOpenMuro: () -> Unit,\n"),
    ("                onReelsClick = onOpenReels,\n", "                onReelsClick = onOpenReels,\n                onMuroClick = onOpenMuro,\n"),
    ("    onReelsClick: () -> Unit,\n", "    onReelsClick: () -> Unit,\n    onMuroClick: () -> Unit,\n"),
    # el lápiz (nuevo chat / buscar personas) pasa a una franja arriba de la lista de chats
    ("                        ChamosEstadosRow(state = state.chamosEstados)\n",
     "                        androidx.compose.foundation.layout.Row(\n"
     "                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),\n"
     "                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,\n"
     "                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,\n"
     "                        ) {\n"
     "                            io.element.android.libraries.designsystem.theme.components.Text(\n"
     "                                text = \"Chamos\",\n"
     "                                style = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),\n"
     "                            )\n"
     "                            io.element.android.libraries.designsystem.theme.components.Text(\n"
     "                                text = \"✏️  Nuevo chat\",\n"
     "                                style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),\n"
     "                                modifier = Modifier.clickable { onStartChatClick() }.padding(horizontal = 12.dp, vertical = 10.dp),\n"
     "                            )\n"
     "                        }\n"
     "                        ChamosEstadosRow(state = state.chamosEstados)\n"),
    ("import androidx.compose.foundation.layout.Box\n",
     "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n"),
])

edit(os.path.join(d, "ChamosReels.kt"), [
    ("fun ChamosReelsPantalla(state: ChamosReelsState, onCerrar: () -> Unit) {",
     "fun ChamosReelsPantalla(state: ChamosReelsState, inicioMuro: Boolean = false, onCerrar: () -> Unit) {"),
    ("    var verMuro by remember { mutableStateOf(false) }\n", "    var verMuro by remember { mutableStateOf(inicioMuro) }\n"),
])
print("barra_muro OK")
