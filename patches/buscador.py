# Interruptor en la búsqueda de personas: solo Chamos / todos (Matrix y Element). Uso: python3 buscador.py RAIZ_DEL_REPO
# Se ejecuta DESPUÉS de numero.py.
import sys, os
root = sys.argv[1]
base = os.path.join(root, "features/startchat/impl/src/main/kotlin/io/element/android/features/startchat/impl")

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (os.path.basename(path), old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

edit(os.path.join(base, "userlist/DefaultUserListPresenter.kt"), [
    ("        LaunchedEffect(searchQuery) {\n",
     "        val soloChamos by io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.soloChamos.collectAsState()\n"
     "        LaunchedEffect(searchQuery, soloChamos) {\n"),
])
edit(os.path.join(base, "components/UserListView.kt"), [
    ("        SearchUserBar(\n",
     "        if (io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.filtro != null) {\n"
     "            val soloChamos = io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.soloChamos.collectAsState().value\n"
     "            androidx.compose.foundation.layout.Row(\n"
     "                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),\n"
     "                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,\n"
     "                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,\n"
     "            ) {\n"
     "                androidx.compose.material3.Text(\n"
     "                    text = if (soloChamos) \"Buscar solo en Chamos\" else \"Buscar en todos (Chamos, Matrix y Element)\",\n"
     "                    modifier = Modifier.weight(1f),\n"
     "                )\n"
     "                androidx.compose.material3.Switch(\n"
     "                    checked = soloChamos,\n"
     "                    onCheckedChange = { io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.soloChamos.value = it },\n"
     "                )\n"
     "            }\n"
     "        }\n"
     "        SearchUserBar(\n"),
    ("import androidx.compose.runtime.Composable\n", "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\n"),
])
print("buscador OK")
