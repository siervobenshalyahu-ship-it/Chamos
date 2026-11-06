# Vincular número de teléfono (verificado con Telegram) y buscar contactos.
# Uso: python3 numero.py RAIZ_DEL_REPO CARPETA_PATCHES URL_DEL_SERVICIO
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
url = (sys.argv[3] if len(sys.argv) > 3 else "").strip()

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

hdir = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
kt = open(os.path.join(pdir, "ChamosNumero.kt"), encoding="utf-8").read()
if url.startswith("https://"):
    kt = kt.replace("__CUENTAS_URL__", url)
open(os.path.join(hdir, "ChamosNumero.kt"), "w", encoding="utf-8").write(kt)

# 1) la sesión y el cliente llegan al objeto desde el origen de datos de la lista de chats
edit(os.path.join(hdir, "datasource/RoomListDataSource.kt"), [
    ("    private val chamosClient: io.element.android.libraries.matrix.api.MatrixClient,\n",
     "    private val chamosClient: io.element.android.libraries.matrix.api.MatrixClient,\n"
     "    private val chamosSesiones: io.element.android.libraries.sessionstorage.api.SessionStore,\n"),
    ("        io.element.android.features.home.impl.ChamosEstados.attach(chamosClient, sessionCoroutineScope)\n",
     "        io.element.android.features.home.impl.ChamosEstados.attach(chamosClient, sessionCoroutineScope)\n"
     "        io.element.android.features.home.impl.ChamosNumero.attach(chamosClient, chamosSesiones)\n"),
])

# 2) botón "Número" junto a Estilos
edit(os.path.join(hdir, "HomeView.kt"), [
    ("    if (mostrarEstilos) ChamosEstilosDialog(onDismiss = { mostrarEstilos = false })\n",
     "    if (mostrarEstilos) ChamosEstilosDialog(onDismiss = { mostrarEstilos = false })\n"
     "    var mostrarNumero by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }\n"
     "    if (mostrarNumero) ChamosNumeroDialog(onDismiss = { mostrarNumero = false })\n"),
    ("                onStylesClick = { mostrarEstilos = true },\n",
     "                onStylesClick = { mostrarEstilos = true },\n                onNumberClick = { mostrarNumero = true },\n"),
    ("    onStylesClick: () -> Unit,\n", "    onStylesClick: () -> Unit,\n    onNumberClick: () -> Unit,\n"),
    ("""            onClick = onStylesClick,
        )
""", """            onClick = onStylesClick,
        )
        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.UserProfile(),
            tooltipLabel = "Número",
            isSelected = false,
            onClick = onNumberClick,
        )
"""),
])

# 3) permiso para leer la agenda (se pide solo cuando el usuario toca "Buscar mis contactos")
edit(os.path.join(root, "app/src/main/AndroidManifest.xml"), [
    ("    <application\n", '    <uses-permission android:name="android.permission.READ_CONTACTS" />\n\n    <application\n'),
])

# 4) La búsqueda de usuarios solo muestra cuentas de Chamos (si el servidor responde)
shutil.copy(os.path.join(pdir, "ChamosFiltroBusqueda.kt"), os.path.join(root, "libraries/matrix/api/src/main/kotlin/io/element/android/libraries/matrix/api/ChamosFiltroBusqueda.kt"))
edit(os.path.join(root, "libraries/usersearch/impl/src/main/kotlin/io/element/android/libraries/usersearch/impl/MatrixUserListDataSource.kt"), [
    ("        return res.getOrNull()?.results.orEmpty()\n",
     "        val todos = res.getOrNull()?.results.orEmpty()\n"
     "        val f = io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.filtro\n"
     "        if (f == null || query.trim().startsWith(\"@\") || !io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.soloChamos.value) return todos\n"
     "        val son = f(todos.map { it.userId.value }) ?: return todos\n"
     "        return todos.filter { it.userId.value in son }\n"),
])
print("numero OK" + (" (servidor: " + url + ")" if url.startswith("https://") else " (sin servidor: pantalla desactivada)"))
