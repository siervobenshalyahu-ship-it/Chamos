# Agrega las actualizaciones dentro de la app: copia ChamosUpdater.kt, pide el permiso de instalar APK
# y lo llama al abrir la app. Uso: python3 actualizador.py RAIZ_DEL_REPO RUTA_A_ChamosUpdater.kt
import sys, os, shutil

root, src = sys.argv[1], sys.argv[2]

# 1) la clase que revisa GitHub y descarga/instala
dest = os.path.join(root, "app/src/main/kotlin/io/element/android/x/ChamosUpdater.kt")
shutil.copyfile(src, dest)

# 2) permiso para instalar APK
manifest = os.path.join(root, "app/src/main/AndroidManifest.xml")
m = open(manifest, encoding="utf8").read()
perm = '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
if perm not in m:
    anchor = "    <application"
    if m.count(anchor) != 1:
        sys.exit("actualizador: no se encontró <application> en el manifest")
    m = m.replace(anchor, "    " + perm + "\n\n" + anchor)
    open(manifest, "w", encoding="utf8").write(m)

# 3) llamarlo al abrir la app
main = os.path.join(root, "app/src/main/kotlin/io/element/android/x/MainActivity.kt")
t = open(main, encoding="utf8").read()
anchor = "        val activity = this\n"
if "ChamosUpdater.checkForUpdate" not in t:
    if t.count(anchor) != 1:
        sys.exit("actualizador: no se encontró el punto de enganche en MainActivity.kt (%d veces)" % t.count(anchor))
    t = t.replace(anchor, "        ChamosUpdater.checkForUpdate(this)\n\n" + anchor)
    open(main, "w", encoding="utf8").write(t)
print("actualizador: aplicado")
