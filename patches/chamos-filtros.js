/*
 * Chamos: efectos de cámara para las VIDEOLLAMADAS (caras estilo manga, caras deformadas, filtros de color,
 * memes y juegos). Solo se activa cuando la llamada pide la cámara: en una llamada de voz no aparece nada.
 *
 * Se inyecta dentro de la pantalla de llamada (Element Call, que corre en un WebView). Intercepta
 * getUserMedia: la cámara real se dibuja en un canvas (con WebGL para deformar caras) y lo que sale hacia la
 * llamada es ese canvas, así que los demás participantes también ven los efectos.
 *
 *  - Caras: usan MediaPipe Face Landmarker, que se empaqueta en /chamos/mediapipe/ al compilar.
 *  - Filtros de color y memes no necesitan nada más.
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
    var guardado = JSON.parse(localStorage.getItem("chamosFx2") || "{}");
    if (guardado.filtro) estado.filtro = guardado.filtro;
    if (guardado.cara) estado.cara = guardado.cara;
  } catch (e) { /* sin almacenamiento: no pasa nada */ }
  function guardar() {
    try { localStorage.setItem("chamosFx2", JSON.stringify({ filtro: estado.filtro, cara: estado.cara })); } catch (e) { /* nada */ }
  }

  /* ------------------------------------------------------------------ filtros de color */
  var FILTROS = [
    { id: "normal", n: "Normal", e: "🙂" },
    { id: "manga", n: "Manga B/N", e: "📖", css: "grayscale(1) contrast(1.9) brightness(1.06)", semitono: true },
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

  function medio(a, b) { return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; }
  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

  var OVAL = [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109];

  /** Convierte los puntos de MediaPipe (0..1) en medidas en píxeles. */
  function medir(pts, w, h) {
    function pt(i) { var p = pts[i] || pts[1]; return { x: p.x * w, y: p.y * h }; }
    function arr(l) { return l.map(pt); }
    function ojo(ext, int, arriba, abajo, iris) {
      var c = medio(pt(ext), pt(int));
      return { centro: c, ext: pt(ext), int: pt(int), arriba: arr(arriba), abajo: arr(abajo), iris: pts.length > 470 ? pt(iris) : c };
    }
    var oA = ojo(33, 133, [246, 161, 160, 159, 158, 157, 173], [7, 163, 144, 145, 153, 154, 155], 468);
    var oB = ojo(263, 362, [466, 388, 387, 386, 385, 384, 398], [249, 390, 373, 374, 380, 381, 382], 473);
    var o1 = oA.centro.x <= oB.centro.x ? oA : oB, o2 = oA.centro.x <= oB.centro.x ? oB : oA;
    var ceA = arr([70, 63, 105, 66, 107]), ceB = arr([300, 293, 334, 296, 336]);
    var ce1 = ceA[0].x <= ceB[0].x ? ceA : ceB, ce2 = ceA[0].x <= ceB[0].x ? ceB : ceA;
    var mA = pt(205), mB = pt(425), eA = pt(234), eB = pt(454);
    var frente = pt(10), barbilla = pt(152);
    return {
      e1: o1, e2: o2, ojos: medio(o1.centro, o2.centro), entre: dist(o1.centro, o2.centro),
      ew: (dist(o1.ext, o1.int) + dist(o2.ext, o2.int)) / 2,
      nariz: pt(1), puente: pt(6), alaA: pt(98), alaB: pt(327),
      boca: medio(pt(13), pt(14)), comA: pt(61), comB: pt(291), labio: pt(0),
      frente: frente, barbilla: barbilla, centro: medio(frente, barbilla),
      mej1: mA.x <= mB.x ? mA : mB, mej2: mA.x <= mB.x ? mB : mA,
      ore1: eA.x <= eB.x ? eA : eB, ore2: eA.x <= eB.x ? eB : eA,
      ce1: ce1, ce2: ce2, oval: arr(OVAL),
      ancho: dist(eA, eB), alto: dist(frente, barbilla),
      ang: Math.atan2(o2.centro.y - o1.centro.y, o2.centro.x - o1.centro.x)
    };
  }
  function suav(p, c, k) {
    if (typeof c === "number") return p + (c - p) * k;
    if (Array.isArray(c)) return c.map(function (v, i) { return suav(p[i], v, k); });
    if (c && typeof c.x === "number") return { x: p.x + (c.x - p.x) * k, y: p.y + (c.y - p.y) * k };
    var o = {};
    for (var key in c) o[key] = suav(p[key], c[key], k);
    return o;
  }
  function detectar(video, w, h, ahora) {
    if (lmEstado !== "listo") return [];
    if (ahora - ultima.t > 55) {
      try {
        var r = lm.detectForVideo(video, ahora);
        var lista = (r && r.faceLandmarks) || [];
        ultima = { t: ahora, caras: lista.map(function (pts, i) {
          var m = medir(pts, w, h);
          return ultima.caras[i] ? suav(ultima.caras[i], m, 0.55) : m;
        }) };
      } catch (e) { ultima = { t: ahora, caras: [] }; }
    }
    return ultima.caras;
  }

  /* ------------------------------------------------------------------ deformación (WebGL) */
  var gl = null, glc = null, glFallo = false, glUni = {}, glTex = null;
  var VS = "attribute vec2 a;varying vec2 uv;void main(){uv=a*0.5+0.5;gl_Position=vec4(a,0.0,1.0);}";
  var FS = "precision mediump float;varying vec2 uv;uniform sampler2D tex;uniform float asp;uniform int n;uniform vec4 w[12];" +
    "void main(){vec2 p=uv;for(int i=0;i<12;i++){if(i>=n)break;vec2 c=w[i].xy;float r=w[i].z;float s=w[i].w;" +
    "vec2 d=(p-c)*vec2(asp,1.0);float dd=length(d);if(dd<r){float t=dd/r;float f=1.0-s*(1.0-t)*(1.0-t);p=c+(p-c)*f;}}" +
    "gl_FragColor=texture2D(tex,p);}";
  function initGL() {
    try {
      glc = document.createElement("canvas");
      gl = glc.getContext("webgl", { alpha: false, antialias: false, premultipliedAlpha: false });
      if (!gl) throw new Error("sin webgl");
      function sh(tipo, src) { var s = gl.createShader(tipo); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
      var pr = gl.createProgram();
      gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS));
      gl.linkProgram(pr);
      if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error("enlace");
      gl.useProgram(pr);
      var buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      var loc = gl.getAttribLocation(pr, "a"); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      glTex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, glTex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      glUni = { asp: gl.getUniformLocation(pr, "asp"), n: gl.getUniformLocation(pr, "n"), w: gl.getUniformLocation(pr, "w") };
    } catch (e) {
      gl = null; glFallo = true;
      try { console.warn("Chamos filtros: sin deformación de caras", e && e.message); } catch (x) { /* nada */ }
    }
  }
  /** Devuelve un canvas con la imagen deformada, o null si el teléfono no puede. */
  function deformar(fuente, w, h, ws) {
    if (!gl && !glFallo) initGL();
    if (!gl) return null;
    if (glc.width !== w || glc.height !== h) { glc.width = w; glc.height = h; }
    gl.viewport(0, 0, w, h);
    gl.bindTexture(gl.TEXTURE_2D, glTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, fuente);
    var arr = new Float32Array(48), n = Math.min(12, ws.length);
    for (var i = 0; i < n; i++) { arr[i * 4] = ws[i].x / w; arr[i * 4 + 1] = 1 - ws[i].y / h; arr[i * 4 + 2] = ws[i].r / h; arr[i * 4 + 3] = ws[i].s; }
    gl.uniform1f(glUni.asp, w / h); gl.uniform1i(glUni.n, n); gl.uniform4fv(glUni.w, arr);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    return glc;
  }
  /** Dónde queda un punto de la cámara después de las deformaciones de la cara. */
  function M(c, p) {
    var ws = c._w || [];
    var q = { x: p.x, y: p.y };
    for (var i = 0; i < ws.length; i++) {
      var k = ws[i], R = k.r, t = { x: q.x, y: q.y };
      for (var it = 0; it < 5; it++) {
        var d = Math.hypot(t.x - k.x, t.y - k.y), f = d < R ? 1 - k.s * Math.pow(1 - d / R, 2) : 1;
        t = { x: k.x + (q.x - k.x) / f, y: k.y + (q.y - k.y) / f };
      }
      q = t;
    }
    return q;
  }
  function W(p, r, s) { return { x: p.x, y: p.y, r: r, s: s }; }

  /* ------------------------------------------------------------------ dibujo de efectos */
  function rnd(i) { var x = Math.sin(i * 12.9898) * 43758.5453; return x - Math.floor(x); }
  function sobre(ctx, cx, cy, ang, fn) { ctx.save(); ctx.translate(cx, cy); ctx.rotate(ang); fn(); ctx.restore(); }
  function arriba(c) { return { x: Math.sin(c.ang), y: -Math.cos(c.ang) }; }
  function destello(ctx, x, y, r, rot) {
    ctx.beginPath();
    for (var i = 0; i < 8; i++) { var a = rot + i * Math.PI / 4, rr = i % 2 ? r * 0.2 : r; ctx.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr); }
    ctx.closePath(); ctx.fill();
  }
  function trazoOval(ctx, pts) {
    ctx.beginPath();
    for (var i = 0; i < pts.length; i++) { if (i) ctx.lineTo(pts[i].x, pts[i].y); else ctx.moveTo(pts[i].x, pts[i].y); }
    ctx.closePath();
  }
  function ovalM(c) { return c.oval.map(function (p) { return M(c, p); }); }
  /** Pinta toda la piel de la cara (para tintes, brillo, etc.). */
  function piel(ctx, c, color, modo, alfa, desenfoque) {
    ctx.save();
    ctx.globalCompositeOperation = modo; ctx.globalAlpha = alfa; ctx.filter = desenfoque ? "blur(" + desenfoque + "px)" : "none";
    ctx.fillStyle = color; trazoOval(ctx, ovalM(c)); ctx.fill();
    ctx.restore();
  }

  /** Ojos de manga: brillos, iris con color, pestañas gruesas y rubor. */
  function ojosManga(ctx, c, t, fuerza) {
    var u = arriba(c);
    [c.e1, c.e2].forEach(function (o, idx) {
      var ct = M(c, o.centro), ir = M(c, o.iris), ex = M(c, o.ext), inn = M(c, o.int);
      var ew = dist(ex, inn);
      var rI = Math.max(ew * 0.16, dist(ir, M(c, { x: o.iris.x + c.ew * 0.2, y: o.iris.y })));
      // color del iris
      ctx.save();
      ctx.globalCompositeOperation = "overlay"; ctx.globalAlpha = 0.75;
      var gi = ctx.createRadialGradient(ir.x, ir.y + rI * 0.3, rI * 0.1, ir.x, ir.y, rI);
      gi.addColorStop(0, "rgba(120,220,255,.9)"); gi.addColorStop(0.6, "rgba(70,110,255,.85)"); gi.addColorStop(1, "rgba(30,20,120,.9)");
      ctx.fillStyle = gi; ctx.beginPath(); ctx.arc(ir.x, ir.y, rI, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
      ctx.strokeStyle = "rgba(25,10,50,.6)"; ctx.lineWidth = rI * 0.2; ctx.beginPath(); ctx.arc(ir.x, ir.y, rI * 0.92, 0, Math.PI * 2); ctx.stroke();
      // brillos
      ctx.fillStyle = "rgba(255,255,255,.95)";
      ctx.beginPath(); ctx.arc(ir.x - rI * 0.38, ir.y - rI * 0.42, rI * 0.34, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(ir.x + rI * 0.36, ir.y + rI * 0.4, rI * 0.16, 0, Math.PI * 2); ctx.fill();
      // pestañas: línea gruesa sobre el párpado de arriba, con alita hacia afuera
      var up = o.arriba.map(function (p) { return M(c, p); });
      ctx.strokeStyle = "#140e1c"; ctx.lineCap = "round"; ctx.lineJoin = "round";
      ctx.lineWidth = Math.max(2, ew * 0.11);
      ctx.beginPath();
      for (var i = 0; i < up.length; i++) { if (i) ctx.lineTo(up[i].x, up[i].y); else ctx.moveTo(up[i].x, up[i].y); }
      ctx.stroke();
      var d = { x: ex.x - ct.x, y: ex.y - ct.y }, dl = Math.hypot(d.x, d.y) || 1;
      d = { x: d.x / dl, y: d.y / dl };
      ctx.lineWidth = Math.max(2, ew * 0.08);
      ctx.beginPath(); ctx.moveTo(ex.x, ex.y); ctx.lineTo(ex.x + d.x * ew * 0.3 + u.x * ew * 0.16, ex.y + d.y * ew * 0.3 + u.y * ew * 0.16); ctx.stroke();
      // pestañitas sueltas
      ctx.lineWidth = Math.max(1.5, ew * 0.035);
      for (var k = 1; k < 4; k++) {
        var p = up[k + 1];
        ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x + u.x * ew * 0.16 + d.x * ew * 0.07 * (k - 1), p.y + u.y * ew * 0.16 + d.y * ew * 0.07 * (k - 1)); ctx.stroke();
      }
    });
  }
  function rubor(ctx, c, fuerza) {
    [c.mej1, c.mej2].forEach(function (m) {
      var p = M(c, m), r = c.ancho * 0.17;
      ctx.save();
      ctx.translate(p.x, p.y + c.alto * 0.02); ctx.rotate(c.ang); ctx.scale(1, 0.62);
      var g = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
      g.addColorStop(0, "rgba(255,95,135," + (0.55 * fuerza) + ")"); g.addColorStop(1, "rgba(255,95,135,0)");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, r, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
      ctx.strokeStyle = "rgba(255,60,100,.7)"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.008); ctx.lineCap = "round";
      for (var i = -1; i <= 1; i++) {
        var x = p.x + i * c.ancho * 0.035, y = p.y + c.alto * 0.02;
        ctx.beginPath(); ctx.moveTo(x - c.ancho * 0.012, y + c.ancho * 0.022); ctx.lineTo(x + c.ancho * 0.012, y - c.ancho * 0.022); ctx.stroke();
      }
    });
  }
  function destellos(ctx, c, t, n, color) {
    ctx.fillStyle = color || "rgba(255,255,255,.95)";
    for (var i = 0; i < n; i++) {
      var a = rnd(i * 3.1) * Math.PI * 2, rr = c.ancho * (0.62 + rnd(i * 7.7) * 0.45);
      var x = c.centro.x + Math.cos(a) * rr, y = c.centro.y + Math.sin(a) * rr * 0.9;
      var tw = 0.5 + 0.5 * Math.sin(t / 220 + i * 1.7);
      destello(ctx, x, y, c.ancho * (0.035 + rnd(i * 9.3) * 0.045) * (0.4 + tw), t / 900 + i);
    }
  }

  /** Líneas de enfoque de manga (rayitas que apuntan al centro). */
  function lineasVelocidad(ctx, w, h, cx, cy, t, r0, color, n) {
    var q = Math.floor(t / 80), diag = Math.hypot(w, h);
    ctx.fillStyle = color;
    for (var i = 0; i < n; i++) {
      var a = (i / n) * Math.PI * 2 + (rnd(i + q * 7) - 0.5) * 0.06;
      var ab = 0.01 + rnd(i * 3 + q) * 0.028;
      var inner = r0 * (1 + rnd(i * 5 + q) * 0.7);
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(a - ab) * diag, cy + Math.sin(a - ab) * diag);
      ctx.lineTo(cx + Math.cos(a + ab) * diag, cy + Math.sin(a + ab) * diag);
      ctx.lineTo(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner);
      ctx.closePath(); ctx.fill();
    }
  }
  var trama = null;
  function semitono(ctx, w, h) {
    if (!trama) {
      var p = document.createElement("canvas"); p.width = 6; p.height = 6;
      var x = p.getContext("2d"); x.fillStyle = "#fff"; x.fillRect(0, 0, 6, 6); x.fillStyle = "#000"; x.beginPath(); x.arc(3, 3, 1.5, 0, Math.PI * 2); x.fill();
      trama = ctx.createPattern(p, "repeat");
    }
    ctx.save(); ctx.globalCompositeOperation = "multiply"; ctx.globalAlpha = 0.42; ctx.fillStyle = trama; ctx.fillRect(0, 0, w, h); ctx.restore();
  }
  function textoManga(ctx, texto, x, y, tam, rot) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot);
    ctx.font = "900 " + tam + "px 'Noto Sans CJK JP','Noto Sans JP','Hiragino Sans',sans-serif";
    ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.lineJoin = "round";
    ctx.lineWidth = tam * 0.2; ctx.strokeStyle = "rgba(255,255,255,.9)"; ctx.fillStyle = "rgba(20,20,30,.85)";
    ctx.strokeText(texto, 0, 0); ctx.fillText(texto, 0, 0);
    ctx.restore();
  }
  var petalos = [];
  function lluviaPetalos(ctx, w, h, t) {
    while (petalos.length < 22) petalos.push({ x: Math.random() * w, y: -Math.random() * h, v: h * (0.12 + Math.random() * 0.14), s: h * (0.014 + Math.random() * 0.016), f: Math.random() * 6 });
    petalos.forEach(function (p) {
      p.y += p.v / FPS; p.x += Math.sin(t / 700 + p.f) * 0.7;
      if (p.y > h + 20) { p.y = -20; p.x = Math.random() * w; }
      ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(t / 500 + p.f);
      var g = ctx.createRadialGradient(0, 0, 0, 0, 0, p.s * 2);
      g.addColorStop(0, "#ffe3ee"); g.addColorStop(1, "#ff9cc0");
      ctx.fillStyle = g; ctx.beginPath(); ctx.ellipse(0, 0, p.s * 1.7, p.s, 0, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    });
  }
  function vineta(ctx, w, h, rgba) {
    var g = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.35, w / 2, h / 2, Math.hypot(w, h) * 0.6);
    g.addColorStop(0, "rgba(255,255,255,0)"); g.addColorStop(1, rgba);
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  }

  /* orejas de ogro */
  function orejaOgro(ctx, c, p, lado, largo, grosor) {
    sobre(ctx, p.x, p.y, c.ang, function () {
      ctx.scale(lado, 1);
      var g = ctx.createLinearGradient(0, 0, largo, -largo * 0.5);
      g.addColorStop(0, "#7fb83c"); g.addColorStop(1, "#5f9a26");
      ctx.fillStyle = g; ctx.strokeStyle = "#4a7a1c"; ctx.lineWidth = Math.max(1.5, grosor * 0.08);
      ctx.beginPath();
      ctx.moveTo(-grosor * 0.15, grosor * 0.55);
      ctx.quadraticCurveTo(largo * 0.4, grosor * 0.7, largo, -largo * 0.52);
      ctx.quadraticCurveTo(largo * 0.55, -grosor * 0.55, -grosor * 0.15, -grosor * 0.65);
      ctx.closePath(); ctx.fill(); ctx.stroke();
      var gi = ctx.createLinearGradient(0, 0, largo * 0.8, -largo * 0.4);
      gi.addColorStop(0, "rgba(214,120,110,.85)"); gi.addColorStop(1, "rgba(214,120,110,.25)");
      ctx.fillStyle = gi;
      ctx.beginPath();
      ctx.moveTo(grosor * 0.05, grosor * 0.2);
      ctx.quadraticCurveTo(largo * 0.4, grosor * 0.25, largo * 0.82, -largo * 0.42);
      ctx.quadraticCurveTo(largo * 0.5, -grosor * 0.2, grosor * 0.05, -grosor * 0.3);
      ctx.closePath(); ctx.fill();
    });
  }
  function cabelloFiona(ctx, c) {
    var o = ovalM(c);
    var idx = [28, 29, 30, 31, 32, 33, 34, 35, 0, 1, 2, 3, 4, 5, 6, 7, 8];
    var ext = [], inte = [];
    idx.forEach(function (k, i) {
      var p = o[k], t = i / (idx.length - 1);
      var dx = p.x - c.centro.x, dy = p.y - c.centro.y;
      var fuera = 0.3 + 0.12 * Math.sin(Math.PI * t), dentro = 0.2 * Math.sin(Math.PI * t) * Math.sin(Math.PI * t);
      ext.push({ x: p.x + dx * fuera, y: p.y + dy * fuera - c.alto * 0.03 * Math.sin(Math.PI * t) });
      inte.push({ x: p.x - dx * dentro, y: p.y - dy * dentro });
    });
    ctx.save();
    ctx.filter = "blur(1px)";
    var g = ctx.createLinearGradient(c.frente.x, c.frente.y - c.alto * 0.4, c.frente.x, c.frente.y + c.alto * 0.2);
    g.addColorStop(0, "#6b2a0c"); g.addColorStop(0.5, "#c4601f"); g.addColorStop(1, "#8a3a12");
    ctx.fillStyle = g;
    ctx.beginPath();
    ext.forEach(function (p, i) { if (i) ctx.lineTo(p.x, p.y); else ctx.moveTo(p.x, p.y); });
    for (var i = inte.length - 1; i >= 0; i--) ctx.lineTo(inte[i].x, inte[i].y);
    ctx.closePath(); ctx.fill();
    ctx.restore();
    // mechones
    ctx.strokeStyle = "rgba(255,170,90,.45)"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.008); ctx.lineCap = "round";
    for (var j = 1; j < ext.length - 1; j += 2) {
      ctx.beginPath(); ctx.moveTo(ext[j].x, ext[j].y); ctx.lineTo((ext[j].x + inte[j].x) / 2, (ext[j].y + inte[j].y) / 2); ctx.stroke();
    }
    // trenzas
    [o[28], o[8]].forEach(function (p, lado) {
      var s = lado ? 1 : -1;
      for (var k = 0; k < 8; k++) {
        var x = p.x + s * c.ancho * 0.05 + (k % 2 ? 1 : -1) * c.ancho * 0.012, y = p.y + c.alto * (0.06 + k * 0.115);
        var gr = ctx.createRadialGradient(x - c.ancho * 0.01, y - c.ancho * 0.01, 0, x, y, c.ancho * 0.07);
        gr.addColorStop(0, "#e07a35"); gr.addColorStop(1, "#8a3a12");
        ctx.fillStyle = gr; ctx.beginPath(); ctx.ellipse(x, y, c.ancho * 0.055, c.alto * 0.07, (k % 2 ? 0.5 : -0.5) * 0.6, 0, Math.PI * 2); ctx.fill();
      }
      ctx.fillStyle = "#ffcf40"; ctx.beginPath(); ctx.arc(p.x + s * c.ancho * 0.05, p.y + c.alto * 0.96, c.ancho * 0.035, 0, Math.PI * 2); ctx.fill();
    });
  }
  function orejasGato(ctx, c) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.scale(s, 1);
        ctx.shadowColor = "rgba(0,0,0,.35)"; ctx.shadowBlur = c.ancho * 0.03;
        var g = ctx.createLinearGradient(0, 0, c.ancho * 0.3, -c.alto * 0.5);
        g.addColorStop(0, "#2a2733"); g.addColorStop(1, "#4a4558");
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.moveTo(c.ancho * 0.06, c.alto * 0.04);
        ctx.quadraticCurveTo(c.ancho * 0.1, -c.alto * 0.3, c.ancho * 0.3, -c.alto * 0.5);
        ctx.quadraticCurveTo(c.ancho * 0.44, -c.alto * 0.22, c.ancho * 0.45, c.alto * 0.07);
        ctx.closePath(); ctx.fill();
        ctx.shadowBlur = 0;
        var gi = ctx.createLinearGradient(0, 0, c.ancho * 0.3, -c.alto * 0.4);
        gi.addColorStop(0, "#ffb3cf"); gi.addColorStop(1, "#ff7fae");
        ctx.fillStyle = gi;
        ctx.beginPath();
        ctx.moveTo(c.ancho * 0.12, c.alto * 0.02);
        ctx.quadraticCurveTo(c.ancho * 0.15, -c.alto * 0.22, c.ancho * 0.3, -c.alto * 0.4);
        ctx.quadraticCurveTo(c.ancho * 0.38, -c.alto * 0.2, c.ancho * 0.39, c.alto * 0.03);
        ctx.closePath(); ctx.fill();
        ctx.restore();
      });
    });
  }

  /* ------------------------------------------------------------------ lentes (efectos de pantalla completa, estilo Snapchat) */
  var TAU = Math.PI * 2;
  function der(c) { return { x: Math.cos(c.ang), y: Math.sin(c.ang) }; }
  function brillo(ctx, x, y, r, color, alfa) {
    var g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, color); g.addColorStop(1, "rgba(0,0,0,0)");
    ctx.save(); ctx.globalCompositeOperation = "lighter"; ctx.globalAlpha = alfa == null ? 1 : alfa;
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill(); ctx.restore();
  }
  function estrella5(ctx, x, y, r, rot, relleno, glow) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot);
    ctx.shadowColor = glow || relleno; ctx.shadowBlur = r * 1.2; ctx.fillStyle = relleno;
    ctx.beginPath();
    for (var i = 0; i < 10; i++) { var rr = i % 2 ? r * 0.42 : r, a = -Math.PI / 2 + i * Math.PI / 5; ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr); }
    ctx.closePath(); ctx.fill(); ctx.restore();
  }
  function corazon(ctx, x, y, r, relleno, rot) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot || 0);
    ctx.fillStyle = relleno; ctx.beginPath();
    ctx.moveTo(0, r * 0.9);
    ctx.bezierCurveTo(-r * 1.35, r * 0.1, -r * 0.9, -r * 1.0, 0, -r * 0.35);
    ctx.bezierCurveTo(r * 0.9, -r * 1.0, r * 1.35, r * 0.1, 0, r * 0.9);
    ctx.fill(); ctx.restore();
  }
  function burbuja(ctx, x, y, r) {
    ctx.save(); ctx.beginPath(); ctx.arc(x, y, r, 0, TAU);
    var g = ctx.createRadialGradient(x - r * 0.35, y - r * 0.35, r * 0.1, x, y, r);
    g.addColorStop(0, "rgba(255,255,255,.6)"); g.addColorStop(0.7, "rgba(190,240,255,.10)"); g.addColorStop(1, "rgba(255,255,255,.42)");
    ctx.fillStyle = g; ctx.fill(); ctx.lineWidth = Math.max(1, r * 0.08); ctx.strokeStyle = "rgba(255,255,255,.65)"; ctx.stroke(); ctx.restore();
  }
  var burbujas = [], copos = [], chispas = [], corazones = [];
  function particulas(lista, n, w, h, nuevo, mover, pintar) {
    while (lista.length < n) lista.push(nuevo(w, h));
    for (var i = 0; i < lista.length; i++) { if (mover(lista[i], w, h)) lista[i] = nuevo(w, h); pintar(lista[i]); }
  }
  function velo(ctx, w, h, c0, c1, modo, alfa) {
    var g = ctx.createLinearGradient(0, 0, 0, h); g.addColorStop(0, c0); g.addColorStop(1, c1);
    ctx.save(); ctx.globalCompositeOperation = modo; ctx.globalAlpha = alfa; ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); ctx.restore();
  }
  function rayosLuz(ctx, w, h, t, color) {
    ctx.save(); ctx.globalCompositeOperation = "screen";
    for (var i = 0; i < 6; i++) {
      var x0 = w * (i / 5.2) + Math.sin(t / 2300 + i * 1.7) * w * 0.05;
      var g = ctx.createLinearGradient(x0, 0, x0 + w * 0.2, h * 0.85);
      g.addColorStop(0, color); g.addColorStop(1, "rgba(255,255,255,0)");
      ctx.globalAlpha = 0.22 + 0.1 * Math.sin(t / 900 + i);
      ctx.fillStyle = g; ctx.beginPath();
      ctx.moveTo(x0 - w * 0.03, 0); ctx.lineTo(x0 + w * 0.06, 0); ctx.lineTo(x0 + w * 0.25, h * 0.85); ctx.lineTo(x0 + w * 0.04, h * 0.85); ctx.fill();
    }
    ctx.restore();
  }
  function lentesAgua(ctx, w, h, t) {
    velo(ctx, w, h, "#a8f0f2", "#0f5f7c", "multiply", 0.78);
    velo(ctx, w, h, "rgba(120,230,240,.25)", "rgba(0,40,70,.35)", "source-over", 1);
    rayosLuz(ctx, w, h, t, "rgba(210,255,255,.9)");
    particulas(burbujas, 26, w, h,
      function (W_, H_) { return { x: Math.random() * W_, y: H_ + Math.random() * H_ * 0.5, r: H_ * (0.006 + Math.random() * 0.02), v: H_ * (0.07 + Math.random() * 0.15), f: Math.random() * 6 }; },
      function (b, W_, H_) { b.y -= b.v / FPS; b.x += Math.sin(t / 650 + b.f) * 0.7; return b.y < -b.r * 2; },
      function (b) { burbuja(ctx, b.x, b.y, b.r); });
    vineta(ctx, w, h, "rgba(0,40,70,.55)");
  }
  function lentesAguaCara(ctx, c, t) {
    // burbujas pegadas a la cara que suben despacio
    for (var i = 0; i < 9; i++) {
      var p = c.oval[(i * 4 + 3) % c.oval.length], q = M(c, p);
      var sube = ((t / 2600 + i * 0.37) % 1);
      burbuja(ctx, q.x + Math.sin(t / 500 + i) * c.ancho * 0.02, q.y - sube * c.alto * 0.45, c.ancho * (0.012 + 0.014 * rnd(i + 3)));
    }
    piel(ctx, c, "#7fe0ea", "soft-light", 0.35, 6);
  }
  function lentesGalaxia(ctx, w, h, t) {
    ctx.save(); ctx.globalCompositeOperation = "screen";
    [[0.2, 0.25, "rgba(150,60,255,.55)", 0.6], [0.85, 0.7, "rgba(40,120,255,.5)", 0.65], [0.5, 0.95, "rgba(255,70,170,.35)", 0.5]].forEach(function (n, i) {
      var g = ctx.createRadialGradient(w * n[0], h * n[1], 0, w * n[0], h * n[1], Math.max(w, h) * n[3]);
      g.addColorStop(0, n[2]); g.addColorStop(1, "rgba(0,0,0,0)"); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
    });
    ctx.restore();
    for (var i = 0; i < 70; i++) {
      var x = rnd(i * 3 + 1) * w, y = rnd(i * 3 + 2) * h, r = (0.6 + rnd(i * 3 + 3) * 1.8) * (h / 700);
      var tw = 0.45 + 0.55 * Math.abs(Math.sin(t / (500 + rnd(i) * 900) + i));
      ctx.fillStyle = "rgba(255,255,255," + tw.toFixed(2) + ")"; ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
    }
    var f = (t / 3200) % 1.6;
    if (f < 1) {
      var sx = w * (1.05 - f * 1.2), sy = h * (0.05 + f * 0.35);
      var g2 = ctx.createLinearGradient(sx, sy, sx + w * 0.18, sy - h * 0.06);
      g2.addColorStop(0, "rgba(255,255,255,.95)"); g2.addColorStop(1, "rgba(255,255,255,0)");
      ctx.strokeStyle = g2; ctx.lineWidth = Math.max(2, h * 0.004); ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(sx + w * 0.18, sy - h * 0.06); ctx.stroke();
    }
    vineta(ctx, w, h, "rgba(10,0,40,.6)");
  }
  function lentesGalaxiaCara(ctx, c, t) {
    var u = arriba(c), r = der(c), cx = c.centro.x, cy = c.centro.y, R = c.ancho * 0.95;
    brillo(ctx, c.frente.x + u.x * c.alto * 0.15, c.frente.y + u.y * c.alto * 0.15, c.ancho * 0.9, "rgba(170,120,255,.8)", 0.55);
    var planetas = [["#ffcf6b", 0.11, 0], ["#6bd3ff", 0.08, 2.1], ["#ff7ab8", 0.065, 4.2]];
    var detras = [], delante = [];
    planetas.forEach(function (p, i) {
      var a = t / (1700 + i * 500) + p[2], sx = Math.cos(a), sy = Math.sin(a);
      var item = { x: cx + (r.x * sx * R) + u.x * (-sy * R * 0.28) + u.x * c.alto * 0.12, y: cy + (r.y * sx * R) + u.y * (-sy * R * 0.28) + u.y * c.alto * 0.12, col: p[0], rad: c.ancho * p[1] * (1 + sy * 0.25) };
      (sy < 0 ? detras : delante).push(item);
    });
    function pintar(it) {
      var g = ctx.createRadialGradient(it.x - it.rad * 0.3, it.y - it.rad * 0.3, it.rad * 0.1, it.x, it.y, it.rad);
      g.addColorStop(0, "#fff"); g.addColorStop(0.35, it.col); g.addColorStop(1, "rgba(20,10,60,.9)");
      ctx.save(); ctx.shadowColor = it.col; ctx.shadowBlur = it.rad; ctx.fillStyle = g; ctx.beginPath(); ctx.arc(it.x, it.y, it.rad, 0, TAU); ctx.fill(); ctx.restore();
    }
    detras.forEach(pintar);
    ctx.save(); ctx.translate(cx + u.x * c.alto * 0.12, cy + u.y * c.alto * 0.12); ctx.rotate(c.ang);
    ctx.strokeStyle = "rgba(200,170,255,.55)"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.012);
    ctx.shadowColor = "#b48cff"; ctx.shadowBlur = c.ancho * 0.05;
    ctx.beginPath(); ctx.ellipse(0, 0, R, R * 0.28, 0, 0, TAU); ctx.stroke(); ctx.restore();
    delante.forEach(pintar);
  }
  function lentesFuego(ctx, w, h, t) {
    var g = ctx.createLinearGradient(0, h, 0, h * 0.45);
    g.addColorStop(0, "rgba(255,90,0,.5)"); g.addColorStop(1, "rgba(255,90,0,0)");
    ctx.save(); ctx.globalCompositeOperation = "screen"; ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); ctx.restore();
    velo(ctx, w, h, "rgba(60,0,0,.0)", "rgba(120,20,0,.35)", "source-over", 1);
    particulas(chispas, 34, w, h,
      function (W_, H_) { return { x: Math.random() * W_, y: H_ * (0.7 + Math.random() * 0.4), v: H_ * (0.15 + Math.random() * 0.3), r: H_ * (0.002 + Math.random() * 0.004), f: Math.random() * 6 }; },
      function (p, W_, H_) { p.y -= p.v / FPS; p.x += Math.sin(t / 300 + p.f) * 1.1; return p.y < -10; },
      function (p) { brillo(ctx, p.x, p.y, p.r * 5, "rgba(255,190,80,1)", 0.9); });
    vineta(ctx, w, h, "rgba(90,10,0,.5)");
  }
  function lentesFuegoCara(ctx, c, t) {
    var u = arriba(c), r = der(c), n = 11;
    ctx.save(); ctx.globalCompositeOperation = "lighter";
    for (var i = 0; i < n; i++) {
      var k = (i / (n - 1)) * 2 - 1, curva = 1 - k * k;
      var bx = c.frente.x + r.x * k * c.ancho * 0.46 - u.x * c.alto * 0.02 + u.x * c.alto * 0.04 * curva;
      var by = c.frente.y + r.y * k * c.ancho * 0.46 - u.y * c.alto * 0.02 + u.y * c.alto * 0.04 * curva;
      var alto = c.alto * (0.22 + 0.3 * curva) * (0.75 + 0.25 * Math.sin(t / 110 + i * 1.9) + 0.12 * Math.sin(t / 47 + i * 3.1));
      var ancho = c.ancho * 0.105;
      var sway = Math.sin(t / 230 + i * 2.3) * ancho * 0.7;
      var tx = bx + u.x * alto + r.x * sway, ty = by + u.y * alto + r.y * sway;
      var g = ctx.createLinearGradient(bx, by, tx, ty);
      g.addColorStop(0, "rgba(255,240,150,.95)"); g.addColorStop(0.35, "rgba(255,150,30,.85)"); g.addColorStop(1, "rgba(220,30,0,0)");
      ctx.fillStyle = g; ctx.beginPath();
      ctx.moveTo(bx - r.x * ancho, by - r.y * ancho);
      ctx.quadraticCurveTo(bx - r.x * ancho * 0.9 + u.x * alto * 0.5, by - r.y * ancho * 0.9 + u.y * alto * 0.5, tx, ty);
      ctx.quadraticCurveTo(bx + r.x * ancho * 0.9 + u.x * alto * 0.5, by + r.y * ancho * 0.9 + u.y * alto * 0.5, bx + r.x * ancho, by + r.y * ancho);
      ctx.closePath(); ctx.fill();
    }
    ctx.restore();
    brillo(ctx, c.frente.x + u.x * c.alto * 0.2, c.frente.y + u.y * c.alto * 0.2, c.ancho * 0.8, "rgba(255,120,20,.7)", 0.5);
    piel(ctx, c, "#ff8a3d", "soft-light", 0.3, 6);
  }
  function flor(ctx, x, y, r, col, rot) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot);
    ctx.shadowColor = "rgba(0,0,0,.3)"; ctx.shadowBlur = r * 0.3;
    ctx.fillStyle = col;
    for (var i = 0; i < 6; i++) { ctx.rotate(TAU / 6); ctx.beginPath(); ctx.ellipse(r * 0.55, 0, r * 0.55, r * 0.33, 0, 0, TAU); ctx.fill(); }
    ctx.shadowBlur = 0; ctx.fillStyle = "#ffd24a"; ctx.beginPath(); ctx.arc(0, 0, r * 0.27, 0, TAU); ctx.fill();
    ctx.fillStyle = "rgba(255,255,255,.4)"; ctx.beginPath(); ctx.arc(-r * 0.08, -r * 0.08, r * 0.1, 0, TAU); ctx.fill();
    ctx.restore();
  }
  function lentesFloresCara(ctx, c, t) {
    var u = arriba(c), r = der(c), cols = ["#ff5d8f", "#ffffff", "#b184ff", "#ffb347", "#ff7ab8"];
    var n = 9;
    for (var pasada = 0; pasada < 2; pasada++) {
      for (var i = 0; i < n; i++) {
        var k = (i / (n - 1)) * 2 - 1, curva = 1 - k * k * 0.9;
        var x = c.frente.x + r.x * k * c.ancho * 0.54 + u.x * c.alto * (0.0 + 0.05 * curva) + r.x * 0;
        var y = c.frente.y + r.y * k * c.ancho * 0.54 + u.y * c.alto * (0.0 + 0.05 * curva);
        var rad = c.ancho * (0.075 + 0.03 * rnd(i + 11));
        if (pasada === 0) {
          ctx.save(); ctx.fillStyle = "#3cae5a"; ctx.translate(x, y); ctx.rotate(c.ang + k * 0.9);
          ctx.beginPath(); ctx.ellipse(rad * 0.7, rad * 0.9, rad * 0.35, rad * 0.9, 0.7, 0, TAU); ctx.fill();
          ctx.beginPath(); ctx.ellipse(-rad * 0.7, rad * 0.9, rad * 0.35, rad * 0.9, -0.7, 0, TAU); ctx.fill(); ctx.restore();
        } else {
          flor(ctx, x, y - u.y * 0, rad, cols[i % cols.length], c.ang + t / 4000 * (i % 2 ? 1 : -1));
        }
      }
    }
    for (var j = 0; j < 4; j++) {
      var a = (t / 1400 + j * 1.57), pp = { x: c.frente.x + r.x * Math.cos(a) * c.ancho * 0.7, y: c.frente.y - u.y * 0 + u.y * c.alto * (0.12 + 0.2 * ((a / 6) % 1)) };
      ctx.save(); ctx.fillStyle = "#fff"; ctx.shadowColor = "#ffd6ec"; ctx.shadowBlur = c.ancho * 0.04; destello(ctx, pp.x, pp.y, c.ancho * 0.05, t / 400 + j); ctx.restore();
    }
    rubor(ctx, c, 0.8);
  }
  function lentesFlores(ctx, w, h, t) {
    velo(ctx, w, h, "rgba(255,200,230,.35)", "rgba(255,170,200,0)", "screen", 0.8);
    lluviaPetalos(ctx, w, h, t);
  }
  function lentesCyber(ctx, w, h, t) {
    var g = ctx.createLinearGradient(0, 0, w, 0);
    g.addColorStop(0, "rgba(255,0,200,.30)"); g.addColorStop(0.5, "rgba(0,0,0,0)"); g.addColorStop(1, "rgba(0,220,255,.30)");
    ctx.save(); ctx.globalCompositeOperation = "screen"; ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); ctx.restore();
    ctx.save(); ctx.fillStyle = "rgba(0,0,0,.11)";
    var paso = Math.max(3, Math.round(h / 180));
    for (var y = 0; y < h; y += paso * 2) ctx.fillRect(0, y, w, paso);
    ctx.restore();
    var by = ((t / 2400) % 1.3 - 0.15) * h, gb = ctx.createLinearGradient(0, by - h * 0.08, 0, by + h * 0.08);
    gb.addColorStop(0, "rgba(0,255,255,0)"); gb.addColorStop(0.5, "rgba(0,255,255,.2)"); gb.addColorStop(1, "rgba(0,255,255,0)");
    ctx.fillStyle = gb; ctx.fillRect(0, by - h * 0.08, w, h * 0.16);
    var ciclo = t % 2600;
    if (ciclo < 160) {
      for (var i = 0; i < 4; i++) {
        var sy = Math.floor(rnd(Math.floor(t / 160) * 7 + i) * h * 0.9), sh = Math.max(4, h * 0.03 * (1 + rnd(i + 5))), dx = (rnd(i + Math.floor(t / 80)) - 0.5) * w * 0.08;
        ctx.drawImage(ctx.canvas, 0, sy, w, sh, dx, sy, w, sh);
      }
    }
    vineta(ctx, w, h, "rgba(20,0,60,.45)");
  }
  function lentesCyberCara(ctx, c, t) {
    var o = ovalM(c), r = der(c), u = arriba(c);
    [["#ff2bd6", 1.8], ["#27f1ff", 0]].forEach(function (cc, i) {
      ctx.save(); ctx.lineJoin = "round"; ctx.shadowColor = cc[0]; ctx.shadowBlur = c.ancho * 0.06;
      ctx.strokeStyle = cc[0]; ctx.lineWidth = Math.max(2, c.ancho * (i ? 0.012 : 0.02)); ctx.globalAlpha = i ? 1 : 0.6;
      ctx.beginPath(); o.forEach(function (p, k) { if (k) ctx.lineTo(p.x, p.y); else ctx.moveTo(p.x, p.y); }); ctx.closePath(); ctx.stroke(); ctx.restore();
    });
    var a = M(c, c.e1.ext), b = M(c, c.e2.ext), alt = c.ew * 0.75;
    ctx.save(); ctx.shadowColor = "#27f1ff"; ctx.shadowBlur = c.ancho * 0.06;
    var gl = ctx.createLinearGradient(a.x, a.y, b.x, b.y); gl.addColorStop(0, "rgba(255,43,214,.55)"); gl.addColorStop(1, "rgba(39,241,255,.55)");
    ctx.fillStyle = gl; ctx.strokeStyle = "#e9ffff"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.01);
    ctx.beginPath();
    ctx.moveTo(a.x - r.x * c.ew * 0.5 - u.x * alt * 0.5, a.y - r.y * c.ew * 0.5 - u.y * alt * 0.5);
    ctx.lineTo(b.x + r.x * c.ew * 0.5 - u.x * alt * 0.5, b.y + r.y * c.ew * 0.5 - u.y * alt * 0.5);
    ctx.lineTo(b.x + r.x * c.ew * 0.5 + u.x * alt * 0.5, b.y + r.y * c.ew * 0.5 + u.y * alt * 0.5);
    ctx.lineTo(a.x - r.x * c.ew * 0.5 + u.x * alt * 0.5, a.y - r.y * c.ew * 0.5 + u.y * alt * 0.5);
    ctx.closePath(); ctx.fill(); ctx.stroke(); ctx.restore();
    for (var i = 0; i < 3; i++) {
      var q = M(c, { x: c.mej2.x + r.x * c.ancho * 0.16, y: c.mej2.y - u.y * 0 + c.alto * 0.04 * i - c.alto * 0.12 });
      ctx.fillStyle = i % 2 ? "#27f1ff" : "#ff2bd6"; ctx.fillRect(q.x, q.y, c.ancho * (0.12 - i * 0.025), Math.max(2, c.ancho * 0.012));
    }
  }
  function lentesAmor(ctx, w, h, t) {
    velo(ctx, w, h, "rgba(255,120,170,.35)", "rgba(255,60,120,.25)", "screen", 0.9);
    vineta(ctx, w, h, "rgba(255,40,110,.5)");
    particulas(corazones, 16, w, h,
      function (W_, H_) { return { x: Math.random() * W_, y: H_ + Math.random() * H_ * 0.3, v: H_ * (0.08 + Math.random() * 0.15), r: H_ * (0.014 + Math.random() * 0.025), f: Math.random() * 6 }; },
      function (p, W_, H_) { p.y -= p.v / FPS; p.x += Math.sin(t / 700 + p.f) * 0.9; return p.y < -p.r * 2; },
      function (p) { ctx.save(); ctx.globalAlpha = 0.85; ctx.shadowColor = "#ff2d6f"; ctx.shadowBlur = p.r; corazon(ctx, p.x, p.y, p.r, "#ff4d88", Math.sin(t / 500 + p.f) * 0.3); ctx.restore(); });
  }
  function lentesAmorCara(ctx, c, t) {
    var latido = 1 + 0.14 * Math.sin(t / 170);
    [c.e1, c.e2].forEach(function (o) {
      var q = M(c, o.centro), rr = c.ew * 0.78 * latido;
      ctx.save(); ctx.shadowColor = "#ff2d6f"; ctx.shadowBlur = rr * 0.9; corazon(ctx, q.x, q.y, rr, "#ff2d6f", c.ang);
      ctx.fillStyle = "rgba(255,255,255,.55)"; ctx.beginPath(); ctx.ellipse(q.x - rr * 0.38, q.y - rr * 0.25, rr * 0.2, rr * 0.12, c.ang - 0.5, 0, TAU); ctx.fill(); ctx.restore();
    });
    rubor(ctx, c, 1.2);
    var u = arriba(c), r = der(c);
    for (var i = 0; i < 6; i++) {
      var a = t / 1100 + i * (TAU / 6), s = Math.sin(a * 0.5 + i);
      var x = c.frente.x + r.x * Math.cos(a) * c.ancho * 0.7 + u.x * c.alto * (0.18 + 0.06 * Math.sin(a * 2));
      var y = c.frente.y + r.y * Math.cos(a) * c.ancho * 0.7 + u.y * c.alto * (0.18 + 0.06 * Math.sin(a * 2));
      ctx.save(); ctx.shadowColor = "#ff5c93"; ctx.shadowBlur = c.ancho * 0.03; corazon(ctx, x, y, c.ancho * (0.035 + 0.01 * i % 3), i % 2 ? "#ff7aa9" : "#ff3d78", c.ang + s * 0.4); ctx.restore();
    }
  }
  function lentesNieve(ctx, w, h, t) {
    velo(ctx, w, h, "rgba(170,210,255,.45)", "rgba(220,240,255,.1)", "screen", 0.9);
    particulas(copos, 60, w, h,
      function (W_, H_) { return { x: Math.random() * W_, y: -Math.random() * H_, v: H_ * (0.07 + Math.random() * 0.14), r: H_ * (0.003 + Math.random() * 0.007), f: Math.random() * 6 }; },
      function (p, W_, H_) { p.y += p.v / FPS; p.x += Math.sin(t / 900 + p.f) * 0.8; return p.y > H_ + 10; },
      function (p) { brillo(ctx, p.x, p.y, p.r * 2.2, "rgba(255,255,255,.95)", 0.95); });
    vineta(ctx, w, h, "rgba(40,80,150,.4)");
  }
  function lentesNieveCara(ctx, c, t) {
    var u = arriba(c), r = der(c);
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      var A = c.ancho, H = c.alto;
      ctx.shadowColor = "rgba(0,0,0,.35)"; ctx.shadowBlur = A * 0.03;
      var g = ctx.createLinearGradient(0, -H * 0.5, 0, 0); g.addColorStop(0, "#ff4545"); g.addColorStop(1, "#c4121f");
      ctx.fillStyle = g; ctx.beginPath();
      ctx.moveTo(-A * 0.52, H * 0.03);
      ctx.bezierCurveTo(-A * 0.5, -H * 0.3, -A * 0.1, -H * 0.55, A * 0.3, -H * 0.46);
      ctx.bezierCurveTo(A * 0.5, -H * 0.4, A * 0.58, -H * 0.2, A * 0.5, -H * 0.1);
      ctx.bezierCurveTo(A * 0.55, -H * 0.05, A * 0.55, 0, A * 0.52, H * 0.03);
      ctx.closePath(); ctx.fill();
      ctx.shadowBlur = 0; ctx.fillStyle = "#fff";
      ctx.beginPath(); ctx.ellipse(0, H * 0.02, A * 0.56, H * 0.07, 0, 0, TAU); ctx.fill();
      ctx.fillStyle = "rgba(0,0,0,.08)"; ctx.beginPath(); ctx.ellipse(0, H * 0.045, A * 0.54, H * 0.03, 0, 0, TAU); ctx.fill();
      var bamb = Math.sin(t / 380) * A * 0.02;
      ctx.shadowColor = "rgba(0,0,0,.3)"; ctx.shadowBlur = A * 0.02; ctx.fillStyle = "#fff";
      ctx.beginPath(); ctx.arc(A * 0.5 + bamb, -H * 0.08 + bamb, A * 0.085, 0, TAU); ctx.fill();
    });
  }
  function lentesVenezuela(ctx, w, h, t) {
    ctx.save(); ctx.globalCompositeOperation = "soft-light"; ctx.globalAlpha = 0.5;
    [["#ffd21f", 0], ["#1c47c4", 1], ["#d3141f", 2]].forEach(function (b) { ctx.fillStyle = b[0]; ctx.fillRect(0, h * b[1] / 3, w, h / 3 + 1); });
    ctx.restore();
    ctx.save(); ctx.globalCompositeOperation = "screen"; ctx.globalAlpha = 0.12;
    [["#ffd21f", 0], ["#1c47c4", 1], ["#d3141f", 2]].forEach(function (b) { ctx.fillStyle = b[0]; ctx.fillRect(0, h * b[1] / 3, w, h / 3 + 1); });
    ctx.restore();
    vineta(ctx, w, h, "rgba(10,20,70,.4)");
  }
  function lentesVenezuelaCara(ctx, c, t) {
    var u = arriba(c), r = der(c), n = 8;
    for (var i = 0; i < n; i++) {
      var k = (i / (n - 1)) * 2 - 1, arco = 1 - k * k;
      var x = c.frente.x + r.x * k * c.ancho * 0.62 + u.x * c.alto * (0.12 + 0.2 * arco);
      var y = c.frente.y + r.y * k * c.ancho * 0.62 + u.y * c.alto * (0.12 + 0.2 * arco);
      var pulso = 1 + 0.12 * Math.sin(t / 300 + i * 0.8);
      estrella5(ctx, x, y, c.ancho * 0.055 * pulso, c.ang + k * 0.4, "#ffffff", "#ffe27a");
    }
  }

  /* ------------------------------------------------------------------ lentes 2: bebé, mapache, barba, gym, taller */
  function elipse(ctx, x, y, rx, ry, fill, stroke, lw) {
    ctx.beginPath(); ctx.ellipse(x, y, Math.max(0.1, rx), Math.max(0.1, ry), 0, 0, TAU);
    if (fill) { ctx.fillStyle = fill; ctx.fill(); }
    if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = lw || 2; ctx.stroke(); }
  }
  function lentesBebeCara(ctx, c, t) {
    var u = arriba(c), r = der(c);
    piel(ctx, c, "#ffd9d0", "screen", 0.3, 8);
    rubor(ctx, c, 1.5);
    // gorrito con moño
    var hx = c.frente.x + u.x * c.alto * 0.04, hy = c.frente.y + u.y * c.alto * 0.04;
    sobre(ctx, hx, hy, c.ang, function () {
      var a = c.ancho;
      ctx.fillStyle = "#bfe6ff"; ctx.strokeStyle = "#7fb7e0"; ctx.lineWidth = Math.max(2, a * 0.015);
      ctx.beginPath(); ctx.moveTo(-a * 0.52, a * 0.05); ctx.quadraticCurveTo(-a * 0.5, -a * 0.62, 0, -a * 0.62); ctx.quadraticCurveTo(a * 0.5, -a * 0.62, a * 0.52, a * 0.05);
      ctx.quadraticCurveTo(0, -a * 0.1, -a * 0.52, a * 0.05); ctx.fill(); ctx.stroke();
      ctx.fillStyle = "#ff8fb8"; ctx.beginPath(); ctx.arc(-a * 0.08, -a * 0.58, a * 0.07, 0, TAU); ctx.arc(a * 0.08, -a * 0.58, a * 0.07, 0, TAU); ctx.fill();
      ctx.fillStyle = "#ff5c9a"; ctx.beginPath(); ctx.arc(0, -a * 0.58, a * 0.04, 0, TAU); ctx.fill();
    });
    // chupón
    var b = M(c, c.boca), s = c.ancho * 0.13;
    sobre(ctx, b.x, b.y + c.alto * 0.03, c.ang, function () {
      elipse(ctx, 0, 0, s * 1.5, s * 1.1, "#ffe6f0", "#ff8fb8", Math.max(2, s * 0.16));
      elipse(ctx, 0, 0, s * 0.55, s * 0.45, "#ff8fb8");
      ctx.strokeStyle = "#ff8fb8"; ctx.lineWidth = s * 0.28; ctx.beginPath(); ctx.arc(0, s * 1.65, s * 0.5, 0, TAU); ctx.stroke();
    });
    destellos(ctx, c, t, 4, "rgba(255,255,255,.9)");
  }
  function lentesMapacheCara(ctx, c, t) {
    var u = arriba(c), r = der(c), a = c.ancho;
    piel(ctx, c, "#b9b9c2", "multiply", 0.55, 6);
    // antifaz oscuro
    [c.e1, c.e2].forEach(function (o, i) {
      var p = M(c, o.centro), dir = i ? 1 : -1;
      sobre(ctx, p.x, p.y, c.ang + dir * 0.28, function () {
        ctx.save(); ctx.filter = "blur(" + Math.max(1, a * 0.012) + "px)";
        elipse(ctx, dir * a * 0.02, a * 0.01, a * 0.2, a * 0.13, "rgba(28,26,34,.92)");
        ctx.restore();
      });
    });
    // orejas
    [-1, 1].forEach(function (d) {
      var x = c.frente.x + r.x * d * a * 0.4 + u.x * c.alto * 0.07, y = c.frente.y + r.y * d * a * 0.4 + u.y * c.alto * 0.07;
      elipse(ctx, x, y, a * 0.13, a * 0.15, "#8d8d97", "#3a3a44", Math.max(2, a * 0.012));
      elipse(ctx, x, y + a * 0.01, a * 0.07, a * 0.09, "#e9b5bd");
    });
    // hocico y nariz
    var n = M(c, c.nariz);
    sobre(ctx, n.x, n.y + c.alto * 0.02, c.ang, function () {
      elipse(ctx, 0, a * 0.02, a * 0.17, a * 0.11, "rgba(235,235,240,.55)");
      ctx.fillStyle = "#1f1b22"; ctx.beginPath(); ctx.ellipse(0, -a * 0.01, a * 0.085, a * 0.06, 0, 0, TAU); ctx.fill();
      elipse(ctx, -a * 0.025, -a * 0.03, a * 0.025, a * 0.014, "rgba(255,255,255,.7)");
      // bigotes
      ctx.strokeStyle = "rgba(245,245,250,.9)"; ctx.lineWidth = Math.max(1.5, a * 0.008); ctx.lineCap = "round";
      [-1, 1].forEach(function (d) { for (var i = 0; i < 3; i++) { ctx.beginPath(); ctx.moveTo(d * a * 0.14, a * (0.03 + i * 0.025)); ctx.lineTo(d * a * 0.5, a * (0.0 + i * 0.07)); ctx.stroke(); } });
    });
  }
  function lentesBarbaCara(ctx, c, t, pal) {
    pal = pal || { a: "#ffffff", b: "#d6dbe2", pelo: "rgba(170,178,190,.55)", bigote: "#f6f7fa", bigoteBorde: "rgba(150,158,170,.6)", cejas: "#f3f4f8" };
    var u = arriba(c), r = der(c), a = c.ancho, h = c.alto;
    function P(k, d) { return { x: c.centro.x + r.x * k * a + u.x * d * h, y: c.centro.y + r.y * k * a + u.y * d * h }; }
    var bm = M(c, c.barbilla);
    var pts = c.oval.slice(); // contorno de la cara
    // barba: desde las patillas por la mandíbula hasta la barbilla, con mechones hacia abajo
    ctx.save();
    var ia = [], iz = [];
    var mA = M(c, c.mej1), mB = M(c, c.mej2);
    var sh = "rgba(0,0,0,.25)";
    ctx.shadowColor = sh; ctx.shadowBlur = a * 0.05; ctx.shadowOffsetY = a * 0.02;
    var g = ctx.createLinearGradient(bm.x - u.x * h * 0.5, bm.y - u.y * h * 0.5, bm.x - u.x * h * -0.4, bm.y - u.y * h * -0.4);
    g.addColorStop(0, pal.a); g.addColorStop(1, pal.b);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(c.ore1.x + r.x * a * 0.02, c.ore1.y + r.y * a * 0.02);
    ctx.quadraticCurveTo(mA.x - r.x * a * 0.18, mA.y - u.y * h * 0.1 - u.y * 0, bm.x - r.x * a * 0.5 + u.x * -h * 0.25, bm.y - u.y * -h * 0.25);
    ctx.quadraticCurveTo(bm.x - r.x * a * 0.2 - u.x * h * 0.25, bm.y - u.y * h * 0.25 + h * 0.22, bm.x - u.x * h * 0.22, bm.y - u.y * h * 0.22 + h * 0.2);
    ctx.quadraticCurveTo(bm.x + r.x * a * 0.2 - u.x * h * 0.25, bm.y - u.y * h * 0.25 + h * 0.22, bm.x + r.x * a * 0.5 + u.x * -h * 0.25, bm.y - u.y * -h * 0.25);
    ctx.quadraticCurveTo(mB.x + r.x * a * 0.18, mB.y, c.ore2.x - r.x * a * 0.02, c.ore2.y - r.y * a * 0.02);
    // borde interior (más cerca de la boca)
    var l = M(c, c.comA), rr = M(c, c.comB), lab = M(c, c.labio);
    ctx.quadraticCurveTo(rr.x + r.x * a * 0.12, rr.y - u.y * h * 0.02, rr.x, rr.y + h * 0.05);
    ctx.quadraticCurveTo(lab.x, lab.y + h * 0.08, l.x, l.y + h * 0.05);
    ctx.quadraticCurveTo(l.x - r.x * a * 0.12, l.y, c.ore1.x + r.x * a * 0.02, c.ore1.y + r.y * a * 0.02);
    ctx.closePath(); ctx.fill();
    ctx.restore();
    // textura de mechones
    ctx.save(); ctx.strokeStyle = pal.pelo; ctx.lineWidth = Math.max(1, a * 0.006); ctx.lineCap = "round";
    for (var i = 0; i < 26; i++) {
      var k = (i / 25) * 2 - 1, x0 = bm.x + r.x * k * a * 0.42, y0 = bm.y + r.y * k * a * 0.42 - h * 0.12 * (1 - Math.abs(k)) * 0 - h * (0.18 - 0.1 * Math.abs(k));
      ctx.beginPath(); ctx.moveTo(x0, y0); ctx.quadraticCurveTo(x0 + k * a * 0.03, y0 + h * 0.1, x0 + k * a * 0.05, y0 + h * (0.2 + 0.05 * Math.sin(i)));
      ctx.stroke();
    }
    ctx.restore();
    // bigote
    var lb = M(c, c.labio);
    sobre(ctx, lb.x - u.x * h * 0.015, lb.y - u.y * h * 0.015 - h * 0.005, c.ang, function () {
      ctx.fillStyle = pal.bigote; ctx.strokeStyle = pal.bigoteBorde; ctx.lineWidth = Math.max(1.5, a * 0.01);
      ctx.beginPath(); ctx.moveTo(0, -a * 0.03);
      ctx.bezierCurveTo(a * 0.1, -a * 0.09, a * 0.28, -a * 0.04, a * 0.3, a * 0.07);
      ctx.bezierCurveTo(a * 0.2, a * 0.03, a * 0.1, a * 0.06, 0, a * 0.04);
      ctx.bezierCurveTo(-a * 0.1, a * 0.06, -a * 0.2, a * 0.03, -a * 0.3, a * 0.07);
      ctx.bezierCurveTo(-a * 0.28, -a * 0.04, -a * 0.1, -a * 0.09, 0, -a * 0.03);
      ctx.closePath(); ctx.fill(); ctx.stroke();
    });
    // cejas canosas
    [[c.ce1, -1], [c.ce2, 1]].forEach(function (z) {
      var pts2 = z[0].map(function (p) { return M(c, p); });
      ctx.save(); ctx.strokeStyle = pal.cejas; ctx.lineWidth = Math.max(4, a * 0.04); ctx.lineCap = "round"; ctx.lineJoin = "round";
      ctx.shadowColor = "rgba(0,0,0,.3)"; ctx.shadowBlur = 3;
      ctx.beginPath(); pts2.forEach(function (p, i) { i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y); }); ctx.stroke(); ctx.restore();
    });
  }
  function musculos(ctx, c, t) {
    var u = arriba(c), r = der(c), a = c.ancho, h = c.alto;
    var bm = c.barbilla, w = a * 1.55, resp = 1 + 0.015 * Math.sin(t / 420);
    sobre(ctx, bm.x - u.x * h * 0.02, bm.y - u.y * h * 0.02, c.ang, function () {
      var piel0 = "#d9a07a", piel1 = "#b9795a";
      var gp = ctx.createLinearGradient(-w, 0, w, 0); gp.addColorStop(0, piel1); gp.addColorStop(0.5, piel0); gp.addColorStop(1, piel1);
      // cuello
      ctx.fillStyle = gp; ctx.beginPath(); ctx.moveTo(-a * 0.32, -h * 0.1); ctx.lineTo(-a * 0.42, h * 0.32); ctx.lineTo(a * 0.42, h * 0.32); ctx.lineTo(a * 0.32, -h * 0.1); ctx.fill();
      // hombros/brazos
      [-1, 1].forEach(function (d) {
        ctx.fillStyle = gp;
        ctx.beginPath(); ctx.ellipse(d * w * 0.58, h * 0.55, a * 0.4 * resp, h * 0.36, d * 0.25, 0, TAU); ctx.fill();   // deltoides
        ctx.beginPath(); ctx.ellipse(d * w * 0.8, h * 1.3, a * 0.36 * resp, h * 0.75, -d * 0.1, 0, TAU); ctx.fill();    // brazo
        ctx.fillStyle = "rgba(0,0,0,.14)"; ctx.beginPath(); ctx.ellipse(d * w * 0.74, h * 1.28, a * 0.12, h * 0.4, -d * 0.1, 0, TAU); ctx.fill();
      });
      // torso con camiseta sin mangas
      var ancho1 = w * 0.62, tw = ctx.createLinearGradient(0, 0, 0, h * 2.4); tw.addColorStop(0, "#ffffff"); tw.addColorStop(1, "#dfe3ea");
      ctx.fillStyle = tw; ctx.strokeStyle = "rgba(0,0,0,.25)"; ctx.lineWidth = Math.max(1.5, a * 0.01);
      ctx.beginPath(); ctx.moveTo(-ancho1 * 0.92, h * 0.28); ctx.quadraticCurveTo(-a * 0.5, h * 0.3, -a * 0.34, h * 0.14);
      ctx.quadraticCurveTo(0, h * 0.78, a * 0.34, h * 0.14); ctx.quadraticCurveTo(a * 0.5, h * 0.3, ancho1 * 0.92, h * 0.28);
      ctx.lineTo(ancho1 * 0.78, h * 3); ctx.lineTo(-ancho1 * 0.78, h * 3); ctx.closePath(); ctx.fill(); ctx.stroke();
      // pectorales marcados
      ctx.strokeStyle = "rgba(60,70,90,.22)"; ctx.lineWidth = Math.max(2, a * 0.02);
      ctx.beginPath(); ctx.moveTo(0, h * 0.62); ctx.quadraticCurveTo(0, h * 1.1, 0, h * 1.6); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-ancho1 * 0.55, h * 0.85); ctx.quadraticCurveTo(-a * 0.35, h * 1.0, -a * 0.04, h * 0.9); ctx.moveTo(ancho1 * 0.55, h * 0.85); ctx.quadraticCurveTo(a * 0.35, h * 1.0, a * 0.04, h * 0.9); ctx.stroke();
    });
  }
  function gorraAzul(ctx, c) {
    var a = c.ancho, u = arriba(c);
    sobre(ctx, c.frente.x + u.x * c.alto * 0.03, c.frente.y + u.y * c.alto * 0.03, c.ang, function () {
      ctx.fillStyle = "#1d3c87"; ctx.strokeStyle = "#102656"; ctx.lineWidth = Math.max(2, a * 0.012);
      ctx.beginPath(); ctx.moveTo(-a * 0.56, a * 0.08); ctx.quadraticCurveTo(-a * 0.55, -a * 0.62, 0, -a * 0.64); ctx.quadraticCurveTo(a * 0.55, -a * 0.62, a * 0.56, a * 0.08); ctx.quadraticCurveTo(0, -a * 0.02, -a * 0.56, a * 0.08); ctx.fill(); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-a * 0.56, a * 0.08); ctx.quadraticCurveTo(0, a * 0.2, a * 0.8, a * 0.1); ctx.quadraticCurveTo(a * 0.2, -a * 0.04, -a * 0.56, a * 0.02); ctx.fill(); ctx.stroke();
    });
  }
  function lentesGymCara(ctx, c, t) { musculos(ctx, c, t); piel(ctx, c, "#e0a98a", "overlay", 0.25, 6); gorraAzul(ctx, c); }
  function lentesGymGlobal(ctx, w, h, t) {
    var g = ctx.createRadialGradient(w / 2, h * 0.4, h * 0.2, w / 2, h / 2, h * 0.8); g.addColorStop(0, "rgba(0,0,0,0)"); g.addColorStop(1, "rgba(15,10,30,.55)");
    ctx.save(); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); ctx.restore();
  }
  function lentesTaller(ctx, w, h, t) {
    ctx.save(); ctx.globalCompositeOperation = "multiply"; ctx.fillStyle = "rgba(255,196,120,.38)"; ctx.fillRect(0, 0, w, h); ctx.restore();
    ctx.save(); ctx.globalCompositeOperation = "soft-light"; ctx.fillStyle = "rgba(255,170,70,.55)"; ctx.fillRect(0, 0, w, h); ctx.restore();
    vineta(ctx, w, h, "rgba(60,25,5,.5)");
    // aserrín cayendo
    ctx.save();
    for (var i = 0; i < 60; i++) {
      var x = (rnd(i) * w + Math.sin(t / 900 + i) * 14) % w, y = ((rnd(i + 50) * h + t * (0.04 + rnd(i + 9) * 0.05)) % h);
      ctx.fillStyle = "rgba(240,200,140," + (0.35 + rnd(i + 3) * 0.4) + ")"; ctx.beginPath(); ctx.arc(x, y, 1.5 + rnd(i + 7) * 2.5, 0, TAU); ctx.fill();
    }
    // caja de madera flotando
    var bw = w * 0.5, bh = bw * 0.28, bx = w * 0.5, by = h * 0.76 + Math.sin(t / 700) * h * 0.01, d = bh * 0.9;
    ctx.translate(bx, by);
    function cara(pts, col) { ctx.fillStyle = col; ctx.strokeStyle = "rgba(90,45,10,.9)"; ctx.lineWidth = Math.max(2, w * 0.004); ctx.beginPath(); pts.forEach(function (p, i) { i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]); }); ctx.closePath(); ctx.fill(); ctx.stroke(); }
    cara([[-bw / 2, 0], [-bw / 2 + d, -d * 0.6], [bw / 2 + d, -d * 0.6], [bw / 2, 0]], "#e4a25a");           // tapa
    cara([[-bw / 2, 0], [bw / 2, 0], [bw / 2, bh], [-bw / 2, bh]], "#c97d38");                              // frente
    cara([[bw / 2, 0], [bw / 2 + d, -d * 0.6], [bw / 2 + d, bh - d * 0.6], [bw / 2, bh]], "#a8612a");       // lado
    ctx.strokeStyle = "rgba(100,50,10,.55)"; ctx.lineWidth = 2;
    for (var j = 1; j < 4; j++) { ctx.beginPath(); ctx.moveTo(-bw / 2, bh * j / 4); ctx.lineTo(bw / 2, bh * j / 4 + (j % 2) * 2); ctx.stroke(); }
    for (var k = 1; k < 5; k++) { ctx.beginPath(); ctx.moveTo(-bw / 2 + d * k / 5, -d * 0.6 * k / 5); ctx.lineTo(bw / 2 + d * k / 5, -d * 0.6 * k / 5); ctx.stroke(); }
    ctx.restore();
  }
  function lentesTallerCara(ctx, c, t) {
    // gorro de carpintero (lápiz detrás de la oreja)
    var u = arriba(c), a = c.ancho;
    sobre(ctx, c.ore2.x, c.ore2.y - a * 0.06, c.ang - 0.5, function () {
      ctx.fillStyle = "#f5c518"; ctx.strokeStyle = "#7a5a00"; ctx.lineWidth = 2; ctx.fillRect(-a * 0.02, -a * 0.3, a * 0.045, a * 0.34); ctx.strokeRect(-a * 0.02, -a * 0.3, a * 0.045, a * 0.34);
      ctx.fillStyle = "#222"; ctx.beginPath(); ctx.moveTo(-a * 0.02, -a * 0.3); ctx.lineTo(a * 0.025, -a * 0.3); ctx.lineTo(a * 0.0, -a * 0.34); ctx.fill();
    });
  }

  /* ------------------------------------------------------------------ lentes 3: pintura, cómic, gafas, ojo azul, carita, escarcha, garabato */
  var pintCv = document.createElement("canvas");
  /** Estilo pintado / cómic: suaviza, reduce los colores a pocos tonos y marca los bordes con línea oscura. */
  function estiloPintura(ctx, w, h, niveles, borde, suave) {
    var sw = Math.max(96, Math.round(w / 3)), sh = Math.max(72, Math.round(h / 3));
    pintCv.width = sw; pintCv.height = sh;
    var px = pintCv.getContext("2d", { willReadFrequently: true });
    px.filter = "blur(" + suave + "px) saturate(1.5) contrast(1.1)";
    px.drawImage(ctx.canvas, 0, 0, sw, sh); px.filter = "none";
    var im = px.getImageData(0, 0, sw, sh), d = im.data, n = sw * sh, lum = new Float32Array(n), i, x, y;
    for (i = 0; i < n; i++) lum[i] = d[i * 4] * 0.3 + d[i * 4 + 1] * 0.59 + d[i * 4 + 2] * 0.11;
    var q = 255 / (niveles - 1);
    for (y = 1; y < sh - 1; y++) for (x = 1; x < sw - 1; x++) {
      var k = y * sw + x, e = Math.abs(lum[k + 1] - lum[k - 1]) + Math.abs(lum[k + sw] - lum[k - sw]), o = k * 4;
      if (e > borde) { d[o] *= 0.12; d[o + 1] *= 0.12; d[o + 2] *= 0.12; }
      else { d[o] = Math.round(d[o] / q) * q; d[o + 1] = Math.round(d[o + 1] / q) * q; d[o + 2] = Math.round(d[o + 2] / q) * q; }
    }
    px.putImageData(im, 0, 0);
    ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.imageSmoothingEnabled = true; ctx.drawImage(pintCv, 0, 0, w, h); ctx.restore();
  }
  function brillitos(ctx, w, h, t, n) {
    ctx.save(); ctx.fillStyle = "rgba(255,255,255,.95)"; ctx.shadowColor = "#fff"; ctx.shadowBlur = 8;
    for (var i = 0; i < n; i++) {
      var f = 0.5 + 0.5 * Math.sin(t / 380 + i * 2.1);
      destello(ctx, rnd(i + 1) * w, rnd(i + 50) * h, (0.012 + 0.022 * rnd(i + 9)) * h * (0.4 + f), t / 900 + i);
    }
    ctx.restore();
  }
  /** Contorno cerrado de un ojo (con los puntos del detector) para recortar lo que se pinta encima. */
  function recorteOjo(ctx, c, e) {
    if (!e.arriba.length || !e.abajo.length) return false;
    var p = e.arriba.concat(e.abajo.slice().reverse()).map(function (q) { return M(c, q); });
    ctx.beginPath(); p.forEach(function (q, i) { i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y); }); ctx.closePath(); ctx.clip();
    return true;
  }
  /** Iris grande y brillante (azul por defecto) con pupila y reflejo. */
  function irisColor(ctx, c, e, c0, c1, escala) {
    var p = M(c, e.iris), r = c.ew * (escala || 0.46);
    ctx.save();
    recorteOjo(ctx, c, e);
    var g = ctx.createRadialGradient(p.x, p.y, r * 0.2, p.x, p.y, r);
    g.addColorStop(0, "#05070a"); g.addColorStop(0.3, "#05070a"); g.addColorStop(0.36, c0); g.addColorStop(0.8, c1); g.addColorStop(1, "#0a1a2a");
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, TAU); ctx.fill();
    ctx.fillStyle = "rgba(255,255,255,.9)"; ctx.beginPath(); ctx.arc(p.x - r * 0.35, p.y - r * 0.38, r * 0.17, 0, TAU); ctx.fill();
    ctx.restore();
  }
  function lentesOjoAzulCara(ctx, c, t) {
    irisColor(ctx, c, c.e1, "#7fc6ff", "#2c78b8", 0.55); irisColor(ctx, c, c.e2, "#7fc6ff", "#2c78b8", 0.55);
  }
  function lentesGafasSol(ctx, c) {
    var o = M(c, c.ojos), A = c.ew;
    sobre(ctx, o.x, o.y, c.ang, function () {
      var sep = c.entre * 0.5, w2 = A * 1.05, h2 = A * 0.62;
      ctx.lineJoin = "round";
      [-1, 1].forEach(function (s) {
        var g = ctx.createLinearGradient(0, -h2, 0, h2); g.addColorStop(0, "#1c2a4a"); g.addColorStop(1, "#0a1020");
        ctx.fillStyle = g; ctx.strokeStyle = "#36456b"; ctx.lineWidth = A * 0.16;
        ctx.beginPath();
        ctx.moveTo(s * sep - w2, -h2 * 0.8); ctx.quadraticCurveTo(s * sep, -h2 * 1.15, s * sep + w2, -h2 * 0.85);
        ctx.lineTo(s * sep + w2 * 0.9, h2 * 0.45); ctx.quadraticCurveTo(s * sep, h2 * 1.2, s * sep - w2 * 0.95, h2 * 0.5); ctx.closePath();
        ctx.fill(); ctx.stroke();
        ctx.fillStyle = "rgba(255,255,255,.16)"; ctx.beginPath(); ctx.ellipse(s * sep - w2 * 0.35, -h2 * 0.35, w2 * 0.3, h2 * 0.14, -0.3, 0, TAU); ctx.fill();
      });
      ctx.strokeStyle = "#36456b"; ctx.lineWidth = A * 0.14; ctx.beginPath(); ctx.moveTo(-sep + w2, -h2 * 0.45); ctx.quadraticCurveTo(0, -h2 * 0.8, sep - w2, -h2 * 0.45); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-sep - w2, -h2 * 0.5); ctx.lineTo(-c.ancho * 0.52, -h2 * 0.3); ctx.moveTo(sep + w2, -h2 * 0.5); ctx.lineTo(c.ancho * 0.52, -h2 * 0.3); ctx.stroke();
    });
  }
  function lentesGafasNerd(ctx, c) {
    var o = M(c, c.ojos), A = c.ew;
    sobre(ctx, o.x, o.y, c.ang, function () {
      var sep = c.entre * 0.5, r = A * 1.0;
      [-1, 1].forEach(function (s) {
        ctx.fillStyle = "rgba(255,255,255,.07)"; ctx.beginPath(); ctx.arc(s * sep, 0, r, 0, TAU); ctx.fill();
        ctx.strokeStyle = "#0d0d10"; ctx.lineWidth = A * 0.22; ctx.beginPath(); ctx.arc(s * sep, 0, r, 0, TAU); ctx.stroke();
        ctx.strokeStyle = "rgba(255,255,255,.55)"; ctx.lineWidth = A * 0.06; ctx.beginPath(); ctx.arc(s * sep, 0, r * 0.7, Math.PI * 1.15, Math.PI * 1.45); ctx.stroke();
      });
      ctx.strokeStyle = "#0d0d10"; ctx.lineWidth = A * 0.2; ctx.beginPath(); ctx.moveTo(-sep + r, -r * 0.1); ctx.quadraticCurveTo(0, -r * 0.45, sep - r, -r * 0.1); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-sep - r, -r * 0.1); ctx.lineTo(-c.ancho * 0.52, 0); ctx.moveTo(sep + r, -r * 0.1); ctx.lineTo(c.ancho * 0.52, 0); ctx.stroke();
    });
  }
  function lentesCaritaFeliz(ctx, c, t) {
    ctx.save();
    var g = ctx.createRadialGradient(c.centro.x, c.centro.y - c.alto * 0.1, c.ancho * 0.1, c.centro.x, c.centro.y, c.alto * 0.6);
    g.addColorStop(0, "#ffffff"); g.addColorStop(1, "#dcdcdc");
    ctx.fillStyle = g; trazoOval(ctx, ovalM(c)); ctx.fill();
    ctx.restore();
    [c.e1, c.e2].forEach(function (e) {
      var p = M(c, e.centro);
      sobre(ctx, p.x, p.y, c.ang, function () {
        ctx.fillStyle = "#111"; ctx.beginPath(); ctx.ellipse(0, 0, c.ew * 0.42, c.ew * 0.58, 0, 0, TAU); ctx.fill();
        ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.arc(-c.ew * 0.12, -c.ew * 0.2, c.ew * 0.14, 0, TAU); ctx.fill();
      });
    });
    var b = M(c, c.boca), a = c.ancho, prof = a * (0.16 + 0.025 * Math.sin(t / 320));
    sobre(ctx, b.x, b.y - c.alto * 0.04, c.ang, function () {
      ctx.strokeStyle = "#111"; ctx.lineWidth = Math.max(3, a * 0.035); ctx.lineCap = "round";
      ctx.beginPath(); ctx.moveTo(-a * 0.3, 0); ctx.quadraticCurveTo(0, prof * 2, a * 0.3, 0); ctx.stroke();
    });
    var n = M(c, c.nariz);
    ctx.fillStyle = "rgba(0,0,0,.18)"; ctx.beginPath(); ctx.ellipse(n.x, n.y, c.ancho * 0.03, c.ancho * 0.018, c.ang, 0, TAU); ctx.fill();
  }
  function lentesBarbaNegra(ctx, c, t) { lentesBarbaCara(ctx, c, t, { a: "#2e2e33", b: "#0c0c0f", pelo: "rgba(0,0,0,.55)", bigote: "#16161a", bigoteBorde: "rgba(0,0,0,.6)", cejas: "#141417" }); }
  function espinaHielo(ctx, x, y, ang, largo, ancho) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(ang);
    var g = ctx.createLinearGradient(0, 0, largo, 0); g.addColorStop(0, "rgba(235,248,255,.95)"); g.addColorStop(1, "rgba(120,190,235,.85)");
    ctx.fillStyle = g; ctx.beginPath(); ctx.moveTo(0, -ancho / 2); ctx.lineTo(largo, 0); ctx.lineTo(0, ancho / 2); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,.7)"; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(largo * 0.85, 0); ctx.stroke();
    ctx.restore();
  }
  function lentesEscarchaGlobal(ctx, w, h, t) {
    var m = Math.min(w, h), gr = ctx.createRadialGradient(w / 2, h / 2, m * 0.3, w / 2, h / 2, Math.max(w, h) * 0.72);
    gr.addColorStop(0, "rgba(170,225,255,0)"); gr.addColorStop(1, "rgba(190,235,255,.8)");
    ctx.save(); ctx.fillStyle = gr; ctx.fillRect(0, 0, w, h);
    var i, k;
    for (k = 0; k < 4; k++) {
      var horiz = k < 2, len = horiz ? w : h, cant = horiz ? 15 : 11;
      for (i = 0; i < cant; i++) {
        var p = (i + 0.5) / cant * len, L = m * (0.05 + 0.1 * rnd(i + k * 31)), A = m * (0.025 + 0.03 * rnd(i + k * 7 + 3));
        if (k === 0) espinaHielo(ctx, p, 0, Math.PI / 2, L, A);
        else if (k === 1) espinaHielo(ctx, p, h, -Math.PI / 2, L, A);
        else if (k === 2) espinaHielo(ctx, 0, p, 0, L, A);
        else espinaHielo(ctx, w, p, Math.PI, L, A);
      }
    }
    ctx.fillStyle = "rgba(255,255,255,.9)";
    for (i = 0; i < 46; i++) {
      var x = (rnd(i + 3) * w + Math.sin(t / 700 + i) * 12 + w) % w, y = (rnd(i + 80) * h + t * 0.045 * (0.5 + rnd(i + 5))) % h;
      ctx.beginPath(); ctx.arc(x, y, m * (0.004 + 0.006 * rnd(i + 11)), 0, TAU); ctx.fill();
    }
    ctx.restore();
  }
  function lentesEscarchaCara(ctx, c, t) {
    piel(ctx, c, "#bfe6ff", "screen", 0.28, 5);
    irisColor(ctx, c, c.e1, "#aef2ff", "#1fa5e8", 0.5); irisColor(ctx, c, c.e2, "#aef2ff", "#1fa5e8", 0.5);
    [c.e1, c.e2].forEach(function (e) { var p = M(c, e.centro); brillo(ctx, p.x, p.y, c.ew * 1.3, "rgba(120,220,255,.55)", 0.55); });
  }
  function ojoGarabato(ctx, c, e, lado, t) {
    var p = M(c, e.centro), r = c.ew * 1.0, mira = Math.sin(t / 900) * 0.25;
    sobre(ctx, p.x, p.y, c.ang, function () {
      ctx.lineJoin = "round"; ctx.lineCap = "round";
      function forma() {
        ctx.beginPath(); ctx.moveTo(-r, r * 0.1); ctx.bezierCurveTo(-r * 0.6, -r * 0.75, r * 0.7, -r * 0.8, r, r * 0.05);
        ctx.bezierCurveTo(r * 0.6, r * 0.85, -r * 0.6, r * 0.85, -r, r * 0.1); ctx.closePath();
      }
      forma(); ctx.fillStyle = "#e9e9e9"; ctx.fill();
      ctx.save(); forma(); ctx.clip();
      ctx.fillStyle = "#0b0b0b"; ctx.beginPath(); ctx.arc(r * (0.35 + mira * lado), r * 0.12, r * 0.28, 0, TAU); ctx.fill();
      ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.arc(r * (0.28 + mira * lado), r * 0.04, r * 0.07, 0, TAU); ctx.fill();
      ctx.fillStyle = "rgba(255,255,255,.35)"; ctx.fillRect(-r, -r, r * 2, r * 0.95);
      ctx.restore();
      forma(); ctx.strokeStyle = "#fff"; ctx.lineWidth = r * 0.34; ctx.stroke();
      forma(); ctx.strokeStyle = "#0b0b0b"; ctx.lineWidth = r * 0.17; ctx.stroke();
      ctx.strokeStyle = "#0b0b0b"; ctx.lineWidth = r * 0.2;
      ctx.beginPath(); ctx.moveTo(-r * 1.05, -r * 0.1); ctx.lineTo(r * 1.05, -r * 0.04); ctx.stroke();
      ctx.lineWidth = r * 0.14; ctx.beginPath(); ctx.moveTo(-r * 0.7, r * 1.15); ctx.quadraticCurveTo(0, r * 1.35, r * 0.7, r * 1.15); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-r * 0.9, -r * 1.2); ctx.quadraticCurveTo(0, -r * (1.55 + lado * 0.15), r * 0.9, -r * 1.05); ctx.stroke();
    });
  }
  function lentesGarabatoCara(ctx, c, t) {
    ojoGarabato(ctx, c, c.e1, -1, t); ojoGarabato(ctx, c, c.e2, 1, t);
    var b = M(c, c.boca), a = c.ancho;
    sobre(ctx, b.x, b.y + c.alto * 0.02, c.ang, function () {
      ctx.lineCap = "round"; ctx.strokeStyle = "#fff"; ctx.lineWidth = a * 0.06;
      ctx.beginPath(); ctx.moveTo(-a * 0.25, 0); ctx.quadraticCurveTo(0, a * 0.04, a * 0.25, -a * 0.02); ctx.stroke();
      ctx.strokeStyle = "#0b0b0b"; ctx.lineWidth = a * 0.03; ctx.stroke();
    });
  }

  /* ------------------------------------------------------------------ efectos de cara */
  function ojosGrandes(c, f, r) { return [W(c.e1.centro, c.ew * r, f), W(c.e2.centro, c.ew * r, f)]; }

  var CARAS = [
    { id: "ninguna", n: "Sin cara", e: "🚫" },
    { id: "l-agua", n: "Bajo el agua", e: "🌊", grupo: "Lentes", css: "saturate(1.15)",
      global: function (ctx, w, h, t) { lentesAgua(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesAguaCara(ctx, c, t); } },
    { id: "l-galaxia", n: "Galaxia", e: "🪐", grupo: "Lentes", css: "brightness(.72) contrast(1.25) saturate(1.4) hue-rotate(200deg)",
      global: function (ctx, w, h, t) { lentesGalaxia(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesGalaxiaCara(ctx, c, t); } },
    { id: "l-fuego", n: "Fuego", e: "🔥", grupo: "Lentes", css: "saturate(1.3) contrast(1.1) brightness(.92)",
      global: function (ctx, w, h, t) { lentesFuego(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesFuegoCara(ctx, c, t); } },
    { id: "l-flores", n: "Flores", e: "🌺", grupo: "Lentes", css: "saturate(1.15) brightness(1.05)",
      global: function (ctx, w, h, t) { lentesFlores(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesFloresCara(ctx, c, t); } },
    { id: "l-cyber", n: "Cyber neón", e: "🤖", grupo: "Lentes", css: "contrast(1.25) saturate(1.6)",
      global: function (ctx, w, h, t) { lentesCyber(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesCyberCara(ctx, c, t); } },
    { id: "l-amor", n: "Enamorado", e: "😍", grupo: "Lentes", css: "saturate(1.2) brightness(1.05)",
      global: function (ctx, w, h, t) { lentesAmor(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesAmorCara(ctx, c, t); } },
    { id: "l-nieve", n: "Nevada", e: "❄️", grupo: "Lentes", css: "saturate(.9) brightness(1.05)",
      global: function (ctx, w, h, t) { lentesNieve(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesNieveCara(ctx, c, t); } },
    { id: "l-venezuela", n: "Venezuela", e: "🇻🇪", grupo: "Lentes", css: "saturate(1.2) contrast(1.05)",
      global: function (ctx, w, h, t) { lentesVenezuela(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesVenezuelaCara(ctx, c, t); } },
    { id: "l-bebe", n: "Bebé", e: "👶", grupo: "Lentes", css: "brightness(1.08) saturate(1.1) contrast(.95)",
      warps: function (c) { return ojosGrandes(c, 0.4, 1.2).concat([W(c.mej1, c.ancho * 0.34, 0.4), W(c.mej2, c.ancho * 0.34, 0.4), W(c.barbilla, c.ancho * 0.5, 0.25), W(c.nariz, c.ancho * 0.25, -0.2)]); },
      dibujar: function (ctx, c, t) { lentesBebeCara(ctx, c, t); } },
    { id: "l-mapache", n: "Mapache", e: "🦝", grupo: "Lentes", css: "saturate(.9) contrast(1.05)",
      warps: function (c) { return [W(c.nariz, c.ancho * 0.3, 0.3)]; },
      dibujar: function (ctx, c, t) { lentesMapacheCara(ctx, c, t); } },
    { id: "l-barba", n: "Barba blanca", e: "🧓", grupo: "Lentes", css: "contrast(1.05)",
      dibujar: function (ctx, c, t) { lentesBarbaCara(ctx, c, t); } },
    { id: "l-gym", n: "Gym Hero", e: "💪", grupo: "Lentes", css: "contrast(1.12) saturate(1.1)",
      global: function (ctx, w, h, t) { lentesGymGlobal(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesGymCara(ctx, c, t); } },
    { id: "l-taller", n: "Taller", e: "🪵", grupo: "Lentes", css: "saturate(1.1)",
      global: function (ctx, w, h, t) { lentesTaller(ctx, w, h, t); }, dibujar: function (ctx, c, t) { lentesTallerCara(ctx, c, t); } },
    { id: "l-pintura", n: "Pintura", e: "🎨", grupo: "Lentes", css: "saturate(1.25)",
      base: function (ctx, w, h) { estiloPintura(ctx, w, h, 6, 40, 2.2); },
      global: function (ctx, w, h, t) { brillitos(ctx, w, h, t, 16); } },
    { id: "l-comic", n: "Cómic", e: "🖍️", grupo: "Lentes", css: "contrast(1.15) saturate(1.4)",
      base: function (ctx, w, h) { estiloPintura(ctx, w, h, 4, 26, 1.2); } },
    { id: "l-gafas-sol", n: "Gafas pintadas", e: "🕶️", grupo: "Lentes", css: "saturate(1.2)",
      base: function (ctx, w, h) { estiloPintura(ctx, w, h, 7, 44, 2.6); },
      dibujar: function (ctx, c) { lentesGafasSol(ctx, c); } },
    { id: "l-gafas-nerd", n: "Gafas nerd", e: "🤓", grupo: "Lentes",
      warps: function (c) { return ojosGrandes(c, 0.3, 1.1); },
      dibujar: function (ctx, c) { lentesGafasNerd(ctx, c); } },
    { id: "l-ojo-azul", n: "Ojo azul", e: "👁️", grupo: "Lentes", css: "contrast(1.08)",
      warps: function (c) { return ojosGrandes(c, 0.62, 1.2); },
      dibujar: function (ctx, c, t) { lentesOjoAzulCara(ctx, c, t); } },
    { id: "l-feliz", n: "Carita feliz", e: "😁", grupo: "Lentes",
      warps: function (c) { return [W(c.boca, c.ancho * 0.5, 0.3)]; },
      dibujar: function (ctx, c, t) { lentesCaritaFeliz(ctx, c, t); } },
    { id: "l-barba-negra", n: "Barba negra", e: "🧔", grupo: "Lentes", css: "contrast(1.05)",
      dibujar: function (ctx, c, t) { lentesBarbaNegra(ctx, c, t); } },
    { id: "l-escarcha", n: "Escarcha", e: "🥶", grupo: "Lentes", css: "saturate(.85) brightness(1.05) hue-rotate(-8deg)",
      global: function (ctx, w, h, t) { lentesEscarchaGlobal(ctx, w, h, t); },
      dibujar: function (ctx, c, t) { lentesEscarchaCara(ctx, c, t); } },
    { id: "l-garabato", n: "Ojos de garabato", e: "✏️", grupo: "Lentes", css: "grayscale(1) contrast(1.15)",
      dibujar: function (ctx, c, t) { lentesGarabatoCara(ctx, c, t); } },
    { id: "kawaii", n: "Kawaii", e: "🌸", grupo: "Manga",
      warps: function (c) { return ojosGrandes(c, 0.5, 1.15).concat([W(c.barbilla, c.ancho * 0.5, -0.1)]); },
      css: "brightness(1.07) saturate(1.12) contrast(.97)",
      dibujar: function (ctx, c, t) { piel(ctx, c, "#fff0f4", "screen", 0.16, 6); rubor(ctx, c, 1); ojosManga(ctx, c, t); destellos(ctx, c, t, 7, "rgba(255,255,255,.95)"); } },
    { id: "shojo", n: "Shōjo", e: "💖", grupo: "Manga",
      warps: function (c) { return ojosGrandes(c, 0.55, 1.2).concat([W(c.barbilla, c.ancho * 0.5, -0.12)]); },
      css: "brightness(1.08) saturate(1.2) contrast(.95)",
      global: function (ctx, w, h, t) { vineta(ctx, w, h, "rgba(255,150,200,.42)"); lluviaPetalos(ctx, w, h, t); },
      dibujar: function (ctx, c, t) { piel(ctx, c, "#fff0f4", "screen", 0.2, 7); rubor(ctx, c, 1.15); ojosManga(ctx, c, t); destellos(ctx, c, t, 10, "rgba(255,225,240,.95)"); } },
    { id: "shonen", n: "Shōnen", e: "🔥", grupo: "Manga",
      warps: function (c) { return ojosGrandes(c, 0.28, 1.0); },
      css: "contrast(1.3) saturate(1.1)",
      global: function (ctx, w, h, t, caras) {
        var cx = caras.length ? caras[0].centro.x : w / 2, cy = caras.length ? caras[0].centro.y : h / 2, r0 = caras.length ? caras[0].alto * 0.85 : h * 0.3;
        lineasVelocidad(ctx, w, h, cx, cy, t, r0, "rgba(255,255,255,.55)", 70);
        textoManga(ctx, "ゴゴゴ", w * 0.17, h * 0.1, h * 0.1, -0.12); textoManga(ctx, "ゴゴゴ", w * 0.83, h * 0.9, h * 0.1, -0.12);
      },
      dibujar: function (ctx, c, t) {
        ojosManga(ctx, c, t);
        // vena de enojo y gota de sudor
        var u = arriba(c), p = { x: c.ore2.x - c.ancho * 0.05 + u.x * c.alto * 0.2, y: c.ore2.y - c.ancho * 0.05 + u.y * c.alto * 0.2 };
        ctx.strokeStyle = "#e5252a"; ctx.lineWidth = Math.max(2.5, c.ancho * 0.018); ctx.lineCap = "round";
        var s = c.ancho * 0.06;
        for (var i = 0; i < 4; i++) {
          var a = i * Math.PI / 2 + Math.PI / 4;
          ctx.beginPath(); ctx.arc(p.x + Math.cos(a) * s * 1.1, p.y + Math.sin(a) * s * 1.1, s, a + Math.PI * 0.75, a + Math.PI * 1.25); ctx.stroke();
        }
        var g = { x: c.ore1.x + c.ancho * 0.06, y: c.ore1.y - c.alto * 0.05 + ((t / 20) % (c.alto * 0.12)) };
        var gg = ctx.createLinearGradient(g.x, g.y - c.alto * 0.07, g.x, g.y + c.alto * 0.04);
        gg.addColorStop(0, "#d7f3ff"); gg.addColorStop(1, "#58b5ee");
        ctx.fillStyle = gg; ctx.beginPath(); ctx.moveTo(g.x, g.y - c.alto * 0.07);
        ctx.bezierCurveTo(g.x + c.alto * 0.05, g.y - c.alto * 0.01, g.x + c.alto * 0.04, g.y + c.alto * 0.04, g.x, g.y + c.alto * 0.04);
        ctx.bezierCurveTo(g.x - c.alto * 0.04, g.y + c.alto * 0.04, g.x - c.alto * 0.05, g.y - c.alto * 0.01, g.x, g.y - c.alto * 0.07);
        ctx.fill();
      } },
    { id: "neko", n: "Neko", e: "🐱", grupo: "Manga",
      warps: function (c) { return ojosGrandes(c, 0.42, 1.1); },
      css: "brightness(1.05) saturate(1.1)",
      dibujar: function (ctx, c, t) {
        orejasGato(ctx, c);
        rubor(ctx, c, 1);
        ojosManga(ctx, c, t);
        // bigotes, nariz y colmillito
        sobre(ctx, c.nariz.x, c.nariz.y, c.ang, function () {
          ctx.strokeStyle = "rgba(255,255,255,.9)"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.007); ctx.lineCap = "round";
          [-1, 1].forEach(function (s) { [-1, 0, 1].forEach(function (k) {
            ctx.beginPath(); ctx.moveTo(s * c.ancho * 0.12, c.ancho * 0.02 + k * c.ancho * 0.015);
            ctx.quadraticCurveTo(s * c.ancho * 0.3, c.ancho * 0.0 + k * c.ancho * 0.05, s * c.ancho * 0.5, c.ancho * (-0.03 + k * 0.1)); ctx.stroke();
          }); });
          ctx.fillStyle = "#ff7fa8"; ctx.beginPath();
          ctx.moveTo(0, c.ancho * 0.045); ctx.lineTo(-c.ancho * 0.03, c.ancho * 0.0); ctx.lineTo(c.ancho * 0.03, c.ancho * 0.0); ctx.closePath(); ctx.fill();
        });
        var b = c.comA;
        ctx.fillStyle = "#fff"; ctx.strokeStyle = "rgba(0,0,0,.25)"; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(b.x + c.ancho * 0.01, b.y); ctx.lineTo(b.x + c.ancho * 0.045, b.y); ctx.lineTo(b.x + c.ancho * 0.03, b.y + c.ancho * 0.05); ctx.closePath(); ctx.fill(); ctx.stroke();
      } },
    { id: "anime", n: "Anime", e: "✨", grupo: "Manga",
      warps: function (c) { return ojosGrandes(c, 0.32, 1.05); },
      css: "saturate(1.5) contrast(1.1) brightness(1.1)",
      global: function (ctx, w, h) {
        // brillo suave (bloom) como en las películas de anime
        ctx.save(); ctx.globalCompositeOperation = "screen"; ctx.globalAlpha = 0.28; ctx.filter = "blur(10px) brightness(1.15)";
        ctx.drawImage(ctx.canvas, 0, 0, w, h); ctx.restore();
      },
      dibujar: function (ctx, c, t) { piel(ctx, c, "#fff4f0", "screen", 0.14, 6); ojosManga(ctx, c, t); destellos(ctx, c, t, 6, "rgba(255,255,230,.95)"); } },

    { id: "shrek", n: "Shrek", e: "👹", grupo: "Deformes",
      warps: function (c) {
        return [W(c.nariz, c.ancho * 0.36, 0.62), W(c.mej1, c.ancho * 0.3, 0.28), W(c.mej2, c.ancho * 0.3, 0.28), W(c.boca, c.ancho * 0.36, 0.2)];
      },
      dibujar: function (ctx, c) {
        orejaOgro(ctx, c, c.ore1, -1, c.ancho * 0.46, c.alto * 0.17);
        orejaOgro(ctx, c, c.ore2, 1, c.ancho * 0.46, c.alto * 0.17);
        piel(ctx, c, "#8cc24a", "multiply", 0.85, 3);
        piel(ctx, c, "#6aa82a", "overlay", 0.45, 5);
        // cejas gruesas
        ctx.strokeStyle = "#5b3a1a"; ctx.lineWidth = Math.max(3, c.ancho * 0.03); ctx.lineCap = "round"; ctx.lineJoin = "round";
        [c.ce1, c.ce2].forEach(function (ce) {
          ctx.beginPath(); ce.forEach(function (p, i) { var q = M(c, { x: p.x, y: p.y - c.alto * 0.015 }); if (i) ctx.lineTo(q.x, q.y); else ctx.moveTo(q.x, q.y); }); ctx.stroke();
        });
      } },
    { id: "fiona", n: "Fiona", e: "👸", grupo: "Deformes",
      warps: function (c) { return ojosGrandes(c, 0.24, 1.0).concat([W(c.nariz, c.ancho * 0.28, 0.2)]); },
      dibujar: function (ctx, c, t) {
        orejaOgro(ctx, c, c.ore1, -1, c.ancho * 0.24, c.alto * 0.09);
        orejaOgro(ctx, c, c.ore2, 1, c.ancho * 0.24, c.alto * 0.09);
        piel(ctx, c, "#a6d96a", "multiply", 0.75, 3);
        piel(ctx, c, "#7cc23a", "overlay", 0.4, 5);
        rubor(ctx, c, 0.5);
        cabelloFiona(ctx, c);
      } },
    { id: "saltones", n: "Ojos saltones", e: "😳", grupo: "Deformes",
      warps: function (c) { return ojosGrandes(c, 0.88, 1.05); } },
    { id: "cabezon", n: "Cabezón", e: "🗿", grupo: "Deformes",
      warps: function (c) { return [W(c.centro, c.alto * 0.85, 0.55)]; } },
    { id: "nariz", n: "Narizón", e: "🤥", grupo: "Deformes",
      warps: function (c) { return [W(c.nariz, c.ancho * 0.42, 0.82)]; } },
    { id: "sapo", n: "Boca de sapo", e: "🐸", grupo: "Deformes",
      warps: function (c) { return [W(c.boca, c.ancho * 0.52, 0.66), W(c.mej1, c.ancho * 0.3, 0.3), W(c.mej2, c.ancho * 0.3, 0.3)].concat(ojosGrandes(c, 0.3, 0.9)); },
      dibujar: function (ctx, c) { piel(ctx, c, "#9ad04a", "multiply", 0.55, 4); } }
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
    var f = FILTROS[0], ef = null, i;
    for (i = 0; i < FILTROS.length; i++) if (FILTROS[i].id === estado.filtro) f = FILTROS[i];
    for (i = 0; i < CARAS.length; i++) if (CARAS[i].id === estado.cara && CARAS[i].id !== "ninguna") ef = CARAS[i];
    var caras = (ef || estado.pegatina) ? detectar(video, w, h, ahora) : [];
    var ws = [];
    caras.forEach(function (c) { c._w = (ef && ef.warps) ? ef.warps(c) : []; ws = ws.concat(c._w); });
    var fuente = video;
    if (ws.length) {
      var g = deformar(video, w, h, ws);
      if (g) fuente = g; else caras.forEach(function (c) { c._w = []; });
    }
    ctx.save();
    if (f.espejo) { ctx.translate(w, 0); ctx.scale(-1, 1); }
    var css = ((f.css || "") + " " + ((ef && ef.css) || "")).trim();
    if (f.pixel) {
      chico.width = Math.max(8, Math.round(w / f.pixel)); chico.height = Math.max(8, Math.round(h / f.pixel));
      chico.getContext("2d").drawImage(fuente, 0, 0, chico.width, chico.height);
      ctx.imageSmoothingEnabled = false; ctx.drawImage(chico, 0, 0, w, h); ctx.imageSmoothingEnabled = true;
    } else {
      if (css) ctx.filter = css;
      ctx.drawImage(fuente, 0, 0, w, h);
      ctx.filter = "none";
    }
    if (f.semitono) {
      semitono(ctx, w, h);
      lineasVelocidad(ctx, w, h, w / 2, h / 2, ahora, h * 0.55, "rgba(0,0,0,.55)", 46);
      textoManga(ctx, "ドン!", w * 0.84, h * 0.12, h * 0.11, 0.12);
    }
    if (ef && ef.base) ef.base(ctx, w, h, ahora);
    if (ef && ef.global) ef.global(ctx, w, h, ahora, caras);
    if (ef && ef.dibujar) caras.forEach(function (c) { ef.dibujar(ctx, c, ahora, w, h); });
    ctx.restore();
    dibujarMemes(ctx, w, h, ahora, caras);
  }

  /* ------------------------------------------------------------------ cámara */
  var sesiones = 0;
  var errorFx = "";
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
      refrescarUI();
    }
    pista.stop = function () { pararOrig(); parar(); };
    vt.addEventListener("ended", function () { pararOrig(); parar(); });
    pista.getSettings = function () { return Object.assign({}, vt.getSettings ? vt.getSettings() : {}, { width: canvas.width, height: canvas.height, frameRate: FPS }); };
    pista.getCapabilities = function () { return vt.getCapabilities ? vt.getCapabilities() : {}; };
    pista.applyConstraints = function (c) { return vt.applyConstraints ? vt.applyConstraints(c) : Promise.resolve(); };

    sesiones++;
    return new MediaStream([pista].concat(stream.getAudioTracks()));
  }

  md.getUserMedia = async function (constraints) {
    var stream = await original(constraints);
    if (!constraints || !constraints.video) return stream;
    var vt = stream.getVideoTracks()[0];
    if (!vt) return stream;
    // Hay cámara: es una videollamada. Recién ahora aparecen los botones de efectos y juegos.
    window.__chamosVideoActivo = true;
    montarUI();
    try { window.dispatchEvent(new Event("chamos-video")); } catch (x) { /* nada */ }
    try { return await procesar(stream, vt); } catch (e) {
      errorFx = String(e && e.message ? e.message : e).slice(0, 120);
      try { console.warn("Chamos filtros: se usa la cámara sin efectos", errorFx); } catch (x) { /* nada */ }
      refrescarUI();
      return stream;
    }
  };


  /* ------------------------------------------------------------------ interfaz: carrusel de abajo */
  var host = null, raiz = null, abierto = true, memesAbierto = false, errorFx = "";

  var CSS = [
    ":host{all:initial}",
    "*{box-sizing:border-box;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}",
    "#fab{position:fixed;left:12px;bottom:112px;width:52px;height:52px;border-radius:50%;border:2px solid #f4b54a;background:rgba(12,16,32,.9);color:#fff;font-size:26px;line-height:1;box-shadow:0 0 14px rgba(244,181,74,.6);touch-action:manipulation;padding:0}",
    "#fab.av{animation:p 1s infinite}@keyframes p{50%{transform:scale(1.18)}}",
    "#barra{position:fixed;left:0;right:0;bottom:174px;background:linear-gradient(to top,rgba(8,10,22,.82),rgba(8,10,22,0));padding-top:12px}",
    "#barra[hidden]{display:none}",
    "#msg{color:#fff;font-size:12px;text-align:center;padding:0 12px 4px;text-shadow:0 1px 3px #000}",
    ".car{display:flex;gap:10px;align-items:flex-start;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x proximity;padding:6px 14px 8px;scrollbar-width:none;-webkit-overflow-scrolling:touch}",
    ".car::-webkit-scrollbar{display:none}",
    ".it{flex:0 0 68px;display:flex;flex-direction:column;align-items:center;gap:4px;background:none;border:0;padding:0;color:#fff;scroll-snap-align:center;touch-action:manipulation}",
    ".ic{width:62px;height:62px;border-radius:50%;border:3px solid rgba(255,255,255,.65);background:rgba(27,37,67,.92);font-size:30px;line-height:1;display:flex;align-items:center;justify-content:center;transition:transform .15s,border-color .15s,box-shadow .15s}",
    ".it.on .ic{border-color:#f4b54a;box-shadow:0 0 16px #f4b54a;transform:scale(1.12);background:rgba(58,48,22,.95)}",
    ".it small{font-size:10px;font-weight:700;text-shadow:0 1px 3px #000;max-width:68px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
    ".sep{flex:0 0 auto;align-self:stretch;display:flex;align-items:center;justify-content:center;writing-mode:vertical-rl;transform:rotate(180deg);font-style:normal;font-size:10px;font-weight:800;letter-spacing:.12em;text-transform:uppercase;color:#f4b54a;text-shadow:0 1px 3px #000;padding:0 2px;border-right:1px solid rgba(255,255,255,.25)}",
    "#panel{position:fixed;left:8px;right:8px;bottom:290px;max-width:430px;margin-left:auto;max-height:44vh;overflow:auto;background:rgba(12,16,32,.95);color:#fff;border:1px solid #2a3558;border-radius:16px;padding:10px;box-shadow:0 14px 40px rgba(0,0,0,.55)}",
    "#panel[hidden]{display:none}",
    ".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}",
    ".mm{display:flex;flex-direction:column;align-items:center;gap:2px;padding:8px 4px;border-radius:12px;border:2px solid transparent;background:#1b2543;color:#fff;font-size:11px;font-weight:700;text-align:center;min-height:62px;justify-content:center}",
    ".mm b{font-size:24px;font-weight:400;line-height:1}",
    "h4{margin:12px 0 6px;font-size:12px;color:#f4b54a;text-transform:uppercase;letter-spacing:.08em}",
    "input{width:100%;padding:9px 10px;margin-bottom:6px;border-radius:10px;border:1px solid #2a3558;background:#0b1020;color:#fff;font-size:15px}",
    ".fila{display:flex;gap:8px;flex-wrap:wrap}",
    ".fila button{padding:9px 12px;border-radius:10px;border:0;background:#1b2543;color:#fff;font-weight:700;font-size:13px}",
    ".fila button.on{background:#f4b54a;color:#1a1205}",
    ".fila .peg{font-size:22px;padding:6px 10px}"
  ].join("");

  function esc(t) { return String(t).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function btn(k, id, e, n, on) {
    return '<button class="it' + (on ? " on" : "") + '" data-k="' + k + '" data-id="' + id + '"><span class="ic">' + e + "</span><small>" + esc(n) + "</small></button>";
  }
  function barraHtml() {
    var J = window.__chamosJuegos;
    var h = btn("sin", "", "🚫", "Sin efectos", false);
    h += btn("juegos", "", "🎮", "Juegos", !!(J && J.abierto && J.abierto()));
    h += btn("memes", "", "💬", "Memes", memesAbierto);
    var grupo = "";
    CARAS.forEach(function (c) {
      if (!c.grupo) return;
      if (c.grupo !== grupo) { grupo = c.grupo; h += '<i class="sep">' + esc(grupo) + "</i>"; }
      h += btn("cara", c.id, c.e, c.n, estado.cara === c.id);
    });
    h += '<i class="sep">Filtros</i>';
    FILTROS.forEach(function (f) { if (f.id !== "normal") h += btn("filtro", f.id, f.e, f.n, estado.filtro === f.id); });
    return h;
  }
  function memesHtml() {
    var h = '<div class="grid">' + MEMES.map(function (m, i) {
      return '<button class="mm" data-meme="' + i + '"><b>' + (m.p || "💬") + "</b>" + esc(m.n) + "</button>";
    }).join("") + "</div>";
    h += "<h4>Tu propio texto</h4>";
    h += '<input id="ta" maxlength="40" placeholder="Texto de arriba" value="' + esc(estado.arriba) + '"><input id="tb" maxlength="40" placeholder="Texto de abajo" value="' + esc(estado.abajo) + '">';
    h += '<div class="fila"><button id="poner">Poner texto</button><button id="quitar">Quitar memes</button></div>';
    h += "<h4>Pegatinas</h4>";
    h += '<div class="fila">' + PEGATINAS.map(function (p) { return '<button class="peg ' + (estado.pegatina === p ? "on" : "") + '" data-peg="' + p + '">' + p + "</button>"; }).join("") + "</div>";
    h += '<h4>Lluvia</h4><div class="fila"><button id="lluvia" class="' + (estado.lluvia ? "on" : "") + '">' + (estado.lluvia ? "Lluvia de emojis: sí" : "Lluvia de emojis: no") + "</button></div>";
    return h;
  }
  function mensaje() {
    if (errorFx) return "No se pudo aplicar a la cámara: " + esc(errorFx);
    if (estado.cara !== "ninguna") {
      if (lmEstado === "cargando") return "Cargando el detector de caras…";
      if (lmEstado === "fallo") return "Las caras no están disponibles en este teléfono";
      if (lmEstado === "listo" && !ultima.caras.length) return "Mira a la cámara para ver el efecto";
    }
    return "";
  }

  function refrescarUI() {
    if (!raiz) return;
    var viejo = raiz.querySelector(".car"), izq = viejo ? viejo.scrollLeft : 0;
    var ta = raiz.getElementById("ta"), tb = raiz.getElementById("tb");
    var va = ta ? ta.value : null, vb = tb ? tb.value : null;
    var J = window.__chamosJuegos, aviso = !!(J && J.aviso && J.aviso());
    raiz.innerHTML = "<style>" + CSS + '</style><button id="fab" class="' + (aviso ? "av" : "") + '" aria-label="Efectos y juegos">✨</button>' +
      '<div id="barra"' + (abierto ? "" : " hidden") + '><div id="msg">' + mensaje() + '</div><div class="car">' + barraHtml() + "</div></div>" +
      '<div id="panel"' + (memesAbierto && abierto ? "" : " hidden") + ">" + (memesAbierto ? memesHtml() : "") + "</div>";
    var car = raiz.querySelector(".car");
    if (car) car.scrollLeft = izq;
    if (va !== null && raiz.getElementById("ta")) { raiz.getElementById("ta").value = va; raiz.getElementById("tb").value = vb; }
  }

  function clic(e) {
    e.stopPropagation();
    var t = e.target.closest ? e.target.closest("button") : null;
    if (!t) return;
    if (t.id === "fab") { abierto = !abierto; refrescarUI(); return; }
    var k = t.dataset.k, id = t.dataset.id;
    if (k === "sin") { estado.cara = "ninguna"; estado.filtro = "normal"; estado.arriba = ""; estado.abajo = ""; estado.pegatina = ""; estado.lluvia = false; guardar(); }
    else if (k === "cara") { estado.cara = estado.cara === id ? "ninguna" : id; if (estado.cara !== "ninguna") cargarDetector(); guardar(); }
    else if (k === "filtro") { estado.filtro = estado.filtro === id ? "normal" : id; guardar(); }
    else if (k === "memes") {
      memesAbierto = !memesAbierto;
      var J1 = window.__chamosJuegos;
      if (memesAbierto && J1 && J1.abierto && J1.abierto()) J1.alternar();
    }
    else if (k === "juegos") { var J = window.__chamosJuegos; memesAbierto = false; if (J && J.alternar) J.alternar(); }
    else if (t.dataset.meme) { var m = MEMES[+t.dataset.meme]; estado.arriba = m.a; estado.abajo = m.b; if (m.p) estado.pegatina = m.p; }
    else if (t.dataset.peg) { estado.pegatina = estado.pegatina === t.dataset.peg ? "" : t.dataset.peg; if (estado.pegatina) cargarDetector(); }
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
        raiz.addEventListener(n, function (ev) { ev.stopPropagation(); }, { passive: true });
      });
      raiz.addEventListener("click", clic);
      refrescarUI();
      document.documentElement.appendChild(host);
      window.addEventListener("chamos-juegos-estado", refrescarUI);
      if (estado.cara !== "ninguna") cargarDetector();
    };
    if (document.documentElement) poner(); else document.addEventListener("DOMContentLoaded", poner);
  }

  window.__chamosFiltros = {
    version: 2, estado: estado, refrescar: refrescarUI, filtros: FILTROS, caras: CARAS,
    _probar: function (pts) { lmEstado = "listo"; lm = { detectForVideo: function () { return { faceLandmarks: [pts] }; } }; },
    _cuadro: cuadro, _montar: montarUI
  };
})();
