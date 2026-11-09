# Iconos y barra: el engranaje de Ajustes pasa arriba (junto al titulo), "Chamo Redes" usa ninos de la mano y
# el boton del Navegador usa el mundito con gorra al reves.
# Se ejecuta DESPUES de navegador.py. Uso: python3 iconos_ui.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosIconos.kt"), os.path.join(d, "ChamosIconos.kt"))
hv = os.path.join(d, "HomeView.kt")
s = open(hv, encoding="utf-8").read()
def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:70], s.count(old))
    s = s.replace(old, new)

# 1) fuera el engranaje de la barra de abajo
rep("        HorizontalFloatingToolbarItem(\n            icon = CompoundIcons.Settings(),\n            tooltipLabel = \"Ajustes\",\n"
    "            isSelected = false,\n            onClick = onSettingsClick,\n        )\n", "")
# 2) iconos nuevos
rep("            icon = CompoundIcons.Public(),\n            tooltipLabel = \"Chamo Muro\",\n",
    "            icon = ChamosIconoNinos,\n            tooltipLabel = \"Chamo Muro\",\n")
rep("            icon = CompoundIcons.Link(),\n            tooltipLabel = \"Navegador\",\n",
    "            icon = ChamosIconoMundoGorra,\n            tooltipLabel = \"Navegador\",\n")
open(hv, "w", encoding="utf-8").write(s)
print("iconos_ui OK")
