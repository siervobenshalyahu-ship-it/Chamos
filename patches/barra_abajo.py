# Barra de abajo plegable (se desliza con el dedo). Se ejecuta DESPUES de barra_oval.py. Uso: python3 barra_abajo.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosBarraAbajo.kt"), os.path.join(d, "ChamosBarraAbajo.kt"))
hv = os.path.join(d, "HomeView.kt")
s = open(hv, encoding="utf-8").read()
old = "@Composable\nprivate fun HomeBottomBar(\n    totalUnreadCount: Int,"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, "@Composable\nprivate fun HomeBottomBarOriginal(\n    totalUnreadCount: Int,")
s += '''
@Composable
private fun HomeBottomBar(
    totalUnreadCount: Int,
    currentHomeNavigationBarItem: HomeNavigationBarItem,
    onItemClick: (HomeNavigationBarItem) -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)?,
) {
    ChamosBarraAbajo(modifier = modifier) {
        HomeBottomBarOriginal(
            totalUnreadCount = totalUnreadCount,
            currentHomeNavigationBarItem = currentHomeNavigationBarItem,
            onItemClick = onItemClick,
            floatingActionButton = floatingActionButton,
        )
    }
}
'''
open(hv, "w", encoding="utf-8").write(s)
print("barra_abajo OK")
