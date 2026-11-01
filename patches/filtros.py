# Filtros de cámara, caras y memes en las videollamadas.
# Uso: python3 filtros.py RAIZ_DEL_REPO CARPETA_PATCHES [CARPETA_MEDIAPIPE]
import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]
mp = sys.argv[3] if len(sys.argv) > 3 else ""

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

# 1) el script y (si se bajó) el detector de caras viajan como archivos de la app: la pantalla de llamada
#    los sirve desde el mismo origen que Element Call.
assets = os.path.join(root, "app/src/main/assets/chamos")
os.makedirs(assets, exist_ok=True)
shutil.copy(os.path.join(pdir, "chamos-filtros.js"), os.path.join(assets, "filtros.js"))
caras = False
if mp and os.path.isfile(os.path.join(mp, "vision_bundle.cjs")) and os.path.isfile(os.path.join(mp, "face_landmarker.task")):
    shutil.copytree(mp, os.path.join(assets, "mediapipe"), dirs_exist_ok=True)
    caras = True

# 2) se carga en cuanto empieza la página de la llamada (antes de que Element Call pida la cámara)
edit(os.path.join(root, "features/call/impl/src/main/kotlin/io/element/android/features/call/impl/utils/WebViewWidgetMessageInterceptor.kt"), [
    ("                super.onPageStarted(view, url, favicon)\n",
     "                super.onPageStarted(view, url, favicon)\n"
     "\n"
     "                // Chamos: filtros de cámara, caras y memes.\n"
     "                try {\n"
     "                    val chamosFiltros = view.context.assets.open(\"chamos/filtros.js\").bufferedReader().use { it.readText() }\n"
     "                    view.evaluateJavascript(chamosFiltros, null)\n"
     "                } catch (e: Exception) {\n"
     "                    android.util.Log.w(\"Chamos\", \"Filtros de video no disponibles\", e)\n"
     "                }\n"),
])
print("filtros OK (" + ("con detector de caras" if caras else "solo filtros de color y memes") + ")")
