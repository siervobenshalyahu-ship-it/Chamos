# Al salir de la pantalla de la llamada (boton atras) se cuelga de verdad: se le avisa a la sala que te fuiste,
# para que no quede la llamada "abierta" con el boton Unirse. Uso: python3 colgar_al_salir.py RAIZ_DEL_REPO
import sys, os
root = sys.argv[1]
f = os.path.join(root, "features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/ElementCallActivity.kt")
s = open(f, encoding="utf-8").read()
ancla = "        pictureInPicturePresenter.setPipView(this)\n"
assert s.count(ancla) == 1, "no se encontro el ancla de ElementCallActivity"
nuevo = ancla + """
        // Chamos: el boton atras cuelga la llamada (antes la dejaba abierta en la sala)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val sink = eventSink
                    if (sink != null) sink.invoke(CallScreenEvent.Hangup) else finish()
                }
            }
        )
"""
s = s.replace(ancla, nuevo, 1)
open(f, "w", encoding="utf-8").write(s)
print("colgar_al_salir OK")
