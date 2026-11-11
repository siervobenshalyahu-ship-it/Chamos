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
    { id: "borroso", n: "Suave", e: "☁️", css: "blur(3px) brightness(1.08) saturate(1.2)" },
    { id: "calido", n: "Cálido", e: "🌅", css: "sepia(.35) saturate(1.5) hue-rotate(-12deg) brightness(1.06)" },
    { id: "atardecer", n: "Atardecer", e: "🌇", css: "sepia(.5) saturate(1.9) hue-rotate(-20deg) contrast(1.08) brightness(1.04)" },
    { id: "frioAzul", n: "Azulado", e: "🔵", css: "saturate(1.3) hue-rotate(185deg) brightness(1.05)" },
    { id: "noche", n: "Noche", e: "🌙", css: "brightness(.72) contrast(1.25) hue-rotate(200deg) saturate(1.4)" },
    { id: "dramatico", n: "Dramático", e: "🎭", css: "grayscale(.35) contrast(1.6) brightness(.92) saturate(1.2)" },
    { id: "vintage", n: "Vintage", e: "📷", css: "sepia(.7) contrast(.9) brightness(1.05) saturate(1.3)" },
    { id: "polaroid", n: "Polaroid", e: "🖼️", css: "sepia(.25) contrast(1.15) brightness(1.1) saturate(1.35) hue-rotate(-8deg)" },
    { id: "cine", n: "Cine", e: "🎬", css: "contrast(1.25) saturate(.85) sepia(.2) hue-rotate(-10deg) brightness(.95)" },
    { id: "desvanecido", n: "Desvanecido", e: "🌫️", css: "contrast(.8) brightness(1.15) saturate(.7)" },
    { id: "brillante", n: "Brillante", e: "☀️", css: "brightness(1.25) contrast(1.05) saturate(1.2)" },
    { id: "oscuro", n: "Oscuro", e: "🖤", css: "brightness(.6) contrast(1.3)" },
    { id: "altoContraste", n: "Contraste", e: "⚡", css: "contrast(1.8) saturate(1.2)" },
    { id: "rosa", n: "Rosa", e: "🌷", css: "sepia(.3) saturate(1.8) hue-rotate(-50deg) brightness(1.08)" },
    { id: "verde", n: "Matrix", e: "🟢", css: "grayscale(1) sepia(1) hue-rotate(60deg) saturate(3) brightness(.95) contrast(1.2)" },
    { id: "morado", n: "Morado", e: "🟣", css: "hue-rotate(250deg) saturate(1.8) brightness(1.05)" },
    { id: "rojo", n: "Rojo", e: "🔴", css: "sepia(1) saturate(4) hue-rotate(-50deg) brightness(.95)" },
    { id: "amarillo", n: "Dorado", e: "🟡", css: "sepia(1) saturate(2.4) brightness(1.1) hue-rotate(-8deg)" },
    { id: "duotono", n: "Duotono", e: "🎚️", css: "grayscale(1) sepia(1) hue-rotate(190deg) saturate(3.5) contrast(1.15)" },
    { id: "satura", n: "Colorido", e: "🍭", css: "saturate(2.6) contrast(1.1)" },
    { id: "bnSuave", n: "B/N suave", e: "⚪", css: "grayscale(1) contrast(.9) brightness(1.1)" },
    { id: "pixelGrande", n: "Pixel grande", e: "🟦", pixel: 28 },
    { id: "pixelMini", n: "Pixel fino", e: "🔳", pixel: 7 },
    { id: "espejoNeon", n: "Espejo neón", e: "🔮", espejo: true, css: "saturate(2.4) contrast(1.25) hue-rotate(-30deg)" },
    { id: "borrosoFuerte", n: "Sueño", e: "💭", css: "blur(6px) brightness(1.15) saturate(1.4)" },
    { id: "invertidoBN", n: "Negativo", e: "🔲", css: "invert(1) grayscale(1)" }
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

  /* ------------------------------------------------------------------ extras estilo Snapchat */
  function aqui(c, ancla, dx, dy) {
    var a = c.ang;
    return { x: ancla.x + (Math.cos(a) * dx - Math.sin(a) * dy) * c.ancho, y: ancla.y + (Math.sin(a) * dx + Math.cos(a) * dy) * c.ancho };
  }
  function emo(ctx, c, ch, ancla, dx, dy, tam, voltear) {
    var p = aqui(c, ancla, dx, dy);
    ctx.save();
    ctx.globalCompositeOperation = "source-over"; ctx.globalAlpha = 1; ctx.filter = "none";
    ctx.translate(p.x, p.y); ctx.rotate(c.ang); if (voltear) ctx.scale(-1, 1);
    ctx.font = Math.max(8, Math.round(c.ancho * tam)) + "px sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.fillText(ch, 0, 0);
    ctx.restore();
  }
  function emoGlobal(ctx, ch, x, y, tam, rot) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot || 0);
    ctx.font = Math.round(tam) + "px sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.fillText(ch, 0, 0); ctx.restore();
  }
  function elipse(ctx, x, y, rx, ry, color, rot) {
    ctx.fillStyle = color; ctx.beginPath(); ctx.ellipse(x, y, Math.max(0.5, rx), Math.max(0.5, ry), rot || 0, 0, Math.PI * 2); ctx.fill();
  }
  function orejasRedondas(ctx, c, colE, colI, rad, sep, alto) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      [-1, 1].forEach(function (s) {
        var x = s * c.ancho * sep, y = -c.alto * alto;
        elipse(ctx, x, y, c.ancho * rad, c.ancho * rad, colE);
        elipse(ctx, x, y, c.ancho * rad * 0.55, c.ancho * rad * 0.55, colI);
      });
    });
  }
  function orejasLargas(ctx, c, colE, colI) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.translate(s * c.ancho * 0.2, -c.alto * 0.5); ctx.rotate(s * 0.16);
        elipse(ctx, 0, 0, c.ancho * 0.13, c.alto * 0.44, colE);
        elipse(ctx, 0, c.alto * 0.02, c.ancho * 0.07, c.alto * 0.32, colI);
        ctx.restore();
      });
    });
  }
  function orejasPico(ctx, c, colE, colI, largo) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.scale(s, 1);
        ctx.fillStyle = colE; ctx.beginPath();
        ctx.moveTo(c.ancho * 0.06, c.alto * 0.04); ctx.lineTo(c.ancho * 0.2, -c.alto * largo); ctx.lineTo(c.ancho * 0.46, c.alto * 0.06); ctx.closePath(); ctx.fill();
        ctx.fillStyle = colI; ctx.beginPath();
        ctx.moveTo(c.ancho * 0.14, c.alto * 0.02); ctx.lineTo(c.ancho * 0.22, -c.alto * largo * 0.62); ctx.lineTo(c.ancho * 0.37, c.alto * 0.04); ctx.closePath(); ctx.fill();
        ctx.restore();
      });
    });
  }
  function narizAnimal(ctx, c, color, r) {
    elipse(ctx, c.nariz.x, c.nariz.y, c.ancho * r, c.ancho * r * 0.75, color);
    elipse(ctx, c.nariz.x - c.ancho * r * 0.3, c.nariz.y - c.ancho * r * 0.3, c.ancho * r * 0.25, c.ancho * r * 0.15, "rgba(255,255,255,.55)");
  }
  function cuernos(ctx, c, color) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.scale(s, 1);
        var g = ctx.createLinearGradient(0, 0, c.ancho * 0.25, -c.alto * 0.4);
        g.addColorStop(0, "#7a0d0d"); g.addColorStop(1, color);
        ctx.fillStyle = g; ctx.beginPath();
        ctx.moveTo(c.ancho * 0.1, c.alto * 0.02);
        ctx.quadraticCurveTo(c.ancho * 0.12, -c.alto * 0.25, c.ancho * 0.3, -c.alto * 0.36);
        ctx.quadraticCurveTo(c.ancho * 0.26, -c.alto * 0.16, c.ancho * 0.24, c.alto * 0.04);
        ctx.closePath(); ctx.fill();
        ctx.restore();
      });
    });
  }
  function astas(ctx, c) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      ctx.strokeStyle = "#7a4a22"; ctx.lineWidth = Math.max(3, c.ancho * 0.03); ctx.lineCap = "round";
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.scale(s, 1);
        ctx.beginPath(); ctx.moveTo(c.ancho * 0.1, 0); ctx.quadraticCurveTo(c.ancho * 0.2, -c.alto * 0.25, c.ancho * 0.3, -c.alto * 0.5); ctx.stroke();
        [[0.16, -0.15, 0.3, -0.2], [0.22, -0.3, 0.36, -0.34], [0.27, -0.42, 0.4, -0.5]].forEach(function (r) {
          ctx.beginPath(); ctx.moveTo(c.ancho * r[0], c.alto * r[1]); ctx.lineTo(c.ancho * r[2], c.alto * r[3]); ctx.stroke();
        });
        ctx.restore();
      });
    });
  }
  function aureola(ctx, c, t) {
    sobre(ctx, c.frente.x, c.frente.y - c.alto * 0.04 * Math.sin(t / 400), c.ang, function () {
      ctx.strokeStyle = "#ffd84a"; ctx.lineWidth = Math.max(3, c.ancho * 0.035);
      ctx.shadowColor = "rgba(255,230,120,.9)"; ctx.shadowBlur = c.ancho * 0.08;
      ctx.beginPath(); ctx.ellipse(0, -c.alto * 0.26, c.ancho * 0.26, c.alto * 0.07, 0, 0, Math.PI * 2); ctx.stroke();
    });
  }
  function bigote(ctx, c) {
    sobre(ctx, c.nariz.x, c.nariz.y + c.alto * 0.1, c.ang, function () {
      ctx.fillStyle = "#2a1a10";
      [-1, 1].forEach(function (s) {
        ctx.save(); ctx.scale(s, 1);
        ctx.beginPath(); ctx.moveTo(0, -c.alto * 0.01);
        ctx.bezierCurveTo(c.ancho * 0.08, -c.alto * 0.06, c.ancho * 0.22, -c.alto * 0.03, c.ancho * 0.3, c.alto * 0.04);
        ctx.bezierCurveTo(c.ancho * 0.2, c.alto * 0.0, c.ancho * 0.1, c.alto * 0.07, 0, c.alto * 0.04);
        ctx.closePath(); ctx.fill(); ctx.restore();
      });
    });
  }
  function parche(ctx, c) {
    var p = c.e1.centro;
    ctx.save();
    ctx.strokeStyle = "#111"; ctx.lineWidth = Math.max(2, c.ancho * 0.015);
    var f = aqui(c, c.frente, 0.28, 0.06);
    ctx.beginPath(); ctx.moveTo(c.ore1.x, c.ore1.y); ctx.lineTo(p.x, p.y); ctx.lineTo(f.x, f.y); ctx.stroke();
    ctx.restore();
    elipse(ctx, p.x, p.y, c.ew * 0.95, c.ew * 0.8, "#111", c.ang);
  }
  function antifaz(ctx, c, color) {
    var p1 = M(c, c.e1.centro), p2 = M(c, c.e2.centro), r = c.ew;
    sobre(ctx, (p1.x + p2.x) / 2, (p1.y + p2.y) / 2, c.ang, function () {
      var d = c.entre / 2;
      ctx.fillStyle = color; ctx.beginPath();
      ctx.moveTo(-d - r * 1.25, -r * 0.2);
      ctx.quadraticCurveTo(-d - r * 0.6, -r * 1.2, 0, -r * 0.45);
      ctx.quadraticCurveTo(d + r * 0.6, -r * 1.2, d + r * 1.25, -r * 0.2);
      ctx.quadraticCurveTo(d + r * 0.9, r * 1.2, d, r * 0.9);
      ctx.quadraticCurveTo(0, r * 0.1, -d, r * 0.9);
      ctx.quadraticCurveTo(-d - r * 0.9, r * 1.2, -d - r * 1.25, -r * 0.2);
      ctx.closePath();
      [-1, 1].forEach(function (s) {
        ctx.moveTo(s * d + r * 0.62, 0);
        ctx.ellipse(s * d, 0, r * 0.62, r * 0.4, 0, 0, Math.PI * 2, true);
      });
      ctx.fill("evenodd");
    });
  }
  function calavera(ctx, c) {
    piel(ctx, c, "#f2f2ec", "source-over", 0.9, 2);
    var p1 = M(c, c.e1.centro), p2 = M(c, c.e2.centro);
    elipse(ctx, p1.x, p1.y, c.ew * 0.95, c.ew * 0.85, "#111", c.ang);
    elipse(ctx, p2.x, p2.y, c.ew * 0.95, c.ew * 0.85, "#111", c.ang);
    sobre(ctx, c.nariz.x, c.nariz.y, c.ang, function () {
      ctx.fillStyle = "#111"; ctx.beginPath();
      ctx.moveTo(0, -c.ancho * 0.07); ctx.lineTo(-c.ancho * 0.05, c.ancho * 0.04); ctx.lineTo(c.ancho * 0.05, c.ancho * 0.04); ctx.closePath(); ctx.fill();
    });
    sobre(ctx, c.boca.x, c.boca.y, c.ang, function () {
      var a = dist(c.comA, c.comB) * 0.55, h = c.ancho * 0.07;
      ctx.strokeStyle = "#111"; ctx.lineWidth = Math.max(2, c.ancho * 0.012);
      ctx.strokeRect(-a, -h, a * 2, h * 2);
      for (var i = -3; i <= 3; i++) { ctx.beginPath(); ctx.moveTo(i * a / 3.4, -h); ctx.lineTo(i * a / 3.4, h); ctx.stroke(); }
    });
  }
  function robot(ctx, c, t) {
    piel(ctx, c, "#9fb0c0", "overlay", 0.8, 3);
    piel(ctx, c, "#c6d2de", "multiply", 0.35, 4);
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      ctx.strokeStyle = "#7d8794"; ctx.lineWidth = Math.max(3, c.ancho * 0.02);
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -c.alto * 0.3); ctx.stroke();
      elipse(ctx, 0, -c.alto * 0.32, c.ancho * 0.04, c.ancho * 0.04, (Math.floor(t / 500) % 2) ? "#ff3b3b" : "#7a1b1b");
    });
    [c.ore1, c.ore2].forEach(function (p) { elipse(ctx, p.x, p.y, c.ancho * 0.03, c.ancho * 0.03, "#5d6672"); });
    var p1 = M(c, c.e1.centro), p2 = M(c, c.e2.centro);
    [p1, p2].forEach(function (p) { elipse(ctx, p.x, p.y, c.ew * 0.45, c.ew * 0.45, "rgba(40,255,200,.55)"); });
  }
  function banderaCara(ctx, c) {
    ctx.save();
    trazoOval(ctx, ovalM(c)); ctx.clip();
    ctx.translate(c.centro.x, c.centro.y); ctx.rotate(c.ang);
    var h = c.alto;
    ctx.globalAlpha = 0.6;
    ctx.fillStyle = "#ffcc00"; ctx.fillRect(-c.ancho, -h * 0.75, c.ancho * 2, h * 0.5);
    ctx.fillStyle = "#0033a0"; ctx.fillRect(-c.ancho, -h * 0.25, c.ancho * 2, h * 0.5);
    ctx.fillStyle = "#cf142b"; ctx.fillRect(-c.ancho, h * 0.25, c.ancho * 2, h * 0.5);
    ctx.globalAlpha = 0.95; ctx.fillStyle = "#fff"; ctx.font = Math.round(c.ancho * 0.07) + "px sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    for (var i = 0; i < 8; i++) {
      var a = Math.PI * (1.15 + i * 0.1);
      ctx.fillText("★", Math.cos(a) * c.ancho * 0.28, -h * 0.02 + Math.sin(a) * h * 0.18 + h * 0.12);
    }
    ctx.restore();
  }
  function pecas(ctx, c) {
    ctx.save(); ctx.fillStyle = "rgba(120,62,30,.62)";
    for (var i = 0; i < 26; i++) {
      var lado = i % 2 ? 1 : -1;
      var x = c.nariz.x + lado * (0.04 + rnd(i * 3) * 0.2) * c.ancho, y = c.nariz.y - c.ancho * 0.06 + rnd(i * 3 + 1) * c.ancho * 0.12;
      var q = aqui(c, c.nariz, lado * (0.04 + rnd(i * 3) * 0.2), -0.06 + rnd(i * 3 + 1) * 0.12);
      ctx.beginPath(); ctx.arc(q.x, q.y, Math.max(1, c.ancho * (0.005 + rnd(i * 3 + 2) * 0.005)), 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }
  function labial(ctx, c, color) {
    ctx.save(); ctx.filter = "blur(1px)"; ctx.globalAlpha = 0.72;
    sobre(ctx, c.boca.x, c.boca.y, c.ang, function () {
      var a = dist(c.comA, c.comB) / 2;
      ctx.fillStyle = color; ctx.beginPath();
      ctx.moveTo(-a, 0);
      ctx.quadraticCurveTo(-a * 0.45, -c.ancho * 0.075, 0, -c.ancho * 0.05);
      ctx.quadraticCurveTo(a * 0.45, -c.ancho * 0.075, a, 0);
      ctx.quadraticCurveTo(a * 0.5, c.ancho * 0.085, 0, c.ancho * 0.085);
      ctx.quadraticCurveTo(-a * 0.5, c.ancho * 0.085, -a, 0);
      ctx.fill();
    });
    ctx.restore();
  }
  function sombraOjos(ctx, c, color) {
    ctx.save(); ctx.filter = "blur(3px)"; ctx.globalAlpha = 0.55;
    [c.e1, c.e2].forEach(function (e) {
      var p = M(c, e.centro), q = { x: p.x - Math.sin(c.ang) * c.ew * 0.0, y: p.y };
      sobre(ctx, q.x, q.y, c.ang, function () { elipse(ctx, 0, -c.ew * 0.32, c.ew * 0.78, c.ew * 0.32, color); });
    });
    ctx.restore();
  }
  function lagrimas(ctx, c, t) {
    [c.e1, c.e2].forEach(function (e, i) {
      var p = M(c, e.centro), k = ((t / 900) + i * 0.5) % 1;
      var x = p.x + (i ? 1 : -1) * c.ew * 0.1, y = p.y + c.ew * 0.5 + k * c.alto * 0.4;
      ctx.save(); ctx.globalAlpha = 1 - k * 0.8;
      var g = ctx.createLinearGradient(x, y - c.ew * 0.4, x, y + c.ew * 0.2);
      g.addColorStop(0, "#e6f6ff"); g.addColorStop(1, "#5ab6f0"); ctx.fillStyle = g;
      ctx.beginPath(); ctx.moveTo(x, y - c.ew * 0.45);
      ctx.bezierCurveTo(x + c.ew * 0.3, y - c.ew * 0.05, x + c.ew * 0.25, y + c.ew * 0.25, x, y + c.ew * 0.25);
      ctx.bezierCurveTo(x - c.ew * 0.25, y + c.ew * 0.25, x - c.ew * 0.3, y - c.ew * 0.05, x, y - c.ew * 0.45); ctx.fill();
      ctx.restore();
    });
  }
  function neonCara(ctx, c, color) {
    ctx.save();
    ctx.strokeStyle = color; ctx.lineWidth = Math.max(2, c.ancho * 0.015); ctx.shadowColor = color; ctx.shadowBlur = c.ancho * 0.08; ctx.lineJoin = "round";
    trazoOval(ctx, ovalM(c)); ctx.stroke();
    ctx.restore();
  }
  function gorroNavidad(ctx, c) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      ctx.fillStyle = "#d62828"; ctx.beginPath();
      ctx.moveTo(-c.ancho * 0.42, c.alto * 0.04);
      ctx.quadraticCurveTo(-c.ancho * 0.3, -c.alto * 0.55, c.ancho * 0.28, -c.alto * 0.5);
      ctx.quadraticCurveTo(c.ancho * 0.45, -c.alto * 0.3, c.ancho * 0.42, c.alto * 0.04);
      ctx.closePath(); ctx.fill();
      ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.roundRect(-c.ancho * 0.46, -c.alto * 0.04, c.ancho * 0.92, c.alto * 0.1, c.alto * 0.05); ctx.fill();
      elipse(ctx, c.ancho * 0.3, -c.alto * 0.5, c.ancho * 0.07, c.ancho * 0.07, "#fff");
    });
  }
  function sombreroBruja(ctx, c) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      ctx.fillStyle = "#1c1327";
      ctx.beginPath(); ctx.ellipse(0, c.alto * 0.02, c.ancho * 0.62, c.alto * 0.07, 0, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.moveTo(-c.ancho * 0.32, 0); ctx.quadraticCurveTo(-c.ancho * 0.05, -c.alto * 0.4, c.ancho * 0.18, -c.alto * 0.72);
      ctx.quadraticCurveTo(c.ancho * 0.12, -c.alto * 0.3, c.ancho * 0.32, 0); ctx.closePath(); ctx.fill();
      ctx.fillStyle = "#7b3fc4"; ctx.fillRect(-c.ancho * 0.3, -c.alto * 0.07, c.ancho * 0.6, c.alto * 0.07);
    });
  }
  function cuernoUnicornio(ctx, c) {
    sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
      var g = ctx.createLinearGradient(0, 0, 0, -c.alto * 0.5);
      g.addColorStop(0, "#ff9ad5"); g.addColorStop(0.5, "#ffe27a"); g.addColorStop(1, "#8fd3ff");
      ctx.fillStyle = g; ctx.beginPath();
      ctx.moveTo(-c.ancho * 0.07, c.alto * 0.03); ctx.lineTo(0, -c.alto * 0.5); ctx.lineTo(c.ancho * 0.07, c.alto * 0.03); ctx.closePath(); ctx.fill();
      ctx.strokeStyle = "rgba(255,255,255,.7)"; ctx.lineWidth = Math.max(1.5, c.ancho * 0.008);
      for (var i = 1; i < 5; i++) { ctx.beginPath(); ctx.moveTo(-c.ancho * 0.06 * (1 - i / 5), -c.alto * 0.1 * i); ctx.lineTo(c.ancho * 0.06 * (1 - i / 5), -c.alto * 0.1 * i + c.alto * 0.02); ctx.stroke(); }
    });
  }
  function colmillos(ctx, c) {
    ctx.fillStyle = "#fff"; ctx.strokeStyle = "rgba(0,0,0,.3)"; ctx.lineWidth = 1;
    [c.comA, c.comB].forEach(function (p, i) {
      var s = i ? -1 : 1, q = aqui(c, p, s * 0.03, 0.0);
      ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x + s * c.ancho * 0.045, q.y); ctx.lineTo(q.x + s * c.ancho * 0.022, q.y + c.ancho * 0.075); ctx.closePath(); ctx.fill(); ctx.stroke();
    });
  }
  function flotantes(ctx, w, h, t, lista, n, tam, sube) {
    for (var i = 0; i < n; i++) {
      var v = 0.5 + rnd(i * 7 + 3), k = ((t / (sube ? 5200 : 6500)) * v + rnd(i)) % 1;
      var x = rnd(i * 5 + 1) * w + Math.sin(t / 700 + i) * w * 0.02, y = sube ? h * (1.05 - k * 1.15) : h * (-0.05 + k * 1.15);
      ctx.save(); ctx.globalAlpha = Math.min(1, Math.min(k, 1 - k) * 5);
      emoGlobal(ctx, lista[i % lista.length], x, y, tam * (0.7 + rnd(i * 11) * 0.7), Math.sin(t / 900 + i) * 0.4);
      ctx.restore();
    }
  }

  /* ------------------------------------------------------------------ efectos de cara */
  function ojosGrandes(c, f, r) { return [W(c.e1.centro, c.ew * r, f), W(c.e2.centro, c.ew * r, f)]; }

  var CARAS = [
    { id: "ninguna", n: "Sin cara", e: "🚫" },
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
      dibujar: function (ctx, c) { piel(ctx, c, "#9ad04a", "multiply", 0.55, 4); } },
    /* ---- Animales ---- */
    { id: "perro", n: "Perrito", e: "🐶", grupo: "Animales",
      warps: function (c) { return ojosGrandes(c, 0.18, 1.0); },
      dibujar: function (ctx, c, t) {
        sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
          [-1, 1].forEach(function (s) {
            ctx.save(); ctx.translate(s * c.ancho * 0.42, -c.alto * 0.06); ctx.rotate(s * 0.25);
            elipse(ctx, 0, c.alto * 0.12, c.ancho * 0.11, c.alto * 0.24, "#7b4a26");
            ctx.restore();
          });
        });
        narizAnimal(ctx, c, "#1a1a1a", 0.07);
        var lengua = (Math.sin(t / 220) + 1) / 2;
        elipse(ctx, c.boca.x, c.boca.y + c.ancho * 0.07 + lengua * c.ancho * 0.02, c.ancho * 0.06, c.ancho * (0.07 + lengua * 0.04), "#ff6f91");
      } },
    { id: "conejo", n: "Conejito", e: "🐰", grupo: "Animales",
      warps: function (c) { return ojosGrandes(c, 0.2, 1.0); },
      css: "brightness(1.06)",
      dibujar: function (ctx, c) {
        orejasLargas(ctx, c, "#f4f0f2", "#ffb3cf");
        rubor(ctx, c, 0.9);
        narizAnimal(ctx, c, "#ff8fb4", 0.045);
        ctx.fillStyle = "#fff"; ctx.strokeStyle = "rgba(0,0,0,.25)"; ctx.lineWidth = 1;
        ctx.fillRect(c.boca.x - c.ancho * 0.045, c.boca.y + c.ancho * 0.015, c.ancho * 0.04, c.ancho * 0.085);
        ctx.strokeRect(c.boca.x - c.ancho * 0.045, c.boca.y + c.ancho * 0.015, c.ancho * 0.04, c.ancho * 0.085);
        ctx.fillRect(c.boca.x + c.ancho * 0.005, c.boca.y + c.ancho * 0.015, c.ancho * 0.04, c.ancho * 0.085);
        ctx.strokeRect(c.boca.x + c.ancho * 0.005, c.boca.y + c.ancho * 0.015, c.ancho * 0.04, c.ancho * 0.085);
      } },
    { id: "oso", n: "Osito", e: "🐻", grupo: "Animales",
      dibujar: function (ctx, c) {
        orejasRedondas(ctx, c, "#8a5a33", "#d9a877", 0.1, 0.34, 0.05);
        narizAnimal(ctx, c, "#2b1a10", 0.065);
        rubor(ctx, c, 0.7);
      } },
    { id: "panda", n: "Panda", e: "🐼", grupo: "Animales",
      css: "brightness(1.08)",
      dibujar: function (ctx, c) {
        orejasRedondas(ctx, c, "#1b1b1b", "#333", 0.1, 0.34, 0.05);
        piel(ctx, c, "#fafafa", "source-over", 0.35, 6);
        [c.e1, c.e2].forEach(function (e, i) {
          var p = M(c, e.centro);
          sobre(ctx, p.x, p.y, c.ang + (i ? -0.5 : 0.5), function () { elipse(ctx, 0, 0, c.ew * 0.95, c.ew * 1.25, "rgba(20,20,20,.88)"); });
        });
        narizAnimal(ctx, c, "#111", 0.06);
      } },
    { id: "zorro", n: "Zorrito", e: "🦊", grupo: "Animales",
      dibujar: function (ctx, c) {
        orejasPico(ctx, c, "#e8721c", "#fff2e0", 0.62);
        piel(ctx, c, "#f08a2e", "multiply", 0.35, 5);
        narizAnimal(ctx, c, "#111", 0.05);
        ctx.fillStyle = "rgba(255,255,255,.92)";
        [c.mej1, c.mej2].forEach(function (p, i) {
          var s = i ? 1 : -1, q = aqui(c, p, s * 0.02, 0.0);
          ctx.beginPath(); ctx.moveTo(q.x - s * c.ancho * 0.1, q.y - c.ancho * 0.04); ctx.lineTo(q.x + s * c.ancho * 0.04, q.y + c.ancho * 0.02); ctx.lineTo(q.x - s * c.ancho * 0.08, q.y + c.ancho * 0.1); ctx.closePath(); ctx.fill();
        });
      } },
    { id: "reno", n: "Reno", e: "🦌", grupo: "Animales",
      dibujar: function (ctx, c) {
        astas(ctx, c);
        elipse(ctx, c.nariz.x, c.nariz.y, c.ancho * 0.065, c.ancho * 0.065, "#ff2a2a");
        elipse(ctx, c.nariz.x - c.ancho * 0.02, c.nariz.y - c.ancho * 0.02, c.ancho * 0.02, c.ancho * 0.015, "rgba(255,255,255,.7)");
      } },
    { id: "mariposa", n: "Mariposas", e: "🦋", grupo: "Animales",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["🦋", "🦋", "🌸"], 9, h * 0.07, true); },
      dibujar: function (ctx, c, t) { emo(ctx, c, "🦋", c.frente, 0.28 + Math.sin(t / 500) * 0.02, -0.16, 0.3); } },
    { id: "pajaritos", n: "Pajaritos", e: "🐤", grupo: "Animales",
      dibujar: function (ctx, c, t) {
        for (var i = 0; i < 4; i++) {
          var a = t / 600 + i * Math.PI / 2;
          emo(ctx, c, "🐤", c.frente, Math.cos(a) * 0.36, -0.22 + Math.sin(a) * 0.05, 0.14 + 0.04 * Math.sin(a));
        }
      } },

    /* ---- Accesorios ---- */
    { id: "corona", n: "Corona", e: "👑", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "👑", c.frente, 0, -0.14, 0.55); } },
    { id: "gorra", n: "Gorra al revés", e: "🧢", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🧢", c.frente, 0.02, -0.12, 0.66, true); } },
    { id: "sombrero", n: "Sombrero", e: "🎩", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🎩", c.frente, 0, -0.16, 0.62); } },
    { id: "paja", n: "Sombrero de paja", e: "👒", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "👒", c.frente, 0, -0.1, 0.9); } },
    { id: "graduado", n: "Graduado", e: "🎓", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🎓", c.frente, 0, -0.12, 0.7); } },
    { id: "casco", n: "Casco", e: "🪖", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🪖", c.frente, 0, -0.12, 0.72); } },
    { id: "lazo", n: "Lazo", e: "🎀", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🎀", c.frente, 0.26, -0.1, 0.34); } },
    { id: "flores", n: "Corona de flores", e: "🌸", grupo: "Accesorios",
      dibujar: function (ctx, c) {
        var fl = ["🌸", "🌼", "🌺", "🌼", "🌸"];
        for (var i = 0; i < 5; i++) emo(ctx, c, fl[i], c.frente, (i - 2) * 0.17, -0.06 - (i === 2 ? 0.04 : 0), 0.2);
      } },
    { id: "gafasSol", n: "Lentes de sol", e: "🕶️", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🕶️", M(c, c.ojos), 0, 0.01, c.entre / c.ancho * 2.3); } },
    { id: "gafas", n: "Lentes", e: "👓", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "👓", M(c, c.ojos), 0, 0.01, c.entre / c.ancho * 2.3); } },
    { id: "goggles", n: "Gafas de buceo", e: "🥽", grupo: "Accesorios",
      dibujar: function (ctx, c) { emo(ctx, c, "🥽", M(c, c.ojos), 0, 0.01, c.entre / c.ancho * 2.4); } },
    { id: "bigote", n: "Bigote", e: "👨", grupo: "Accesorios",
      dibujar: function (ctx, c) { bigote(ctx, c); } },
    { id: "pirata", n: "Pirata", e: "🏴‍☠️", grupo: "Accesorios",
      dibujar: function (ctx, c) { parche(ctx, c); bigote(ctx, c); } },
    { id: "heroe", n: "Antifaz de héroe", e: "🦸", grupo: "Accesorios",
      dibujar: function (ctx, c) { antifaz(ctx, c, "#1a1a2e"); } },
    { id: "navidad", n: "Gorro de Navidad", e: "🎅", grupo: "Accesorios",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["❄️", "❄️", "✨"], 14, h * 0.05, false); },
      dibujar: function (ctx, c) { gorroNavidad(ctx, c); } },
    { id: "bruja", n: "Sombrero de bruja", e: "🧙", grupo: "Accesorios",
      dibujar: function (ctx, c) { sombreroBruja(ctx, c); } },

    /* ---- Fantasía ---- */
    { id: "diablo", n: "Diablito", e: "😈", grupo: "Fantasía",
      css: "saturate(1.2)",
      dibujar: function (ctx, c) { piel(ctx, c, "#d62828", "multiply", 0.55, 4); cuernos(ctx, c, "#ff3b3b"); colmillos(ctx, c); } },
    { id: "angel", n: "Ángel", e: "😇", grupo: "Fantasía",
      css: "brightness(1.1) saturate(.95)",
      global: function (ctx, w, h) { vineta(ctx, w, h, "rgba(255,245,200,.35)"); },
      dibujar: function (ctx, c, t) { aureola(ctx, c, t); rubor(ctx, c, 0.7); } },
    { id: "vampiro", n: "Vampiro", e: "🧛", grupo: "Fantasía",
      css: "contrast(1.15) saturate(.8)",
      dibujar: function (ctx, c) { piel(ctx, c, "#e9e4ee", "screen", 0.4, 4); colmillos(ctx, c); [c.e1, c.e2].forEach(function (e) { var p = M(c, e.centro); elipse(ctx, p.x, p.y, c.ew * 0.22, c.ew * 0.22, "rgba(220,20,40,.8)"); }); } },
    { id: "zombie", n: "Zombi", e: "🧟", grupo: "Fantasía",
      css: "contrast(1.15) saturate(.7)",
      dibujar: function (ctx, c) {
        piel(ctx, c, "#7f9a6a", "multiply", 0.75, 3);
        ctx.strokeStyle = "rgba(60,20,20,.75)"; ctx.lineWidth = Math.max(2, c.ancho * 0.012);
        var a = aqui(c, c.mej1, 0.0, -0.1), b = aqui(c, c.mej1, 0.1, 0.0);
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        for (var i = 0; i < 5; i++) { var p = { x: a.x + (b.x - a.x) * i / 4, y: a.y + (b.y - a.y) * i / 4 }; ctx.beginPath(); ctx.moveTo(p.x - 4, p.y - 6); ctx.lineTo(p.x + 4, p.y + 6); ctx.stroke(); }
        [c.e1, c.e2].forEach(function (e) { var p = M(c, e.centro); sobre(ctx, p.x, p.y + c.ew * 0.45, c.ang, function () { elipse(ctx, 0, 0, c.ew * 0.7, c.ew * 0.25, "rgba(60,30,60,.45)"); }); });
      } },
    { id: "calavera", n: "Calavera", e: "💀", grupo: "Fantasía",
      dibujar: function (ctx, c) { calavera(ctx, c); } },
    { id: "robot", n: "Robot", e: "🤖", grupo: "Fantasía",
      dibujar: function (ctx, c, t) { robot(ctx, c, t); } },
    { id: "alien", n: "Alien", e: "👽", grupo: "Fantasía",
      warps: function (c) { return ojosGrandes(c, 0.95, 1.25).concat([W(c.barbilla, c.ancho * 0.7, -0.25), W(c.nariz, c.ancho * 0.3, -0.35)]); },
      dibujar: function (ctx, c) {
        piel(ctx, c, "#7fd66a", "multiply", 0.85, 3);
        piel(ctx, c, "#b6ff9a", "overlay", 0.4, 5);
        sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
          ctx.strokeStyle = "#5fb04a"; ctx.lineWidth = Math.max(3, c.ancho * 0.02); ctx.lineCap = "round";
          [-1, 1].forEach(function (s) { ctx.beginPath(); ctx.moveTo(s * c.ancho * 0.1, 0); ctx.quadraticCurveTo(s * c.ancho * 0.18, -c.alto * 0.22, s * c.ancho * 0.26, -c.alto * 0.3); ctx.stroke(); elipse(ctx, s * c.ancho * 0.26, -c.alto * 0.31, c.ancho * 0.035, c.ancho * 0.035, "#c9ff7a"); });
        });
        [c.e1, c.e2].forEach(function (e) { var p = M(c, e.centro); sobre(ctx, p.x, p.y, c.ang, function () { elipse(ctx, 0, 0, c.ew * 0.7, c.ew * 0.95, "#0b0b12"); elipse(ctx, -c.ew * 0.2, -c.ew * 0.3, c.ew * 0.15, c.ew * 0.22, "rgba(255,255,255,.7)"); }); });
      } },
    { id: "unicornio", n: "Unicornio", e: "🦄", grupo: "Fantasía",
      css: "saturate(1.2) brightness(1.06)",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["✨", "🌈", "⭐", "💖"], 10, h * 0.06, true); },
      dibujar: function (ctx, c) { orejasPico(ctx, c, "#ffe3f1", "#ffb3d9", 0.34); cuernoUnicornio(ctx, c); rubor(ctx, c, 1.1); } },
    { id: "elfo", n: "Elfo", e: "🧝", grupo: "Fantasía",
      dibujar: function (ctx, c) {
        [[c.ore1, -1], [c.ore2, 1]].forEach(function (par) {
          var p = par[0], s = par[1];
          ctx.fillStyle = "#f0c9a8"; ctx.strokeStyle = "rgba(120,70,40,.5)"; ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.moveTo(p.x, p.y + c.alto * 0.08); ctx.lineTo(p.x + s * c.ancho * 0.28, p.y - c.alto * 0.2); ctx.lineTo(p.x, p.y - c.alto * 0.06); ctx.closePath(); ctx.fill(); ctx.stroke();
        });
        piel(ctx, c, "#fff4e0", "screen", 0.2, 5);
      } },
    { id: "payaso", n: "Payaso", e: "🤡", grupo: "Fantasía",
      dibujar: function (ctx, c) {
        sobre(ctx, c.frente.x, c.frente.y, c.ang, function () {
          [-1, 1].forEach(function (s) { elipse(ctx, s * c.ancho * 0.34, -c.alto * 0.05, c.ancho * 0.16, c.ancho * 0.16, "#ff6a00"); elipse(ctx, s * c.ancho * 0.3, -c.alto * 0.22, c.ancho * 0.1, c.ancho * 0.1, "#ff6a00"); });
        });
        piel(ctx, c, "#fff", "screen", 0.45, 3);
        [c.mej1, c.mej2].forEach(function (p) { elipse(ctx, p.x, p.y, c.ancho * 0.05, c.ancho * 0.05, "rgba(255,40,80,.5)"); });
        labial(ctx, c, "#e8142a");
        elipse(ctx, c.nariz.x, c.nariz.y, c.ancho * 0.075, c.ancho * 0.075, "#e8142a");
        elipse(ctx, c.nariz.x - c.ancho * 0.02, c.nariz.y - c.ancho * 0.02, c.ancho * 0.02, c.ancho * 0.015, "rgba(255,255,255,.7)");
      } },
    { id: "bandera", n: "Venezuela", e: "🇻🇪", grupo: "Fantasía",
      dibujar: function (ctx, c) { banderaCara(ctx, c); emo(ctx, c, "🇻🇪", c.frente, 0.3, -0.12, 0.2); } },

    /* ---- Maquillaje ---- */
    { id: "pecas", n: "Pecas", e: "🟤", grupo: "Maquillaje",
      dibujar: function (ctx, c) { pecas(ctx, c); rubor(ctx, c, 0.5); } },
    { id: "labialRojo", n: "Labial rojo", e: "💄", grupo: "Maquillaje",
      dibujar: function (ctx, c) { labial(ctx, c, "#d1122b"); } },
    { id: "labialRosa", n: "Labial rosa", e: "💋", grupo: "Maquillaje",
      dibujar: function (ctx, c) { labial(ctx, c, "#ff5fa2"); rubor(ctx, c, 0.9); } },
    { id: "sombraAzul", n: "Sombra azul", e: "💙", grupo: "Maquillaje",
      dibujar: function (ctx, c) { sombraOjos(ctx, c, "#2f6bff"); } },
    { id: "sombraMorada", n: "Sombra morada", e: "💜", grupo: "Maquillaje",
      dibujar: function (ctx, c) { sombraOjos(ctx, c, "#9b3fd6"); labial(ctx, c, "#a02a8a"); } },
    { id: "glitter", n: "Brillitos", e: "✨", grupo: "Maquillaje",
      dibujar: function (ctx, c, t) { destellos(ctx, c, t, 14, "rgba(255,240,170,.95)"); sombraOjos(ctx, c, "#ffd84a"); } },
    { id: "lagrimas", n: "Llorón", e: "😭", grupo: "Maquillaje",
      dibujar: function (ctx, c, t) { lagrimas(ctx, c, t); rubor(ctx, c, 0.8); } },
    { id: "neonCian", n: "Contorno neón", e: "🔷", grupo: "Maquillaje",
      dibujar: function (ctx, c) { neonCara(ctx, c, "#22f0ff"); } },
    { id: "neonRosa", n: "Contorno rosa", e: "🩷", grupo: "Maquillaje",
      dibujar: function (ctx, c) { neonCara(ctx, c, "#ff3cac"); } },

    /* ---- Cariñosas ---- */
    { id: "ojosCorazon", n: "Ojos de corazón", e: "😍", grupo: "Cariñosas",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["💗", "💖", "❤️"], 12, h * 0.06, true); },
      dibujar: function (ctx, c, t) { var k = 1 + 0.12 * Math.sin(t / 180); emo(ctx, c, "❤️", M(c, c.e1.centro), 0, 0, c.ew / c.ancho * 1.9 * k); emo(ctx, c, "❤️", M(c, c.e2.centro), 0, 0, c.ew / c.ancho * 1.9 * k); } },
    { id: "ojosEstrella", n: "Ojos de estrella", e: "🤩", grupo: "Cariñosas",
      dibujar: function (ctx, c, t) { emo(ctx, c, "⭐", M(c, c.e1.centro), 0, 0, c.ew / c.ancho * 1.9); emo(ctx, c, "⭐", M(c, c.e2.centro), 0, 0, c.ew / c.ancho * 1.9); destellos(ctx, c, t, 6, "rgba(255,240,160,.95)"); } },
    { id: "enamorado", n: "Enamorado", e: "🥰", grupo: "Cariñosas",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["💕", "💗", "💘"], 10, h * 0.055, true); },
      dibujar: function (ctx, c, t) { rubor(ctx, c, 1.2); for (var i = 0; i < 3; i++) { var a = t / 700 + i * 2.1; emo(ctx, c, "💕", c.frente, Math.cos(a) * 0.4, -0.2 + Math.sin(a) * 0.06, 0.12); } } },
    { id: "lluviaEstrellas", n: "Estrellitas", e: "🌟", grupo: "Cariñosas",
      global: function (ctx, w, h, t) { flotantes(ctx, w, h, t, ["⭐", "🌟", "✨"], 16, h * 0.05, false); },
      dibujar: function (ctx, c, t) { destellos(ctx, c, t, 8, "rgba(255,250,200,.95)"); } },

    /* ---- Más deformes ---- */
    { id: "chiquito", n: "Ojitos", e: "🥺", grupo: "Más deformes",
      warps: function (c) { return ojosGrandes(c, -0.55, 1.0); } },
    { id: "cachetes", n: "Cachetón", e: "😚", grupo: "Más deformes",
      warps: function (c) { return [W(c.mej1, c.ancho * 0.32, 0.6), W(c.mej2, c.ancho * 0.32, 0.6)]; } },
    { id: "bocona", n: "Bocón", e: "😮", grupo: "Más deformes",
      warps: function (c) { return [W(c.boca, c.ancho * 0.5, 0.85)]; } },
    { id: "frentona", n: "Frentón", e: "🧠", grupo: "Más deformes",
      warps: function (c) { var f = { x: (c.frente.x + c.ojos.x) / 2, y: (c.frente.y + c.ojos.y) / 2 }; return [W(f, c.alto * 0.55, 0.8)]; } },
    { id: "barbilla", n: "Barbilludo", e: "🦷", grupo: "Más deformes",
      warps: function (c) { return [W(c.barbilla, c.ancho * 0.55, 0.85)]; } },
    { id: "cabecita", n: "Cabecita", e: "🔬", grupo: "Más deformes",
      warps: function (c) { return [W(c.centro, c.alto * 0.8, -0.55)]; } },
    { id: "gemelos", n: "Ojos distintos", e: "🤪", grupo: "Más deformes",
      warps: function (c) { return [W(c.e1.centro, c.ew * 1.5, 0.85), W(c.e2.centro, c.ew * 1.2, -0.45)]; } },
    { id: "alargada", n: "Cara larga", e: "🐴", grupo: "Más deformes",
      warps: function (c) { return [W(c.centro, c.alto * 0.9, -0.35), W(c.nariz, c.ancho * 0.45, 0.45), W(c.barbilla, c.ancho * 0.5, 0.5)]; } }
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
