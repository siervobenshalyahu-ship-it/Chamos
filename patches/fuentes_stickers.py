# Fuentes (tipo de letra) y stickers para Chamos.
# Uso: python3 fuentes_stickers.py RAIZ_DEL_REPO CARPETA_PATCHES   (después de neon.py)
import sys, os, re, shutil
root, pdir = sys.argv[1], sys.argv[2]

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) fuente: los estilos de texto de Compound pasan a ser propiedades que leen la fuente elegida
tok = os.path.join(root, "libraries/compound/src/main/kotlin/io/element/android/compound/tokens/generated/TypographyTokens.kt")
t = open(tok, encoding="utf-8").read()
t, n1 = re.subn(r"    val (font\w+) = TextStyle\(", r"    val \1: TextStyle get() = TextStyle(", t)
t, n2 = re.subn(r"fontFamily = FontFamily\.Default", "fontFamily = io.element.android.compound.theme.ChamosEstilo.familia", t)
assert n1 >= 10 and n1 == n2, (n1, n2)
open(tok, "w", encoding="utf-8").write(t)
edit(os.path.join(root, "libraries/compound/src/main/kotlin/io/element/android/compound/theme/ElementTheme.kt"), [
    ("            typography = typography,\n", "            typography = ChamosEstilo.tipografia(typography),\n"),
])

# 2) stickers en el chat
md = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer")
shutil.copy(os.path.join(pdir, "ChamosStickers.kt"), os.path.join(md, "ChamosStickers.kt"))
edit(os.path.join(md, "MessageComposerView.kt"), [
    ("    TextComposer(\n        modifier = modifier,\n        state = state.textEditorState,",
     "    val chamosStickersAbierto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n"
     "    androidx.compose.foundation.layout.Column(modifier = modifier) {\n"
     "    if (chamosStickersAbierto.value) {\n"
     "        ChamosStickersPanel(onEnviar = { uri -> chamosStickersAbierto.value = false; sendUri(uri) }, onCerrar = { chamosStickersAbierto.value = false })\n"
     "    } else {\n"
     "        ChamosStickersBoton { chamosStickersAbierto.value = true }\n"
     "    }\n"
     "    TextComposer(\n        modifier = Modifier,\n        state = state.textEditorState,"),
    ("        onSelectRichContent = ::sendUri,\n    )\n",
     "        onSelectRichContent = ::sendUri,\n    )\n    }\n"),
])
print("fuentes_stickers OK")
