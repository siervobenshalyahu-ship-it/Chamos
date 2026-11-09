# Marca las llamadas de la app a los servidores de Chamos con su paquete (x-chamos-app) para poder cerrar las versiones antiguas.
import os, re, shutil, sys
root = sys.argv[1]
aqui = os.path.dirname(os.path.abspath(__file__))
home = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl")
shutil.copy(os.path.join(aqui, "ChamosMarca.kt"), os.path.join(home, "ChamosMarca.kt"))

# 1) MainActivity fija el paquete antes de cualquier llamada
ma = os.path.join(root, "app/src/main/kotlin/io/element/android/x/MainActivity.kt")
t = open(ma, encoding="utf-8").read()
m = re.search(r"^( *)(?:if \(ChamosFirma\.verificar\(this\)\) )?ChamosUpdater\.checkForUpdate\(this\)\n", t, re.M)
assert m, "marca.py: no encontré dónde insertar en MainActivity"
t = t[:m.start()] + m.group(1) + "io.element.android.features.home.impl.ChamosMarca.pkg = packageName\n" + t[m.start():]
open(ma, "w", encoding="utf-8").write(t)

# 2) Cada llamada a chamos-push lleva el paquete
viejo = "openConnection() as HttpURLConnection"
def poner(archivo, patron, reemplazo, n):
    p = os.path.join(home, archivo)
    s = open(p, encoding="utf-8").read()
    assert s.count(patron) == n, "marca.py: %s esperaba %d y hay %d de %r" % (archivo, n, s.count(patron), patron)
    open(p, "w", encoding="utf-8").write(s.replace(patron, reemplazo))
h = '.also { it.setRequestProperty("x-chamos-app", ChamosMarca.pkg) } as HttpURLConnection'
hj = '.also { it.setRequestProperty("x-chamos-app", ChamosMarca.pkg) } as java.net.HttpURLConnection'
poner("ChamosStats.kt", "URL(URL_EVENTOS).openConnection() as HttpURLConnection", "URL(URL_EVENTOS).openConnection()" + h, 1)
poner("ChamosBarraVC.kt", "/video/resumen\").openConnection() as HttpURLConnection", "/video/resumen\").openConnection()" + h, 1)
poner("ChamosSocial.kt", 'URL_SEGUIR + "?u=" + java.net.URLEncoder.encode(autorId, "UTF-8")).openConnection() as HttpURLConnection', 'URL_SEGUIR + "?u=" + java.net.URLEncoder.encode(autorId, "UTF-8")).openConnection()' + h, 1)
poner("ChamosSocial.kt", "(URL(URL_SEGUIR).openConnection() as HttpURLConnection)", "(URL(URL_SEGUIR).openConnection()" + h + ")", 1)
poner("ChamosSocial.kt", "/video/accion\").openConnection() as HttpURLConnection", "/video/accion\").openConnection()" + h, 1)
poner("ChamosSocial.kt", "URL(BASE + ruta).openConnection() as HttpURLConnection", "URL(BASE + ruta).openConnection()" + h, 1)
poner("ChamosReels.kt", '"$URL_VIDEOS/$id/$i?h=$horas").openConnection() as java.net.HttpURLConnection', '"$URL_VIDEOS/$id/$i?h=$horas").openConnection()' + hj, 1)
poner("ChamosReels.kt", '"$URL_VIDEOS/$id/fin?n=$n&h=$horas").openConnection() as java.net.HttpURLConnection', '"$URL_VIDEOS/$id/fin?n=$n&h=$horas").openConnection()' + hj, 1)
print("marca.py: listo")
