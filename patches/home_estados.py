import sys, os, shutil
root, kt = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(kt, os.path.join(d, "ChamosEstados.kt"))
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
                        ChamosEstadosRow(onAddStatus = { })
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
print("home_estados OK")
