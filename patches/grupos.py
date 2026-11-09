# Boton "Crear grupo" (👥) arriba en la lista de chats: abre directo la pantalla de nueva sala.
# Se ejecuta DESPUES de iconos_ui.py. Uso: python3 grupos.py RAIZ_DEL_REPO
import sys, os
root = sys.argv[1]
api = os.path.join(root, "libraries/matrix/api/src/main/kotlin/io/element/android/libraries/matrix/api")
open(os.path.join(api, "ChamosGrupo.kt"), "w", encoding="utf-8").write(
    "package io.element.android.libraries.matrix.api\n\n"
    "/** Cuando es true, la proxima pantalla de \"nuevo chat\" abre directo la creacion de un grupo. */\n"
    "object ChamosGrupo {\n"
    "    @Volatile var directo: Boolean = false\n"
    "    fun tomar(): Boolean { val v = directo; directo = false; return v }\n"
    "}\n")

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

edit(os.path.join(root, "features/startchat/impl/src/main/kotlin/io/element/android/features/startchat/impl/StartChatFlowNode.kt"), [
    ("        initialElement = NavTarget.Root,\n",
     "        initialElement = if (io.element.android.libraries.matrix.api.ChamosGrupo.tomar()) NavTarget.NewRoom else NavTarget.Root,\n"),
])
edit(os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeView.kt"), [
    ("                                io.element.android.libraries.designsystem.theme.components.Text(\n"
     "                                    text = \"🔍\",\n",
     "                                io.element.android.libraries.designsystem.theme.components.Text(\n"
     "                                    text = \"👥\",\n"
     "                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),\n"
     "                                    modifier = Modifier.clickable {\n"
     "                                        io.element.android.libraries.matrix.api.ChamosGrupo.directo = true\n"
     "                                        onStartChatClick()\n"
     "                                    }.padding(horizontal = 12.dp, vertical = 8.dp),\n"
     "                                )\n"
     "                                io.element.android.libraries.designsystem.theme.components.Text(\n"
     "                                    text = \"🔍\",\n"),
])
print("grupos OK")
