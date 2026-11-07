# Barra ovalada "VC" (Video Chamos) bajo la foto del perfil. Uso: python3 barra_vc.py RAIZ CARPETA_PATCHES
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
pref = os.path.join(root, "features/preferences/impl/src/main/kotlin/io/element/android/features/preferences/impl/user")
shutil.copy(os.path.join(pdir, "ChamosBarraVC.kt"), os.path.join(pref, "ChamosBarraVC.kt"))
path = os.path.join(pref, "UserPreferences.kt")
s = open(path, encoding="utf-8").read()
old = "    MatrixUserHeader(\n        modifier = modifier,\n        matrixUser = matrixUser,\n    )\n}"
assert s.count(old) == 1
new = ("    androidx.compose.foundation.layout.Column {\n"
       "        MatrixUserHeader(\n            modifier = modifier,\n            matrixUser = matrixUser,\n        )\n"
       "        ChamosBarraVC()\n    }\n}")
open(path, "w", encoding="utf-8").write(s.replace(old, new))
print("barra_vc: listo")
