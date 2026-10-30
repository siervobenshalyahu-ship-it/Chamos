import sys, os, shutil
root, kt = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(kt, os.path.join(d, "ChamosEstados.kt"))
shutil.copy(os.path.join(os.path.dirname(kt), "ChamosEstadosData.kt"), os.path.join(d, "ChamosEstadosData.kt"))
p = os.path.join(d, "HomeView.kt")
s = open(p, encoding="utf-8").read()

def rep(old, new, count=1):
    global s
    assert s.count(old) == count, (old[:60], s.count(old))
    s = s.replace(old, new)

# 1) quitar la barra superior (avatar, titulo, buscar) y los filtros
a = s.index("        topBar = {\n            HomeTopBar(")
b = s.index("        floatingActionButton = {\n            val coroutineScope")
s = s[:a] + s[b:]

# 2) Estados arriba de la lista
rep("""                        RoomListContentView(
                            contentState""", """                      Column(
                          modifier = Modifier
                              .padding(outerPadding)
                              .consumeWindowInsets(outerPadding)
                      ) {
                        ChamosEstadosRow(state = state.chamosEstados, onOpenRoom = { id: RoomId -> onRoomClick(id) })
                        RoomListContentView(
                            contentState""")
rep("""                            modifier = Modifier
                                .padding(outerPadding)
                                .consumeWindowInsets(outerPadding)
                        )
                        SpaceFiltersView""", """                            modifier = Modifier.weight(1f)
                        )
                      }
                        SpaceFiltersView""")
rep("import androidx.compose.foundation.layout.Box\n", "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column\n")

# 3) Ajustes abajo a la izquierda (primer item de la barra inferior)
rep("""                currentHomeNavigationBarItem = state.currentHomeNavigationBarItem,
                onItemClick = { item ->""", """                currentHomeNavigationBarItem = state.currentHomeNavigationBarItem,
                onSettingsClick = onOpenSettings,
                onItemClick = { item ->""")
rep("""    onItemClick: (HomeNavigationBarItem) -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton""", """    onItemClick: (HomeNavigationBarItem) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton""")
rep("""        HomeNavigationBarItem.entries.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalFloatingToolbarSeparator()
            }""", """        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.Settings(),
            tooltipLabel = "Ajustes",
            isSelected = false,
            onClick = onSettingsClick,
        )
        HomeNavigationBarItem.entries.forEachIndexed { index, item ->
            HorizontalFloatingToolbarSeparator()""")
open(p, "w", encoding="utf-8").write(s)

# --- origen de datos: ocultar salas de estado, auto-unirse, invitar contactos
p2 = os.path.join(d, "datasource/RoomListDataSource.kt")
t = open(p2, encoding="utf-8").read()
def rep2(old, new):
    global t
    assert t.count(old) == 1, old[:60]
    t = t.replace(old, new)
rep2("    private val analyticsService: AnalyticsService,\n) {\n    init {",
     "    private val analyticsService: AnalyticsService,\n    private val chamosClient: io.element.android.libraries.matrix.api.MatrixClient,\n) {\n    init {\n        io.element.android.features.home.impl.ChamosEstados.attach(chamosClient, sessionCoroutineScope)")
rep2("""            diffCacheUpdater.updateWith(roomSummaries)
            buildAndEmitAllRooms(roomSummaries)""","""            io.element.android.features.home.impl.ChamosEstados.onRooms(roomSummaries)
            val visibles = roomSummaries.filterNot { io.element.android.features.home.impl.ChamosEstados.esEstado(it) }
            diffCacheUpdater.updateWith(visibles)
            buildAndEmitAllRooms(visibles)""")
rep2("""                diffCacheUpdater.updateWith(roomSummaries)
                buildAndEmitAllRooms(roomSummaries, useCache = false)""","""                val visibles = roomSummaries.filterNot { io.element.android.features.home.impl.ChamosEstados.esEstado(it) }
                diffCacheUpdater.updateWith(visibles)
                buildAndEmitAllRooms(visibles, useCache = false)""")
open(p2, "w", encoding="utf-8").write(t)

# --- datos: lista de chats sin salas de estados + salas de estados aparte
p2 = os.path.join(d, "datasource/RoomListDataSource.kt")
t = open(p2, encoding="utf-8").read()
old = "    val roomSummariesFlow: Flow<ImmutableList<RoomListRoomSummary>> = _roomSummariesFlow\n"
assert t.count(old) == 1
t = t.replace(old, """    val roomSummariesFlow: Flow<ImmutableList<RoomListRoomSummary>> = _roomSummariesFlow
        .map { list -> list.filterNot { it.isChamosEstado() }.toImmutableList() }

    val statusRoomsFlow: Flow<ImmutableList<RoomListRoomSummary>> = _roomSummariesFlow
        .map { list -> list.filter { it.isChamosEstado() }.toImmutableList() }
""")
t = t.replace("import io.element.android.features.home.impl.model.RoomListRoomSummary\n", "import io.element.android.features.home.impl.isChamosEstado\nimport io.element.android.features.home.impl.model.RoomListRoomSummary\n", 1)
t = t.replace("import kotlinx.coroutines.flow.launchIn\n", "import kotlinx.coroutines.flow.launchIn\nimport kotlinx.coroutines.flow.map\n", 1)
open(p2, "w", encoding="utf-8").write(t)

# --- HomeState
p3 = os.path.join(d, "HomeState.kt")
t = open(p3, encoding="utf-8").read()
old = "    val eventSink: (HomeEvent) -> Unit,\n) {"
assert t.count(old) == 1
t = t.replace(old, "    val eventSink: (HomeEvent) -> Unit,\n    val chamosEstados: ChamosEstadosState? = null,\n) {")
open(p3, "w", encoding="utf-8").write(t)

# --- HomePresenter
p4 = os.path.join(d, "HomePresenter.kt")
t = open(p4, encoding="utf-8").read()
old = "    private val featureFlagService: FeatureFlagService,\n"
assert t.count(old) == 1
t = t.replace(old, old + "    private val roomListDataSource: io.element.android.features.home.impl.datasource.RoomListDataSource,\n")
old = "        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()\n"
assert t.count(old) == 1
t = t.replace(old, old + "        val chamosEstados = rememberChamosEstados(client, roomListDataSource)\n")
old = "            eventSink = ::handleEvent,\n        )"
assert t.count(old) == 1
t = t.replace(old, "            eventSink = ::handleEvent,\n            chamosEstados = chamosEstados,\n        )")
open(p4, "w", encoding="utf-8").write(t)
print("home_estados OK")
