/*
 * Chamos: filtros de cámara, caras y memes para las videollamadas.
 *
 * Se inyecta dentro de la pantalla de llamada (Element Call, que corre en un WebView). Intercepta
 * getUserMedia: la cámara real se dibuja en un canvas con el filtro elegido y lo que sale hacia la
 * llamada es el canvas, así que los demás participantes también ven los efectos.
 *
 *  - Filtros de color: no necesitan nada más.
 *  - Filtros de cara: usan MediaPipe Face Landmarker, que se empaqueta en /chamos/mediapipe/ al compilar.
 *    Si no está, esa pestaña avisa y el resto sigue funcionando.
 *  - Memes: texto arriba y abajo, pegatinas de emojis y lluvia de emojis.
 */
(function () {
  "use strict";
  if (window.__chamosFiltros) return;
  var md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return;

  var BASE = location.origin + "/chamos/mediapipe/";
  var FPS = 24;

  var estado = { filtro: "normal", cara: "ninguna", arriba: "", abajo: "", pegatina: "", lluvia: false };
  try {
    var guardado = JSON.parse(localStorage.getItem("chamosFx") || "{}");
    if (guardado.filtro) estado.filtro = guardado.filtro;
    if (guardado.cara) estado.cara = guardado.cara;
  } catch (e) { /* sin almacenamiento: no pasa nada */ }
  function guardar() {
    try { localStorage.setItem("chamosFx", JSON.stringify({ filtro: estado.filtro, cara: estado.cara })); } catch (e) { /* nada */ }
  }

  /* ------------------------------------------------------------------ filtros de color */
  var FILTROS = [
    { id: "normal", n: "Normal", e: "🙂" },
    { id: "bn", n: "Blanco y negro", e: "🎞️", css: "grayscale(1) contrast(1.15)" },
    { id: "sepia", n: "Sepia", e: "📜", css: "sepia(1) contrast(1.05)" },
    { id: "neon", n: "Neón", e: "🌈", css: "saturate(2.8) contrast(1.3) hue-rotate(-25deg) brightness(1.05)" },
    { id: "retro", n: "Retro", e: "📼", css: "sepia(.55) saturate(1.8) hue-rotate(-30deg) contrast(1.1)" },
    { id: "frio", n: "Hielo", e: "🧊", css: "hue-rotate(160deg) saturate(1.5) brightness(1.05)" },
    { id: "caricatura", n: "Caricatura", e: "🎨", css: "contrast(1.9) saturate(1.9) brightness(1.05)" },
    { id: "invertido", n: "Invertido", e: "👽", css: "invert(1) hue-rotate(180deg)" },
    { id: "pixel", n: "Pixelado", e: "👾", pixel: 14 },
    { id: "espejo", n: "Espejo", e: "🪞", espejo: true },
    { id: "borroso", n: "Suave", e: "☁️", css: "blur(3px) brightness(1.08) saturate(1.2)" }
  ];

  /* ------------------------------------------------------------------ detector de caras */
  var lm = null;
  var lmEstado = "no"; // no | cargando | listo | fallo
  var ultima = { t: 0, caras: [] };

  function cargarDetector() {
    if (lmEstado !== "no") return;
    lmEstado = "cargando";
    refrescarUI();
    fetch(BASE + "vision_bundle.cjs")
      .then(function (r) { if (!r.ok) throw new Error("sin librería"); return r.text(); })
      .then(function (codigo) {
        var m = { exports: {} };
        new Function("module", "exports", codigo)(m, m.exports);
        var V = m.exports;
        if (!V || !V.FilesetResolver || !V.FaceLandmarker) throw new Error("librería inesperada");
        return V.FilesetResolver.forVisionTasks(BASE + "wasm").then(function (fileset) {
          function crear(delegado) {
            return V.FaceLandmarker.createFromOptions(fileset, {
              baseOptions: { modelAssetPath: BASE + "face_landmarker.task", delegate: delegado },
              runningMode: "VIDEO",
              numFaces: 2
            });
          }
          return crear("GPU").catch(function () { return crear("CPU"); });
        });
      })
      .then(function (d) { lm = d; lmEstado = "listo"; refrescarUI(); })
      .catch(function (e) { lmEstado = "fallo"; try { console.warn("Chamos filtros: no hay detector de caras", e && e.message); } catch (x) { /* nada */ } refrescarUI(); });
  }

  function P(pts, i, w, h) { return { x: pts[i].x * w, y: pts[i].y * h }; }
  function medio(a, b) { return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; }
  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

  /** Convierte los puntos de MediaPipe (0..1) en medidas en píxeles. */
  function medir(pts, w, h) {
    var a = medio(P(pts, 33, w, h), P(pts, 133, w, h));
    var b = medio(P(pts, 263, w, h), P(pts, 362, w, h));
    var izq = a.x <= b.x ? a : b, der = a.x <= b.x ? b : a;
    var frente = P(pts, 10, w, h), barbilla = P(pts, 152, w, h);
    return {
      izq: izq, der: der, ojos: medio(izq, der),
      nariz: P(pts, 1, w, h), labio: P(pts, 0, w, h), boca: medio(P(pts, 13, w, h), P(pts, 14, w, h)),
      frente: frente, barbilla: barbilla,
      ancho: dist(P(pts, 234, w, h), P(pts, 454, w, h)),
      alto: dist(frente, barbilla),
      ang: Math.atan2(der.y - izq.y, der.x - izq.x)
    };
  }
  function suavizar(prev, cur) {
    if (!prev) return cur;
    var k = 0.55, o = {};
    for (var key in cur) {
      var c = cur[key], p = prev[key];
      if (typeof c === "number") o[key] = p + (c - p) * k;
      else o[key] = { x: p.x + (c.x - p.x) * k, y: p.y + (c.y - p.y) * k };
    }
    return o;
  }
  function detectar(video, w, h, ahora) {
    if (lmEstado !== "listo") return [];
    if (ahora - ultima.t > 55) {
      try {
        var r = lm.detectForVideo(video, ahora);
        var lista = (r && r.faceLandmarks) || [];
        ultima = { t: ahora, caras: lista.map(function (pts, i) { return suavizar(ultima.caras[i], medir(pts, w, h)); }) };
      } catch (e) { ultima = { t: ahora, caras: [] }; }
    }
    return ultima.caras;
  }

  /* ------------------------------------------------------------------ dibujos de cara */
  function sobre(ctx, cx, cy, ang, fn) { ctx.save(); ctx.translate(cx, cy); ctx.rotate(ang); fn(); ctx.restore(); }

  var CARAS = [
    { id: "ninguna", n: "Sin filtro", e: "🚫" },
    { id: "gafas", n: "Gafas", e: "😎", dibujar: function (ctx, c) {
      var d = dist(c.izq, c.der), rx = d * 0.42, ry = d * 0.31;
      sobre(ctx, c.ojos.x, c.ojos.y, c.ang, function () {
        ctx.fillStyle = "rgba(10,12,24,.92)"; ctx.strokeStyle = "#0a0a12"; ctx.lineWidth = Math.max(2, d * 0.05);
        [-d / 2, d / 2].forEach(function (x) {
          ctx.beginPath(); ctx.ellipse(x, 0, rx, ry, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
          ctx.fillStyle = "rgba(255,255,255,.35)"; ctx.beginPath(); ctx.ellipse(x - rx * 0.35, -ry * 0.35, rx * 0.22, ry * 0.12, -0.5, 0, Math.PI * 2); ctx.fill();
          ctx.fillStyle = "rgba(10,12,24,.92)";
        });
        ctx.beginPath(); ctx.moveTo(-d / 2 + rx, -ry * 0.1); ctx.lineTo(d / 2 - rx, -ry * 0.1); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(-d / 2 - rx, -ry * 0.2); ctx.lineTo(-c.ancho / 2, -ry * 0.3); ctx.moveTo(d / 2 + rx, -ry * 0.2); ctx.lineTo(c.ancho / 2, -ry * 0.3); ctx.stroke();
      });
    } },
    { id: "bigote", n: "Bigote", e: "🥸", dibujar: function (ctx, c) {
      var cx = c.nariz.x + (c.labio.x - c.nariz.x) * 0.55, cy = c.nariz.y + (c.labio.y - c.nariz.y) * 0.55, w = c.ancho * 0.5;
      sobre(ctx, cx, cy, c.ang, function () {
        ctx.fillStyle = "#2b1a10";
        [-1, 1].forEach(function (s) {
          ctx.beginPath(); ctx.moveTo(0, -w * 0.05);
          ctx.bezierCurveTo(s * w * 0.2, -w * 0.2, s * w * 0.65, -w * 0.12, s * w * 0.78, w * 0.12);
          ctx.bezierCurveTo(s * w * 0.55, w * 0.02, s * w * 0.25, w * 0.1, 0, w * 0.1);
          ctx.closePath(); ctx.fill();
        });
      });
    } },
    { id: "corona", n: "Corona", e: "👑", dibujar: function (ctx, c) {
      var w = c.ancho * 0.8, a = c.alto * 0.34;
      sobre(ctx, c.frente.x, c.frente.y - c.alto * 0.08, c.ang, function () {
        var g = ctx.createLinearGradient(0, -a, 0, 0); g.addColorStop(0, "#ffe27a"); g.addColorStop(1, "#d9a21b");
        ctx.fillStyle = g; ctx.strokeStyle = "#8a6208"; ctx.lineWidth = Math.max(2, w * 0.02);
        ctx.beginPath(); ctx.moveTo(-w / 2, 0); ctx.lineTo(-w / 2, -a * 0.55); ctx.lineTo(-w / 4, -a * 0.25); ctx.lineTo(0, -a); ctx.lineTo(w / 4, -a * 0.25); ctx.lineTo(w / 2, -a * 0.55); ctx.lineTo(w / 2, 0); ctx.closePath(); ctx.fill(); ctx.stroke();
        ["#e53950", "#2d7bff", "#e53950"].forEach(function (col, i) { ctx.fillStyle = col; ctx.beginPath(); ctx.arc((i - 1) * w * 0.28, -a * 0.2, w * 0.045, 0, Math.PI * 2); ctx.fill(); });
      });
    } },
    { id: "gorra", n: "Gorra tricolor", e: "🇻🇪", dibujar: function (ctx, c) {
      var rx = c.ancho * 0.56, ry = c.alto * 0.5;
      sobre(ctx, c.frente.x, c.frente.y + c.alto * 0.02, c.ang, function () {
        ctx.save();
        ctx.beginPath(); ctx.ellipse(0, 0, rx, ry, 0, Math.PI, Math.PI * 2); ctx.closePath(); ctx.clip();
        ctx.fillStyle = "#ffcc00"; ctx.fillRect(-rx, -ry, rx * 2, ry / 3);
        ctx.fillStyle = "#0b3d91"; ctx.fillRect(-rx, -ry * 2 / 3, rx * 2, ry / 3);
        ctx.fillStyle = "#cf142b"; ctx.fillRect(-rx, -ry / 3, rx * 2, ry / 3 + 2);
        ctx.restore();
        ctx.fillStyle = "#0a2a66"; ctx.beginPath(); ctx.ellipse(0, 2, rx * 1.18, ry * 0.16, 0, 0, Math.PI * 2); ctx.fill();
      });
    } },
    { id: "perro", n: "Perrito", e: "🐶", dibujar: function (ctx, c) {
      sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
        ctx.fillStyle = "#7a4a26"; ctx.strokeStyle = "#4a2a12"; ctx.lineWidth = Math.max(2, c.ancho * 0.02);
        [-1, 1].forEach(function (s) {
          ctx.save(); ctx.translate(s * c.ancho * 0.36, -c.alto * 0.12); ctx.rotate(s * 0.35);
          ctx.beginPath(); ctx.ellipse(0, 0, c.ancho * 0.13, c.alto * 0.24, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke(); ctx.restore();
        });
      });
      ctx.fillStyle = "#1b1410"; ctx.beginPath(); ctx.ellipse(c.nariz.x, c.nariz.y, c.ancho * 0.09, c.ancho * 0.065, c.ang, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "rgba(255,255,255,.5)"; ctx.beginPath(); ctx.ellipse(c.nariz.x - c.ancho * 0.03, c.nariz.y - c.ancho * 0.02, c.ancho * 0.025, c.ancho * 0.015, c.ang, 0, Math.PI * 2); ctx.fill();
    } },
    { id: "payaso", n: "Payaso", e: "🤡", dibujar: function (ctx, c) {
      sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
        ["#ff7a1a", "#2fb5ff"].forEach(function (col, i) {
          ctx.fillStyle = col;
          [-1, 1].forEach(function (s) { ctx.beginPath(); ctx.arc(s * c.ancho * (0.42 + i * 0.1), c.alto * (0.02 + i * 0.12), c.ancho * 0.13, 0, Math.PI * 2); ctx.fill(); });
        });
      });
      var r = c.ancho * 0.1;
      ctx.fillStyle = "#e5202c"; ctx.beginPath(); ctx.arc(c.nariz.x, c.nariz.y, r, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "rgba(255,255,255,.6)"; ctx.beginPath(); ctx.arc(c.nariz.x - r * 0.3, c.nariz.y - r * 0.3, r * 0.28, 0, Math.PI * 2); ctx.fill();
    } }
  ];

  /* ------------------------------------------------------------------ memes */
  var MEMES = [
    { n: "Ni a palos", a: "NI A PALOS", b: "" },
    { n: "Todo bien", a: "ESTO ESTÁ BIEN", b: "TODO BIEN, CHAMO", p: "🔥" },
    { n: "Qué vaina", a: "¡QUÉ VAINA!", b: "", p: "😱" },
    { n: "Fino, fino", a: "", b: "FINO, FINO", p: "😎" },
    { n: "Ay, mi madre", a: "AY, MI MADRE", b: "", p: "💀" },
    { n: "Épale", a: "¡ÉPALE!", b: "¿QUÉ HAY DE NUEVO?", p: "👋" }
  ];
  var PEGATINAS = ["😂", "💀", "🔥", "❤️", "🇻🇪", "😎", "🤡", "👑", "😭", "🤯"];
  var LLUVIA = ["🇻🇪", "😂", "🔥", "💛", "💙", "❤️"];
  var gotas = [];

  function dibujarTexto(ctx, texto, w, h, abajo) {
    if (!texto) return;
    texto = texto.toUpperCase();
    var tam = h * 0.1;
    ctx.textAlign = "center"; ctx.textBaseline = abajo ? "bottom" : "top"; ctx.lineJoin = "round";
    do { ctx.font = "900 " + tam + "px Impact, 'Arial Black', Arial, sans-serif"; tam -= 2; } while (ctx.measureText(texto).width > w * 0.94 && tam > h * 0.035);
    ctx.lineWidth = Math.max(3, tam * 0.16); ctx.strokeStyle = "#000"; ctx.fillStyle = "#fff";
    var y = abajo ? h - h * 0.04 : h * 0.04;
    ctx.strokeText(texto, w / 2, y); ctx.fillText(texto, w / 2, y);
  }
  function dibujarMemes(ctx, w, h, ahora, caras) {
    dibujarTexto(ctx, estado.arriba, w, h, false);
    dibujarTexto(ctx, estado.abajo, w, h, true);
    if (estado.pegatina) {
      var tam = h * 0.2, bote = Math.sin(ahora / 260) * h * 0.012, x = w * 0.8, y = h * 0.28;
      if (caras.length) { x = caras[0].frente.x; y = caras[0].frente.y - caras[0].alto * 0.45; tam = Math.max(h * 0.12, caras[0].ancho * 0.45); }
      ctx.font = tam + "px 'Noto Color Emoji', 'Apple Color Emoji', 'Segoe UI Emoji', sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillStyle = "#fff"; ctx.fillText(estado.pegatina, x, y + bote);
    }
    if (estado.lluvia) {
      while (gotas.length < 16) gotas.push({ x: Math.random() * w, y: -Math.random() * h, v: h * (0.25 + Math.random() * 0.35), e: LLUVIA[Math.floor(Math.random() * LLUVIA.length)], s: h * (0.07 + Math.random() * 0.06) });
      ctx.textAlign = "center"; ctx.textBaseline = "middle";
      gotas.forEach(function (g) {
        g.y += g.v / FPS;
        if (g.y > h + g.s) { g.y = -g.s; g.x = Math.random() * w; }
        ctx.font = g.s + "px 'Noto Color Emoji', 'Apple Color Emoji', sans-serif"; ctx.fillText(g.e, g.x, g.y);
      });
    } else if (gotas.length) { gotas = []; }
  }

  /* ------------------------------------------------------------------ cuadro a cuadro */
  var chico = document.createElement("canvas");
  function cuadro(ctx, video, w, h) {
    var ahora = performance.now();
    var f = null;
    for (var i = 0; i < FILTROS.length; i++) if (FILTROS[i].id === estado.filtro) f = FILTROS[i];
    f = f || FILTROS[0];
    ctx.save();
    if (f.espejo) { ctx.translate(w, 0); ctx.scale(-1, 1); }
    if (f.pixel) {
      chico.width = Math.max(8, Math.round(w / f.pixel)); chico.height = Math.max(8, Math.round(h / f.pixel));
      chico.getContext("2d").drawImage(video, 0, 0, chico.width, chico.height);
      ctx.imageSmoothingEnabled = false; ctx.drawImage(chico, 0, 0, w, h); ctx.imageSmoothingEnabled = true;
    } else {
      if (f.css) ctx.filter = f.css;
      ctx.drawImage(video, 0, 0, w, h);
      ctx.filter = "none";
    }
    var caras = [];
    if (estado.cara !== "ninguna" || estado.pegatina) caras = detectar(video, w, h, ahora);
    if (estado.cara !== "ninguna") {
      var dib = null;
      for (var k = 0; k < CARAS.length; k++) if (CARAS[k].id === estado.cara) dib = CARAS[k].dibujar;
      if (dib) caras.forEach(function (c) { dib(ctx, c); });
    }
    ctx.restore();
    dibujarMemes(ctx, w, h, ahora, caras);
  }

  /* ------------------------------------------------------------------ cámara */
  var sesiones = 0;
  var original = md.getUserMedia.bind(md);

  async function procesar(stream, vt) {
    var video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.setAttribute("playsinline", "");
    video.srcObject = new MediaStream([vt]);
    try { await video.play(); } catch (e) { /* se reintenta al dibujar */ }
    if (!video.videoWidth) await new Promise(function (res) { video.onloadedmetadata = res; setTimeout(res, 1500); });
    var aj = vt.getSettings ? vt.getSettings() : {};
    var canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || aj.width || 640;
    canvas.height = video.videoHeight || aj.height || 480;
    var ctx = canvas.getContext("2d", { alpha: false });
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    var salida = canvas.captureStream(FPS);
    var pista = salida.getVideoTracks()[0];
    if (!pista) return stream;

    var activo = true;
    function dibujar() {
      if (!activo) return;
      var vw = video.videoWidth, vh = video.videoHeight;
      if (vw && vh && (vw !== canvas.width || vh !== canvas.height)) { canvas.width = vw; canvas.height = vh; }
      if (video.readyState >= 2) { try { cuadro(ctx, video, canvas.width, canvas.height); } catch (e) { /* un cuadro malo no tumba la llamada */ } }
    }
    var timer = setInterval(dibujar, Math.round(1000 / FPS));
    var pararOrig = pista.stop.bind(pista);
    function parar() {
      if (!activo) return;
      activo = false; clearInterval(timer);
      try { vt.stop(); } catch (e) { /* nada */ }
      video.srcObject = null;
      sesiones = Math.max(0, sesiones - 1);
      if (!sesiones) quitarUI();
    }
    pista.stop = function () { pararOrig(); parar(); };
    vt.addEventListener("ended", function () { pararOrig(); parar(); });
    pista.getSettings = function () { return Object.assign({}, vt.getSettings ? vt.getSettings() : {}, { width: canvas.width, height: canvas.height, frameRate: FPS }); };
    pista.getCapabilities = function () { return vt.getCapabilities ? vt.getCapabilities() : {}; };
    pista.applyConstraints = function (c) { return vt.applyConstraints ? vt.applyConstraints(c) : Promise.resolve(); };

    sesiones++;
    montarUI();
    return new MediaStream([pista].concat(stream.getAudioTracks()));
  }

  md.getUserMedia = async function (constraints) {
    var stream = await original(constraints);
    if (!constraints || !constraints.video) return stream;
    var vt = stream.getVideoTracks()[0];
    if (!vt) return stream;
    try { return await procesar(stream, vt); } catch (e) {
      try { console.warn("Chamos filtros: se usa la cámara sin efectos", e && e.message); } catch (x) { /* nada */ }
      return stream;
    }
  };

  /* ------------------------------------------------------------------ panel */
  var host = null, raiz = null, pestana = "filtros", abierto = false;

  var CSS = [
    ":host{all:initial}",
    "*{box-sizing:border-box;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}",
    "#fab{position:fixed;left:12px;bottom:110px;width:52px;height:52px;border-radius:50%;border:2px solid #f4b54a;background:rgba(12,16,32,.88);color:#fff;font-size:26px;line-height:1;box-shadow:0 0 14px rgba(244,181,74,.6);touch-action:manipulation}",
    "#panel{position:fixed;left:8px;right:8px;bottom:172px;max-width:430px;max-height:48vh;overflow:auto;background:rgba(12,16,32,.95);color:#fff;border:1px solid #2a3558;border-radius:16px;padding:10px;box-shadow:0 14px 40px rgba(0,0,0,.55)}",
    "#panel[hidden]{display:none}",
    ".tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-bottom:10px}",
    ".tabs button{padding:9px 4px;border-radius:10px;border:0;background:#1b2543;color:#cfd6e6;font-weight:800;font-size:13px}",
    ".tabs button.on{background:#f4b54a;color:#1a1205}",
    ".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}",
    ".it{display:flex;flex-direction:column;align-items:center;gap:2px;padding:8px 4px;border-radius:12px;border:2px solid transparent;background:#1b2543;color:#fff;font-size:11px;font-weight:700;text-align:center;min-height:62px;justify-content:center}",
    ".it b{font-size:24px;font-weight:400;line-height:1}",
    ".it.on{border-color:#f4b54a;background:#2a3558}",
    ".nota{color:#cfd6e6;font-size:12px;margin:0 0 8px}",
    "h4{margin:12px 0 6px;font-size:12px;color:#f4b54a;text-transform:uppercase;letter-spacing:.08em}",
    "input{width:100%;padding:9px 10px;margin-bottom:6px;border-radius:10px;border:1px solid #2a3558;background:#0b1020;color:#fff;font-size:15px}",
    ".fila{display:flex;gap:8px;flex-wrap:wrap}",
    ".fila button{padding:9px 12px;border-radius:10px;border:0;background:#1b2543;color:#fff;font-weight:700;font-size:13px}",
    ".fila button.on{background:#f4b54a;color:#1a1205}",
    ".fila .peg{font-size:22px;padding:6px 10px}"
  ].join("");

  function esc(t) { return String(t).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function html() {
    var h = '<button id="fab" aria-label="Filtros y memes">✨</button><div id="panel"' + (abierto ? "" : " hidden") + ">";
    h += '<div class="tabs">' + [["filtros", "Filtros"], ["caras", "Caras"], ["memes", "Memes"]].map(function (t) {
      return '<button data-tab="' + t[0] + '" class="' + (pestana === t[0] ? "on" : "") + '">' + t[1] + "</button>";
    }).join("") + "</div>";
    if (pestana === "filtros") {
      h += '<div class="grid">' + FILTROS.map(function (f) {
        return '<button class="it ' + (estado.filtro === f.id ? "on" : "") + '" data-filtro="' + f.id + '"><b>' + f.e + "</b>" + esc(f.n) + "</button>";
      }).join("") + "</div>";
    } else if (pestana === "caras") {
      if (lmEstado === "cargando") h += '<p class="nota">Cargando el detector de caras…</p>';
      if (lmEstado === "fallo") h += '<p class="nota">Los filtros de cara no están disponibles en esta versión o en este teléfono. Los filtros de color y los memes sí funcionan.</p>';
      h += '<div class="grid">' + CARAS.map(function (f) {
        return '<button class="it ' + (estado.cara === f.id ? "on" : "") + '" data-cara="' + f.id + '"' + (f.id !== "ninguna" && lmEstado === "fallo" ? " disabled" : "") + "><b>" + f.e + "</b>" + esc(f.n) + "</button>";
      }).join("") + "</div>";
    } else {
      h += '<div class="grid">' + MEMES.map(function (m, i) {
        return '<button class="it" data-meme="' + i + '"><b>' + (m.p || "💬") + "</b>" + esc(m.n) + "</button>";
      }).join("") + "</div>";
      h += "<h4>Tu propio texto</h4>";
      h += '<input id="ta" maxlength="40" placeholder="Texto de arriba" value="' + esc(estado.arriba) + '"><input id="tb" maxlength="40" placeholder="Texto de abajo" value="' + esc(estado.abajo) + '">';
      h += '<div class="fila"><button id="poner">Poner texto</button><button id="quitar">Quitar memes</button></div>';
      h += "<h4>Pegatinas</h4>";
      h += '<div class="fila">' + PEGATINAS.map(function (p) { return '<button class="peg ' + (estado.pegatina === p ? "on" : "") + '" data-peg="' + p + '">' + p + "</button>"; }).join("") + "</div>";
      h += '<h4>Lluvia</h4><div class="fila"><button id="lluvia" class="' + (estado.lluvia ? "on" : "") + '">' + (estado.lluvia ? "Lluvia de emojis: sí" : "Lluvia de emojis: no") + "</button></div>";
    }
    return h + "</div>";
  }

  function refrescarUI() {
    if (!raiz) return;
    var ta = raiz.getElementById("ta"), tb = raiz.getElementById("tb");
    var va = ta ? ta.value : null, vb = tb ? tb.value : null;
    var panel = raiz.getElementById("panel"), arriba = panel ? panel.scrollTop : 0;
    raiz.innerHTML = "<style>" + CSS + "</style>" + html();
    if (va !== null && raiz.getElementById("ta")) { raiz.getElementById("ta").value = va; raiz.getElementById("tb").value = vb; }
    if (raiz.getElementById("panel")) raiz.getElementById("panel").scrollTop = arriba;
  }

  function clic(e) {
    e.stopPropagation();
    var t = e.target.closest ? e.target.closest("button") : null;
    if (!t) return;
    if (t.id === "fab") { abierto = !abierto; refrescarUI(); return; }
    if (t.dataset.tab) { pestana = t.dataset.tab; if (pestana === "caras") cargarDetector(); refrescarUI(); return; }
    if (t.dataset.filtro) { estado.filtro = t.dataset.filtro; guardar(); }
    else if (t.dataset.cara) { estado.cara = t.dataset.cara; if (estado.cara !== "ninguna") cargarDetector(); guardar(); }
    else if (t.dataset.meme) { var m = MEMES[+t.dataset.meme]; estado.arriba = m.a; estado.abajo = m.b; if (m.p) estado.pegatina = m.p; }
    else if (t.dataset.peg) { estado.pegatina = estado.pegatina === t.dataset.peg ? "" : t.dataset.peg; }
    else if (t.id === "poner") { estado.arriba = raiz.getElementById("ta").value.slice(0, 40); estado.abajo = raiz.getElementById("tb").value.slice(0, 40); }
    else if (t.id === "quitar") { estado.arriba = ""; estado.abajo = ""; estado.pegatina = ""; estado.lluvia = false; }
    else if (t.id === "lluvia") { estado.lluvia = !estado.lluvia; }
    refrescarUI();
  }

  function montarUI() {
    if (host) return;
    var poner = function () {
      host = document.createElement("div");
      host.style.cssText = "position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647";
      raiz = host.attachShadow({ mode: "open" });
      ["click", "pointerdown", "pointerup", "touchstart", "touchend", "mousedown", "mouseup"].forEach(function (n) {
        raiz.addEventListener(n, function (e) { e.stopPropagation(); }, { passive: true });
      });
      raiz.addEventListener("click", clic);
      refrescarUI();
      document.documentElement.appendChild(host);
    };
    if (document.documentElement) poner(); else document.addEventListener("DOMContentLoaded", poner);
  }
  function quitarUI() {
    if (host && host.parentNode) host.parentNode.removeChild(host);
    host = null; raiz = null; abierto = false;
  }

  window.__chamosFiltros = { version: 1, estado: estado, refrescar: refrescarUI, filtros: FILTROS, caras: CARAS };
})();
