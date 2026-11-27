#!/usr/bin/env python3
"""Sello del creador al final de Ajustes. Uso: sello.py <carpeta_app> <sello.png>"""
import os, shutil, sys

app, png = sys.argv[1], sys.argv[2]
mod = os.path.join(app, "features/preferences/impl/src/main")
p = os.path.join(mod, "kotlin/io/element/android/features/preferences/impl/root/PreferencesRootView.kt")
t = open(p, encoding="utf-8").read()

start = t.index("private fun ColumnScope.Footer(")
body = t.index(") {\n", start) + 4
marca = '''    // Chamos: marca del creador
    Image(
        painter = painterResource(id = R.drawable.marca_benshalyahu),
        contentDescription = "Benshalyahu, el hebreo de Venezuela",
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 16.dp)
            .size(112.dp),
    )
    Text(
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        textAlign = TextAlign.Center,
        text = "Chamos, creado por Benshalyahu, el hebreo de Venezuela",
        style = ElementTheme.typography.fontBodySmRegular,
        color = ElementTheme.colors.textSecondary,
    )
'''
t = t[:body] + marca + t[body:]

for imp in ("androidx.compose.foundation.Image", "androidx.compose.foundation.layout.size",
            "androidx.compose.ui.res.painterResource", "androidx.compose.ui.text.style.TextAlign",
            "io.element.android.features.preferences.impl.R"):
    if f"import {imp}\n" not in t:
        t = t.replace("\nimport ", f"\nimport {imp}\nimport ", 1)
open(p, "w", encoding="utf-8").write(t)

d = os.path.join(mod, "res/drawable-nodpi")
os.makedirs(d, exist_ok=True)
shutil.copy(png, os.path.join(d, "marca_benshalyahu.png"))
print("Sello del creador puesto en Ajustes")
