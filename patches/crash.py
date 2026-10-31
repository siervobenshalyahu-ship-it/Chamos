import sys, os, shutil
root = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
appdir = os.path.join(root, "app/src/main/kotlin/io/element/android/x")
shutil.copy(os.path.join(here, "ChamosCrash.kt"), os.path.join(appdir, "ChamosCrash.kt"))
p = os.path.join(appdir, "MainActivity.kt")
t = open(p, encoding="utf-8").read()
a = "        setupLockManagement(appBindings.lockScreenService(), appBindings.lockScreenEntryPoint())\n"
assert t.count(a) == 1
t = t.replace(a, "        ChamosCrash.instalar(this)\n        ChamosCrash.mostrarSiHay(this)\n" + a)
open(p, "w", encoding="utf-8").write(t)
print("crash OK")
