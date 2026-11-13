package io.element.android.x

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Navegador interno de Chamos: sirve para abrir lo que te nombran en un chat sin salir de la app.
 * Pantalla propia (no usa Compose) para que sea liviana y no dependa de otras partes de la app.
 * Se abre con: Intent().setClassName(paquete, "io.element.android.x.ChamosNavegadorActivity").putExtra("url", enlace)
 */
class ChamosNavegadorActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var barra: ProgressBar
    private lateinit var direccion: EditText
    private lateinit var atras: TextView
    private lateinit var adelante: TextView
    private var vistaCompleta: View? = null
    private var avisoCompleta: WebChromeClient.CustomViewCallback? = null

    private val fondo = Color.parseColor("#0F1530")
    private val panel = Color.parseColor("#1B2347")
    private val neon = Color.parseColor("#22D3EE")
    private val inicio = "https://chamos.local/"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun boton(texto: String, accion: () -> Unit): TextView = TextView(this).apply {
        text = texto
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { accion() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contenido = LinearLayout(this)
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setBackgroundColor(fondo)
        ViewCompat.setOnApplyWindowInsetsListener(contenido) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // ---- barra de arriba: cerrar + dirección ----
        val cabecera = LinearLayout(this)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = Gravity.CENTER_VERTICAL
        cabecera.setPadding(dp(6), dp(8), dp(10), dp(6))

        direccion = EditText(this)
        direccion.hint = "Busca o escribe una dirección"
        direccion.setHintTextColor(Color.parseColor("#8A93B8"))
        direccion.setTextColor(Color.WHITE)
        direccion.textSize = 15f
        direccion.isSingleLine = true
        direccion.setSelectAllOnFocus(true)
        direccion.imeOptions = EditorInfo.IME_ACTION_GO
        direccion.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        direccion.setPadding(dp(16), dp(9), dp(16), dp(9))
        val pildora = GradientDrawable()
        pildora.setColor(panel)
        pildora.cornerRadius = dp(24).toFloat()
        pildora.setStroke(dp(2), neon)
        direccion.background = pildora
        direccion.setOnEditorActionListener { _, accion, evento ->
            if (accion == EditorInfo.IME_ACTION_GO || (evento != null && evento.keyCode == KeyEvent.KEYCODE_ENTER && evento.action == KeyEvent.ACTION_DOWN)) {
                ir(direccion.text.toString())
                true
            } else {
                false
            }
        }
        // La barra de arriba ya no se muestra (menos barras); la caja de direccion queda oculta y se busca desde la pagina de inicio.

        // ---- progreso ----
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        barra.max = 100
        barra.progressTintList = ColorStateList.valueOf(neon)
        barra.visibility = View.INVISIBLE
        contenido.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // ---- la página ----
        web = WebView(this)
        configurarWeb()
        contenido.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- barra de abajo ----
        val pie = LinearLayout(this)
        pie.orientation = LinearLayout.HORIZONTAL
        pie.setBackgroundColor(panel)
        atras = boton("⬅️") { if (web.canGoBack()) web.goBack() }
        adelante = boton("➡️") { if (web.canGoForward()) web.goForward() }
        val recargar = boton("🔄") { web.reload() }
        val casa = boton("🏠") { cargarInicio() }
        val compartir = boton("📤") { compartirEnlace() }
        val cerrar = boton("✕") { finish() }
        val fuera = android.widget.FrameLayout(this)
        fuera.clipChildren = false
        fuera.addView(boton("🌐") { abrirFuera() }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val gorra = TextView(this)
        gorra.text = "🧢"
        gorra.textSize = 14f
        gorra.scaleX = -1f  // al reves: la visera mira hacia atras
        gorra.translationY = -dp(4).toFloat()
        fuera.addView(gorra, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (b in listOf(cerrar, atras, adelante, recargar, casa, compartir, fuera)) {
            pie.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        contenido.addView(pie, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(contenido)

        val enlace = intent?.getStringExtra("url")
        if (enlace != null && (enlace.startsWith("http://") || enlace.startsWith("https://"))) {
            direccion.setText(enlace)
            web.loadUrl(enlace)
        } else {
            cargarInicio()
        }
        actualizarBotones()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configurarWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        // Muchos sitios (y el inicio de sesión de Google) se portan mal si ven "; wv" en el identificador.
        s.userAgentString = s.userAgentString.replace("; wv", "")
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true

        web.setBackgroundColor(fondo)
        web.setDownloadListener { url, _, _, _, _ -> if (url != null) externo(Uri.parse(url)) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                if (u.host == "chamos.local") {
                    when (u.path) {
                        "/bancos" -> cargarBancos()
                        "/fuera" -> u.getQueryParameter("u")?.let { externo(Uri.parse(it)) }
                        else -> cargarInicio()
                    }
                    return true
                }
                return when (u.scheme?.lowercase()) {
                    "http", "https", "about" -> false
                    "tel", "mailto", "sms", "smsto", "whatsapp", "tg", "market", "geo" -> {
                        if (request.hasGesture()) externo(u)
                        true
                    }
                    else -> true // file:, content:, javascript:, intent:, data:… no se abren
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val u = url ?: return
                if (!direccion.hasFocus()) direccion.setText(if (u.startsWith(inicio)) "" else u)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) mostrarError(request.url.toString(), error?.description?.toString())
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.cancel()
                mostrarError(error?.url ?: "", "El certificado de seguridad del sitio no es válido")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                actualizarBotones()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                actualizarBotones()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                barra.progress = newProgress
                barra.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || vistaCompleta != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                vistaCompleta = view
                avisoCompleta = callback
                (window.decorView as ViewGroup).addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }

            override fun onHideCustomView() {
                quitarPantallaCompleta()
            }
        }
    }

    private fun quitarPantallaCompleta() {
        val v = vistaCompleta ?: return
        (window.decorView as ViewGroup).removeView(v)
        vistaCompleta = null
        avisoCompleta?.onCustomViewHidden()
        avisoCompleta = null
    }

    private fun actualizarBotones() {
        atras.alpha = if (web.canGoBack()) 1f else 0.35f
        adelante.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    /** Lo que escribes: una dirección (se abre) o palabras (se buscan en Google). */
    private fun ir(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        val destino = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(direccion.windowToken, 0)
        direccion.clearFocus()
        web.loadUrl(destino)
    }

    private fun urlActual(): String? {
        val u = web.url ?: return null
        return if (u.startsWith("http://") || u.startsWith("https://")) {
            if (u.startsWith(inicio)) null else u
        } else {
            null
        }
    }

    private fun compartirEnlace() {
        val u = urlActual()
        if (u == null) {
            Toast.makeText(this, "Abre una página primero", Toast.LENGTH_SHORT).show()
            return
        }
        val envio = Intent(Intent.ACTION_SEND)
        envio.type = "text/plain"
        envio.putExtra(Intent.EXTRA_TEXT, u)
        startActivity(Intent.createChooser(envio, "Compartir enlace"))
    }

    private fun abrirFuera() {
        val u = urlActual()
        if (u == null) {
            Toast.makeText(this, "Abre una página primero", Toast.LENGTH_SHORT).show()
            return
        }
        externo(Uri.parse(u))
    }

    private fun externo(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay una app para abrir esto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cargarInicio() {
        direccion.setText("")
        web.loadDataWithBaseURL(inicio, PAGINA_INICIO, "text/html", "utf-8", null)
    }

    private fun mostrarError(url: String, motivo: String?) {
        if (url.startsWith("https://chamos.local") || url.isEmpty()) return
        val m = (motivo ?: "No se pudo abrir").replace("<", "&lt;")
        val u = Uri.encode(url)
        web.loadDataWithBaseURL(inicio + "error", "<!doctype html><html lang=\"es\"><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{background:#0B1026;color:#fff;font-family:sans-serif;padding:28px;text-align:center}a{display:inline-block;margin:10px;padding:12px 20px;border-radius:24px;border:2px solid #22D3EE;color:#22D3EE;text-decoration:none}</style></head><body>" +
            "<h2>No se pudo abrir esta página</h2><p>" + m + "</p>" +
            "<a href=\"https://chamos.local/fuera?u=" + u + "\">Abrir en tu navegador</a><a href=\"https://chamos.local/\">Inicio</a></body></html>",
            "text/html", "utf-8", null)
    }

    private fun cargarBancos() {
        direccion.setText("")
        web.loadDataWithBaseURL(inicio + "bancos", PAGINA_BANCOS, "text/html", "utf-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            vistaCompleta != null -> quitarPantallaCompleta()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        quitarPantallaCompleta()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        // Los logos son los de cada sitio (se piden en línea); si alguno no carga, se ve el emoji.
        const val ESTILO = """<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box}
body{margin:0;padding:26px 16px 30px;background:radial-gradient(circle at 50% -10%,#1d2a66 0,#0F1530 55%);color:#fff;font-family:sans-serif;text-align:center}
h1{margin:4px 0 2px;font-size:30px;color:#22D3EE;text-shadow:0 0 12px #22D3EE,0 0 26px #1E90FF}
p{margin:0 0 20px;color:#9AA3C7;font-size:14px}
a{color:#fff;text-decoration:none}
.g{display:grid;grid-template-columns:repeat(3,1fr);gap:14px 10px;max-width:480px;margin:0 auto}
.g a{display:block;padding:14px 2px 10px;border-radius:18px;background:linear-gradient(160deg,#222c5a,#161d42);border:1px solid #2B3566;font-size:13px}
i{display:flex;width:58px;height:58px;margin:0 auto 9px;border-radius:50%;background:#fff;align-items:center;justify-content:center;font-style:normal;font-size:28px;box-shadow:0 0 0 2px #22D3EE,0 0 16px rgba(34,211,238,.55)}
i img{width:36px;height:36px;border-radius:8px}
small{display:block;margin-top:24px;color:#6C76A0;font-size:12px}
</style>"""

        const val PAGINA_INICIO = """<!doctype html><html lang="es"><head>""" + ESTILO + """
<style>
form{margin:0 auto 18px;max-width:480px;display:flex;gap:8px}
input{flex:1;min-width:0;padding:13px 16px;border-radius:26px;border:2px solid #22D3EE;background:#1B2347;color:#fff;font-size:16px;outline:none}
button{padding:0 18px;border-radius:26px;border:0;background:#22D3EE;color:#0F1530;font-weight:bold;font-size:16px}
.t{display:flex;justify-content:space-between;align-items:center;max-width:480px;margin:0 auto 18px;padding:14px 18px;border-radius:18px;background:linear-gradient(160deg,#17503f,#0f2f2c);border:1px solid #1f7a5f;font-size:15px}
.t b{font-size:20px;color:#7CFFB2;text-shadow:0 0 10px rgba(124,255,178,.6)}
</style></head><body>
<h1>Chamos <span style="position:relative;display:inline-block">🌐<span style="position:absolute;left:50%;top:-.55em;font-size:.7em;transform:translateX(-50%) scaleX(-1)">🧢</span></span></h1>
<p>Busca lo que te nombraron sin salir del chat</p>
<form action="https://www.google.com/search" method="get">
<input name="q" type="search" placeholder="Busca en Google" autocomplete="off">
<button type="submit">Ir</button>
</form>
<a class="t" href="https://www.bcv.org.ve"><span>💵 Dólar BCV</span><span style="text-align:right"><b id="bcv">ver hoy ›</b><small id="bcvf" style="display:block;margin:2px 0 0;color:#9FE3BE;font-size:11px"></small></span></a>
<div class="g">
<a href="https://www.bibliatodo.com/interlineal/online"><i><img data-e="📖" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=bibliatodo.com&sz=128"></i>Biblia interlineal</a>
<a href="https://m.youtube.com"><i><img data-e="▶️" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=youtube.com&sz=128"></i>YouTube</a>
<a href="https://maps.google.com"><i><img data-e="🗺️" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=maps.google.com&sz=128"></i>Mapas</a>
<a href="https://chamos.local/bancos"><i>🏦</i>Bancos</a>
<a href="https://www.patria.org.ve"><i><img data-e="🇻🇪" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=patria.org.ve&sz=128"></i>Patria</a>
<a href="https://translate.google.com"><i><img data-e="🌍" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=translate.google.com&sz=128"></i>Traductor</a>
<a href="https://news.google.com/?hl=es-419&amp;gl=VE&amp;ceid=VE:es-419"><i><img data-e="📰" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain=news.google.com&sz=128"></i>Noticias</a>
<a href="https://www.google.com/search?q=clima"><i>⛅</i>Clima</a>
<a href="https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/"><i>🇻🇪</i>Chamos</a>
</div>
<small>Navegador de Chamos · no guardamos tu historial</small>
<script>
(function(){
  var el=document.getElementById('bcv'),sub=document.getElementById('bcvf');
  function poner(j){
    if(!j||!j.usd)return;
    el.textContent='Bs. '+Number(j.usd).toFixed(2).replace('.',',');
    if(j.fecha&&/^\d{4}-\d{2}-\d{2}/.test(j.fecha)){sub.textContent='valor del '+j.fecha.slice(8,10)+'/'+j.fecha.slice(5,7)}
  }
  function leer(){
    try{
      var c=new AbortController();setTimeout(function(){c.abort()},8000);
      fetch('https://chamos-push.siervodeyhwh57.workers.dev/dolar',{signal:c.signal})
        .then(function(r){return r.json()}).then(poner).catch(function(){});
    }catch(e){}
  }
  leer();setInterval(leer,300000);
})();
</script>
</body></html>"""

        const val PAGINA_BANCOS = """<!doctype html><html lang="es"><head>""" + ESTILO + """
<style>
.b{max-width:480px;margin:0 auto;display:grid;gap:10px}
.b a{display:flex;align-items:center;gap:14px;padding:12px 14px;border-radius:18px;background:linear-gradient(160deg,#222c5a,#161d42);border:1px solid #2B3566;text-align:left;font-size:16px}
.b i{width:46px;height:46px;margin:0;flex:none;font-size:22px}
.b span{display:block;font-size:12px;color:#9AA3C7;margin-top:2px}
.v{display:inline-block;margin-bottom:16px;color:#22D3EE;font-size:14px}
</style></head><body>
<a class="v" href="https://chamos.local/">‹ Inicio</a>
<h1>Bancos 🏦</h1>
<p>Accesos rápidos a la banca en línea. Para el pago móvil, usa la app de tu banco.</p>
<div class="b" id="lista"></div>
<small>Si algún enlace no abre, usa el botón 🌐 para verlo en tu navegador.</small>
<script>
var B=[
["Banco de Venezuela","bancodevenezuela.com","https://bdvenlinea.banvenez.com"],
["Banesco","banesco.com","https://www.banesco.com/"],
["Mercantil","mercantilbanco.com","https://www.mercantilbanco.com"],
["BBVA Provincial","provincial.com","https://www.provincial.com"],
["BNC","bnc.com.ve","https://www.bncenlinea.com"],
["Bancamiga","bancamiga.com","https://www.bancamiga.com/"],
["Banco del Tesoro","bt.com.ve","https://www.bt.com.ve/"],
["Bancaribe","bancaribe.com.ve","https://www.bancaribe.com.ve"],
["Banplus","banplus.com","https://www.banplus.com"],
["Banco Exterior","bancoexterior.com","https://www.bancoexterior.com"],
["100% Banco","100x100banco.com","https://www.100x100banco.com"],
["Banco Plaza","bancoplaza.com","https://www.bancoplaza.com"],
["Banco Digital de los Trabajadores","bdt.com.ve","https://www.bdt.com.ve"]
];
var h="";
for(var k=0;k<B.length;k++){
  h+='<a href="'+B[k][2]+'"><i><img data-e="🏦" onerror="this.parentNode.textContent=this.dataset.e" src="https://www.google.com/s2/favicons?domain='+B[k][1]+'&sz=128"></i><div>'+B[k][0]+'<span>'+B[k][1]+'</span></div></a>';
}
document.getElementById("lista").innerHTML=h;
</script>
</body></html>"""
    }
}
