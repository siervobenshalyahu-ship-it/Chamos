# Navegador interno de Chamos. Uso: python3 navegador.py RAIZ_DEL_REPO (despues de barra_muro.py)
#  - copia la pantalla del navegador al modulo app y la declara en el manifiesto
#  - agrega el boton "Navegador" a la barra de abajo
#  - los enlaces que tocas en un chat se abren dentro de Chamos
import sys, os, shutil
root = sys.argv[1]
aqui = os.path.dirname(os.path.abspath(__file__))

# 1) la pantalla
dest = os.path.join(root, "app/src/main/kotlin/io/element/android/x")
assert os.path.isdir(dest), "navegador: no existe el modulo app"
shutil.copy(os.path.join(aqui, "ChamosNavegadorActivity.kt"), os.path.join(dest, "ChamosNavegadorActivity.kt"))

# 2) manifiesto
mf = os.path.join(root, "app/src/main/AndroidManifest.xml")
s = open(mf, encoding="utf-8").read()
marca = "        <!--\n        Using launchMode singleTask"
assert s.count(marca) == 1, "navegador: no se encontro el lugar en el manifiesto"
act = ('        <activity\n'
       '            android:name=".ChamosNavegadorActivity"\n'
       '            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode"\n'
       '            android:enableOnBackInvokedCallback="false"\n'
       '            android:exported="false"\n'
       '            android:theme="@android:style/Theme.Material.NoActionBar"\n'
       '            android:windowSoftInputMode="adjustResize" />\n\n')
open(mf, "w", encoding="utf-8").write(s.replace(marca, act + marca))

# 3) boton en la barra de abajo
hv = os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeView.kt")
s = open(hv, encoding="utf-8").read()
muro = ('        HorizontalFloatingToolbarItem(\n'
        '            icon = CompoundIcons.Public(),\n'
        '            tooltipLabel = "Chamo Muro",\n'
        '            isSelected = false,\n'
        '            onClick = onMuroClick,\n'
        '        )\n')
assert s.count(muro) == 1, "navegador: no se encontro el boton del Muro"
nuevo = ('        HorizontalFloatingToolbarSeparator()\n'
         '        val chamosNavCtx = androidx.compose.ui.platform.LocalContext.current\n'
         '        HorizontalFloatingToolbarItem(\n'
         '            icon = CompoundIcons.Link(),\n'
         '            tooltipLabel = "Navegador",\n'
         '            isSelected = false,\n'
         '            onClick = {\n'
         '                chamosNavCtx.startActivity(\n'
         '                    android.content.Intent().setClassName(chamosNavCtx.packageName, "io.element.android.x.ChamosNavegadorActivity"),\n'
         '                )\n'
         '            },\n'
         '        )\n')
open(hv, "w", encoding="utf-8").write(s.replace(muro, muro + nuevo))

# 4) enlaces de los chats
mn = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesNode.kt")
s = open(mn, encoding="utf-8").read()
old = "            is PermalinkData.FallbackLink -> {\n                if (customTab) {\n"
assert s.count(old) == 1, "navegador: no se encontro FallbackLink"
new = ("            is PermalinkData.FallbackLink -> {\n"
       "                if (url.startsWith(\"http://\") || url.startsWith(\"https://\")) {\n"
       "                    // Chamos: los enlaces se abren en el navegador de Chamos, sin salir de la app.\n"
       "                    activity.startActivity(\n"
       "                        android.content.Intent()\n"
       "                            .setClassName(activity.packageName, \"io.element.android.x.ChamosNavegadorActivity\")\n"
       "                            .putExtra(\"url\", url),\n"
       "                    )\n"
       "                } else if (customTab) {\n")
open(mn, "w", encoding="utf-8").write(s.replace(old, new))
print("navegador OK")
