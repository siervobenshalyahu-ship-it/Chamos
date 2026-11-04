# Ajustes de pantalla: stickers pequeños y sin fondo blanco, y barra de abajo sin "Espacios" ni el "+" azul.
# Uso: python3 ajustes_ui.py RAIZ_DEL_REPO   (después de numero.py)
import sys, os
root = sys.argv[1]

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) Imágenes que son stickers de Chamos (PNG 512x512): pequeñas (112 dp) y sin el fondo blanco de Element
vista = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/event/TimelineItemImageView.kt")
edit(vista, [
    ("    val description = content.caption?.let { \"$a11yLabel: $it\" } ?: a11yLabel\n",
     "    val description = content.caption?.let { \"$a11yLabel: $it\" } ?: a11yLabel\n"
     "    val esSticker = content.filename.startsWith(\"s_\") || (content.mimeType == \"image/png\" && content.width == 512 && content.height == 512)\n"),
    ("            modifier = containerModifier\n                .blurHashBackground(content.blurhash, alpha = 0.9f)\n                .align(Alignment.CenterHorizontally),\n            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),\n",
     "            modifier = (if (esSticker) containerModifier else containerModifier.blurHashBackground(content.blurhash, alpha = 0.9f))\n                .align(Alignment.CenterHorizontally),\n            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),\n"
     "            minHeight = if (esSticker) 112 else MIN_HEIGHT_IN_DP,\n            maxHeight = if (esSticker) 112 else MAX_HEIGHT_IN_DP,\n"),
    ("                            .then(if (isLoaded) Modifier.background(Color.White) else Modifier)\n",
     "                            .then(if (isLoaded && !esSticker) Modifier.background(Color.White) else Modifier)\n"),
])

# 2) Barra de abajo de la pantalla principal
hv = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeView.kt")
s = open(hv, encoding="utf-8").read()
a = s.index("                floatingActionButton = {\n                    when (state.currentHomeNavigationBarItem) {")
b = s.index("                totalUnreadCount = state.totalUnreadCount,", a)
s = s[:a] + "                floatingActionButton = null,\n" + s[b:]
open(hv, "w", encoding="utf-8").write(s)
edit(hv, [
    ("                onNumberClick = { mostrarNumero = true },\n", "                onNumberClick = { mostrarNumero = true },\n                onStartChatClick = onStartChatClick,\n"),
    ("    onNumberClick: () -> Unit,\n", "    onNumberClick: () -> Unit,\n    onStartChatClick: () -> Unit,\n"),
    ("            onClick = onReelsClick,\n        )\n",
     "            onClick = onReelsClick,\n        )\n"
     "        HorizontalFloatingToolbarItem(\n            icon = CompoundIcons.Compose(),\n            tooltipLabel = \"Nuevo chat\",\n            isSelected = false,\n            onClick = onStartChatClick,\n        )\n"),
    ("        HomeNavigationBarItem.entries.forEachIndexed { index, item ->\n",
     "        HomeNavigationBarItem.entries.filter { it != HomeNavigationBarItem.Spaces }.forEachIndexed { index, item ->\n"),
])
# 3) Se quita la burbuja "Número" de la barra de abajo
s = open(hv, encoding="utf-8").read()
bloque = ("        HorizontalFloatingToolbarItem(\n            icon = CompoundIcons.UserProfile(),\n            tooltipLabel = \"Número\",\n"
          "            isSelected = false,\n            onClick = onNumberClick,\n        )\n")
assert s.count(bloque) == 1, "bloque Número"
open(hv, "w", encoding="utf-8").write(s.replace(bloque, ""))
print("ajustes_ui OK")
