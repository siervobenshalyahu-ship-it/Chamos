# Seguir / dejar de seguir, guardar con marca de agua y límite de estados. Uso: python3 social.py RAIZ_DEL_REPO
# Se ejecuta DESPUÉS de reels.py, home_estados.py y barra_muro.py.
import sys, os, shutil
root = sys.argv[1]
aqui = os.path.dirname(os.path.abspath(__file__))
d = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(aqui, "ChamosSocial.kt"), os.path.join(d, "ChamosSocial.kt"))
g = os.path.join(root, "features/home/impl/build.gradle.kts")
s = open(g, encoding="utf-8").read()
old = "    implementation(projects.libraries.matrix.api)\n"
assert s.count(old) == 1
s = s.replace(old, old + "    implementation(libs.androidx.media3.transformer)\n    implementation(libs.androidx.media3.effect)\n    implementation(libs.androidx.media3.common)\n    implementation(libs.google.guava)\n")
open(g, "w", encoding="utf-8").write(s)
print("social OK")
