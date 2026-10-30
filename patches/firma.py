import os, sys, base64
root = sys.argv[1]
b64 = os.environ.get("CHAMOS_KEYSTORE_B64", "").strip()
if not b64:
    print("Sin llave propia: se firma con la llave de depuración (pública).")
    sys.exit(0)
sig = os.path.join(root, "app", "signature")
os.makedirs(sig, exist_ok=True)
open(os.path.join(sig, "chamos.keystore"), "wb").write(base64.b64decode(b64))
p = os.path.join(root, "app", "build.gradle.kts")
s = open(p, encoding="utf-8").read()
old = '''            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("./signature/debug.keystore")
            storePassword = "android"'''
assert s.count(old) == 1
new = '''            keyAlias = "%s"
            keyPassword = "%s"
            storeFile = file("./signature/chamos.keystore")
            storePassword = "%s"''' % (
    os.environ["CHAMOS_KEY_ALIAS"], os.environ["CHAMOS_KEY_PASSWORD"], os.environ["CHAMOS_STORE_PASSWORD"])
open(p, "w", encoding="utf-8").write(s.replace(old, new))
print("Firmando con la llave propia de Chamos.")
