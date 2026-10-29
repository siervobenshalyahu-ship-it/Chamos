# Quita la barra de reacciones y "mano levantada" de las llamadas y videollamadas de Chamos.
# La llamada es la web de Element Call dentro de un WebView: al terminar de cargar se le inyecta un CSS que
# oculta el botón y el panel de reacciones. Uso: python3 sin_reacciones.py RAIZ_DEL_REPO
import sys, os

root = sys.argv[1]
path = os.path.join(root, "features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/CallScreenView.kt")
anchor = 'webView.evaluateJavascript("controls.onBackButtonPressed = () => { backHandler.onBackPressed() }", null)'
extra = '''
                        webView.evaluateJavascript(
                            """(function(){try{localStorage.setItem('matrix-setting-reactions-show','false');}catch(e){}if(document.getElementById('chamos-sin-reacciones'))return;var s=document.createElement('style');s.id='chamos-sin-reacciones';s.textContent='[class*="raiseHand"],[class*="reactionPopupMenu"]{display:none!important}';(document.head||document.documentElement).appendChild(s);})()""",
                            null
                        )'''

t = open(path, encoding="utf8").read()
if t.count(anchor) != 1:
    sys.exit("sin_reacciones: no se encontró el punto de enganche en CallScreenView.kt (%d veces)" % t.count(anchor))
if "chamos-sin-reacciones" in t:
    print("sin_reacciones: ya aplicado")
else:
    open(path, "w", encoding="utf8").write(t.replace(anchor, anchor + extra))
    print("sin_reacciones: aplicado")
