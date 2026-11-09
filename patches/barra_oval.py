# Barra ovalada plegable para los iconos de arriba (se ejecuta DESPUES de grupos.py). Uso: python3 barra_oval.py RAIZ_DEL_REPO RUTA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(pdir, "ChamosBarraOvalada.kt"), os.path.join(d, "ChamosBarraOvalada.kt"))
hv = os.path.join(d, "HomeView.kt")
s = open(hv, encoding="utf-8").read()
old = "                            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {\n"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, "                            ChamosBarraOvalada(fijo = {\n"
                "                                io.element.android.libraries.designsystem.theme.components.Text(\n"
                "                                    text = \"⚙️\",\n"
                "                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),\n"
                "                                    modifier = Modifier.clickable { onOpenSettings() }.padding(horizontal = 12.dp, vertical = 8.dp),\n"
                "                                )\n"
                "                            }) {\n")
open(hv, "w", encoding="utf-8").write(s)
print("barra_oval OK")
