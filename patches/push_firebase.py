# Conecta Chamos a TU proyecto de Firebase y a TU gateway de notificaciones.
# Uso: python3 push_firebase.py RAIZ_DEL_REPO RUTA/google-services.json URL_DEL_GATEWAY
import sys, os, re, json, glob

root, gs_path, gateway = sys.argv[1], sys.argv[2], sys.argv[3].strip()

# 1) URL del gateway (siempre termina en /_matrix/push/v1/notify)
suffix = "/_matrix/push/v1/notify"
gateway = gateway.rstrip("/")
if not gateway.startswith("https://"):
    sys.exit("push_firebase: la URL del gateway debe empezar con https://")
if not gateway.endswith(suffix):
    gateway += suffix

# 2) datos de Firebase
gs = json.load(open(gs_path, encoding="utf8"))
info = gs["project_info"]
clients = {c["client_info"]["android_client_info"]["package_name"]: c for c in gs["client"]}
# La compilación de prueba (debug) usa el paquete com.chamos.app.debug
client = clients.get("com.chamos.app.debug") or clients.get("com.chamos.app")
if client is None:
    sys.exit("push_firebase: en google-services.json no hay una app Android con paquete com.chamos.app.debug ni com.chamos.app. Paquetes encontrados: %s" % ", ".join(clients) )
used_package = client["client_info"]["android_client_info"]["package_name"]
values = {
    "gcm_defaultSenderId": info["project_number"],
    "project_id": info["project_id"],
    "google_app_id": client["client_info"]["mobilesdk_app_id"],
    "google_api_key": client["api_key"][0]["current_key"],
}
values["google_crash_reporting_api_key"] = values["google_api_key"]
optional = {
    "firebase_database_url": info.get("firebase_url"),
    "google_storage_bucket": info.get("storage_bucket"),
}

fb = os.path.join(root, "libraries/pushproviders/firebase/src")
files = glob.glob(os.path.join(fb, "*/res/values/firebase.xml"))
if not files:
    sys.exit("push_firebase: no se encontraron los firebase.xml")
for p in files:
    t = open(p, encoding="utf8").read()
    for name, v in values.items():
        t = re.sub(r'(<string name="%s"[^>]*>)[^<]*(</string>)' % name, lambda m: m.group(1) + v + m.group(2), t)
    for name, v in optional.items():
        if v:
            t = re.sub(r'(<string name="%s"[^>]*>)[^<]*(</string>)' % name, lambda m: m.group(1) + v + m.group(2), t)
        else:
            t = re.sub(r'[ \t]*<string name="%s"[^>]*>[^<]*</string>\n' % name, "", t)
    # El id del cliente web de Element ya no sirve con otro proyecto
    t = re.sub(r'[ \t]*<string name="default_web_client_id"[^>]*>[^<]*</string>\n', "", t)
    open(p, "w", encoding="utf8").write(t)

# 3) gateway
cfg = os.path.join(fb, "main/kotlin/io/element/android/libraries/pushproviders/firebase/FirebaseConfig.kt")
t = open(cfg, encoding="utf8").read()
t2, n = re.subn(r'(const val PUSHER_HTTP_URL: String = )"[^"]*"', lambda m: m.group(1) + '"' + gateway + '"', t)
if n != 1:
    sys.exit("push_firebase: no se encontró PUSHER_HTTP_URL en FirebaseConfig.kt")
open(cfg, "w", encoding="utf8").write(t2)
print("push_firebase: proyecto %s, app %s (%s), gateway %s" % (info["project_id"], values["google_app_id"], used_package, gateway))
