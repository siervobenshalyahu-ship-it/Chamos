# Barra de abajo plegable (se desliza con el dedo). Se ejecuta DESPUES de barra_oval.py. Uso: python3 barra_abajo.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosBarraAbajo.kt"), os.path.join(d, "ChamosBarraAbajo.kt"))
hv = os.path.join(d, "HomeView.kt")
s = open(hv, encoding="utf-8").read()
import re
# Se lee la firma real de HomeBottomBar (otros parches le agregan parametros) y el envoltorio copia la misma firma.
m = re.search(r"@Composable\nprivate fun HomeBottomBar\(\n(.*?)\n\) \{\n", s, re.S)
assert m, "no se encontro HomeBottomBar"
params = m.group(1)
nombres = re.findall(r"^    (\w+):", params, re.M)
assert "modifier" in nombres and "floatingActionButton" in nombres, nombres
s = s.replace("@Composable\nprivate fun HomeBottomBar(\n", "@Composable\nprivate fun HomeBottomBarOriginal(\n", 1)
reenviar = "".join("            %s = %s,\n" % (n, n) for n in nombres if n != "modifier")
s += """
@Composable
private fun HomeBottomBar(
""" + params + """
) {
    ChamosBarraAbajo(modifier = modifier) {
        HomeBottomBarOriginal(
""" + reenviar + """        )
    }
}
"""
open(hv, "w", encoding="utf-8").write(s)
print("barra_abajo OK")
