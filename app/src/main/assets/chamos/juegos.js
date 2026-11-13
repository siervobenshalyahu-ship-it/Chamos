/*
 * Chamos: juegos para jugar durante la videollamada (la vieja, ahorcado y parchís de 2 jugadores).
 *
 * Se inyecta en la pantalla de llamada (Element Call, en un WebView). El estado de la partida se guarda en el
 * servidor gratis de Chamos (Cloudflare Worker, ruta /juego/<sala>) y los dos teléfonos lo consultan cada
 * par de segundos. La sala del juego es la misma sala de la llamada.
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------ reglas (puras, se prueban en Node) */
  var L = {};

  L.LINEAS = [[0, 1, 2], [3, 4, 5], [6, 7, 8], [0, 3, 6], [1, 4, 7], [2, 5, 8], [0, 4, 8], [2, 4, 6]];
  // null = sigue; { g: 0|1, lin } = ganó; { g: -1 } = empate
  L.ganadorVieja = function (t) {
    for (var i = 0; i < L.LINEAS.length; i++) {
      var l = L.LINEAS[i];
      if (t[l[0]] !== null && t[l[0]] === t[l[1]] && t[l[0]] === t[l[2]]) return { g: t[l[0]], lin: l };
    }
    for (var j = 0; j < 9; j++) if (t[j] === null) return null;
    return { g: -1, lin: [] };
  };

  L.limpiar = function (s) {
    return String(s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z]/g, "").slice(0, 16);
  };
  L.PALABRAS = ["arepa", "cachapa", "hallaca", "pabellon", "tequeno", "caraota", "plaza", "morrocoy", "guacamaya", "orinoco",
    "maracaibo", "caracas", "pelotero", "beisbol", "cafetal", "joropo", "cuatro", "maracas", "chamo", "bolivar", "turpial",
    "araguaney", "roraima", "merida", "playa", "cerveza", "papelon", "tostones", "empanada", "guayoyo"];
  L.errores = function (palabra, usadas) {
    var n = 0;
    for (var i = 0; i < usadas.length; i++) if (palabra.indexOf(usadas[i]) < 0) n++;
    return n;
  };
  L.completa = function (palabra, usadas) {
    for (var i = 0; i < palabra.length; i++) if (usadas.indexOf(palabra.charAt(i)) < 0) return false;
    return true;
  };
  L.MAX_ERRORES = 6;

  // Parchís simplificado para 2 jugadores. Progreso p de una ficha: -1 = en la cárcel; 0..62 = casillas del anillo
  // (desde su salida); 63..69 = pasillo final; 70 = meta.
  L.START = [4, 38];
  L.SEG = [4, 11, 16, 21, 28, 33, 38, 45, 50, 55, 62, 67];
  L.celda = function (k, p) { return (L.START[k] + p) % 68; };
  L.nuevoParchis = function () { return [[-1, -1, -1, -1], [-1, -1, -1, -1]]; };
  L.legales = function (f, k, d) {
    var r = [];
    for (var i = 0; i < 4; i++) {
      var p = f[k][i];
      if (p === -1) { if (d === 5) r.push({ i: i, a: 0 }); }
      else if (p < 70 && p + d <= 70) r.push({ i: i, a: p + d });
    }
    return r;
  };
  L.mover = function (f, k, i, a) {
    var g = [f[0].slice(), f[1].slice()];
    g[k][i] = a;
    var cap = 0;
    if (a >= 0 && a <= 62) {
      var c = L.celda(k, a), o = 1 - k;
      for (var j = 0; j < 4; j++) {
        var po = g[o][j];
        if (po >= 0 && po <= 62 && L.celda(o, po) === c && (a === 0 || L.SEG.indexOf(c) < 0)) { g[o][j] = -1; cap++; }
      }
    }
    return { f: g, cap: cap };
  };
  L.gano = function (f, k) { return f[k].every(function (p) { return p === 70; }); };

  if (typeof module !== "undefined" && module.exports) { module.exports = L; return; }

  /* ------------------------------------------------------------------ red */
  if (window.__chamosJuegos) return;
  var API = "https://chamos-push.siervodeyhwh57.workers.dev/juego/";

  var yo = "";
  try { yo = localStorage.getItem("chamosJugador") || ""; } catch (e) { /* sin almacenamiento */ }
  if (!yo) {
    yo = "j" + Math.random().toString(36).slice(2, 10);
    try { localStorage.setItem("chamosJugador", yo); } catch (e) { /* nada */ }
  }

  var codigoManual = "";
  function sala() {
    var m = /[?&#]roomId=([^&]+)/.exec(location.href);
    if (m) { try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; } }
    return codigoManual;
  }

  var S = { v: 0, e: null };
  var abierto = false, errRed = "", avisados = {}, hayAviso = false, ultimoAviso = "";
  // Avisa al carrusel de la videollamada (que tiene el botón 🎮) cuando cambia el panel o llega una invitación.
  function avisarCambio() {
    var clave = abierto + "|" + hayAviso;
    if (clave === ultimoAviso) return;
    ultimoAviso = clave;
    try { window.dispatchEvent(new Event("chamos-juegos-estado")); } catch (e) { /* nada */ }
  }

  function copia(o) { return JSON.parse(JSON.stringify(o)); }

  function traer() {
    var s = sala();
    if (!s) return;
    fetch(API + encodeURIComponent(s) + "?t=" + Date.now(), { cache: "no-store" })
      .then(function (r) { if (!r.ok) throw new Error("servidor " + r.status); return r.json(); })
      .then(function (j) {
        errRed = "";
        if (j && j.v > S.v) {
          S = { v: j.v, e: j.estado };
          var e = S.e;
          if (e && e.j && !abierto && !avisados[S.v] && e.jug && e.jug.indexOf(yo) < 0) { avisados[S.v] = 1; abierto = true; }
          pintar();
        } else if (errRedAnterior) { pintar(); }
        errRedAnterior = false;
      })
      .catch(function (e) { errRed = "Sin conexión con el servidor de juegos (" + (e && e.message ? e.message : "red") + ")"; errRedAnterior = true; pintar(); });
  }
  var errRedAnterior = false;

  function cambiar(fn) {
    var s = sala();
    if (!s) return;
    var e = S.e ? copia(S.e) : { j: null };
    fn(e);
    var nv = S.v + 1;
    S = { v: nv, e: e };
    pintar();
    fetch(API + encodeURIComponent(s), { method: "POST", headers: { "content-type": "text/plain" }, body: JSON.stringify({ v: nv, estado: e }) })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j && j.rechazado) { S = { v: j.v, e: j.estado }; pintar(); }
      })
      .catch(function () { errRed = "No se pudo enviar la jugada. Revisa la conexión."; pintar(); });
  }

  /* ------------------------------------------------------------------ jugadas */
  function empezar(juego) {
    cambiar(function (e) {
      for (var k in e) delete e[k];
      e.j = juego; e.jug = [yo, null];
      if (juego === "vieja") { e.tab = [null, null, null, null, null, null, null, null, null]; e.turno = 0; e.gan = null; }
      if (juego === "ahorcado") { e.rol = 0; e.palabra = null; e.usadas = []; }
      if (juego === "parchis") { e.f = L.nuevoParchis(); e.turno = 0; e.dado = null; e.msg = ""; e.gan = null; }
    });
  }
  function unirme() {
    cambiar(function (e) { if (e.jug && e.jug[1] === null && e.jug[0] !== yo) e.jug[1] = yo; });
  }
  function salirJuego() { cambiar(function (e) { for (var k in e) delete e[k]; e.j = null; }); }

  function jugarVieja(i) {
    cambiar(function (e) {
      var k = e.jug.indexOf(yo);
      if (k < 0 || e.gan || e.turno !== k || e.tab[i] !== null || e.jug[1] === null) return;
      e.tab[i] = k;
      var g = L.ganadorVieja(e.tab);
      if (g) e.gan = g; else e.turno = 1 - k;
    });
  }
  function revanchaVieja() {
    cambiar(function (e) { e.tab = [null, null, null, null, null, null, null, null, null]; e.gan = null; e.turno = e.turno === 0 ? 1 : 0; });
  }

  function ponerPalabra(txt) {
    var p = L.limpiar(txt);
    if (p.length < 3) return false;
    cambiar(function (e) { if (e.jug.indexOf(yo) === e.rol) { e.palabra = p; e.usadas = []; } });
    return true;
  }
  function azar() { ponerPalabra(L.PALABRAS[Math.floor(Math.random() * L.PALABRAS.length)]); }
  function letra(c) {
    cambiar(function (e) {
      if (!e.palabra || e.jug.indexOf(yo) !== 1 - e.rol || e.usadas.indexOf(c) >= 0) return;
      if (L.errores(e.palabra, e.usadas) >= L.MAX_ERRORES || L.completa(e.palabra, e.usadas)) return;
      e.usadas.push(c);
    });
  }
  function revanchaAhorcado() { cambiar(function (e) { e.rol = 1 - e.rol; e.palabra = null; e.usadas = []; }); }

  function tirar() {
    cambiar(function (e) {
      var k = e.jug.indexOf(yo);
      if (k < 0 || e.gan || e.turno !== k || e.dado !== null || e.jug[1] === null) return;
      var d = 1 + Math.floor(Math.random() * 6);
      e.dado = d;
      if (!L.legales(e.f, k, d).length) {
        e.msg = (k === 0 ? "Jugador 1" : "Jugador 2") + " sacó " + d + " y no puede mover";
        e.dado = null; e.turno = 1 - k;
      } else e.msg = "";
    });
  }
  function moverFicha(i) {
    cambiar(function (e) {
      var k = e.jug.indexOf(yo);
      if (k < 0 || e.gan || e.turno !== k || e.dado === null) return;
      var ok = L.legales(e.f, k, e.dado).filter(function (m) { return m.i === i; })[0];
      if (!ok) return;
      var r = L.mover(e.f, k, i, ok.a);
      e.f = r.f;
      e.msg = r.cap ? "¡Se comió " + r.cap + " ficha" + (r.cap > 1 ? "s" : "") + "!" : "";
      var d = e.dado;
      e.dado = null;
      if (L.gano(e.f, k)) { e.gan = { g: k }; return; }
      if (d !== 6) e.turno = 1 - k; else e.msg += " Sacó 6: tira otra vez";
    });
  }
  function revanchaParchis() {
    cambiar(function (e) { e.f = L.nuevoParchis(); e.dado = null; e.gan = null; e.msg = ""; e.turno = e.turno === 0 ? 1 : 0; });
  }

  /* ------------------------------------------------------------------ dibujo */
  function esc(t) { return String(t).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  var COL = ["#f4b54a", "#4ac8f4"];

  function svgAhorcado(n) {
    var h = '<svg viewBox="0 0 120 130" width="140" height="150" style="display:block;margin:0 auto">';
    h += '<g stroke="#cfd6e6" stroke-width="4" fill="none" stroke-linecap="round">';
    h += '<path d="M10 122H70M30 122V10H85V24"/>';
    h += '</g><g stroke="#f4b54a" stroke-width="4" fill="none" stroke-linecap="round">';
    if (n > 0) h += '<circle cx="85" cy="34" r="10"/>';
    if (n > 1) h += '<path d="M85 44V80"/>';
    if (n > 2) h += '<path d="M85 52L70 68"/>';
    if (n > 3) h += '<path d="M85 52L100 68"/>';
    if (n > 4) h += '<path d="M85 80L72 102"/>';
    if (n > 5) h += '<path d="M85 80L98 102"/>';
    return h + "</g></svg>";
  }

  function vistaVieja(e, k) {
    var h = "";
    var g = e.gan;
    var sim = ["❌", "⭕"];
    if (e.jug[1] === null) h += '<p class="nota">Esperando al otro jugador…</p>';
    else if (g) h += '<p class="gr">' + (g.g === -1 ? "¡Empate!" : (g.g === k ? "¡Ganaste! 🎉" : "Ganó el otro jugador")) + "</p>";
    else h += '<p class="nota">' + (e.turno === k ? "Te toca a ti " + sim[k] : "Turno del otro jugador " + sim[e.turno]) + "</p>";
    h += '<div class="tab">';
    for (var i = 0; i < 9; i++) {
      var marca = e.tab[i] === null ? "" : sim[e.tab[i]];
      var res = g && g.lin && g.lin.indexOf(i) >= 0 ? " res" : "";
      h += '<button class="cel' + res + '" data-a="v" data-i="' + i + '">' + marca + "</button>";
    }
    h += "</div>";
    if (g) h += '<div class="fila"><button data-a="rv">Revancha</button></div>';
    return h;
  }

  function vistaAhorcado(e, k) {
    var h = "";
    var setter = e.jug.indexOf(yo) === e.rol;
    if (e.jug[1] === null) return '<p class="nota">Esperando al otro jugador…</p>';
    if (!e.palabra) {
      if (setter) {
        h += '<p class="nota">Escribe la palabra secreta (solo letras, de 3 a 16). El otro la adivina.</p>';
        h += '<input id="pal" maxlength="16" autocomplete="off" autocapitalize="none" placeholder="Palabra secreta">';
        h += '<div class="fila"><button data-a="pp">Listo</button><button data-a="pa">Palabra al azar</button></div>';
      } else h += '<p class="nota">El otro jugador está pensando la palabra…</p>';
      return h;
    }
    var err = L.errores(e.palabra, e.usadas), ok = L.completa(e.palabra, e.usadas), perdio = err >= L.MAX_ERRORES;
    h += svgAhorcado(err);
    var pal = "";
    for (var i = 0; i < e.palabra.length; i++) {
      var c = e.palabra.charAt(i);
      pal += '<span class="lt">' + (e.usadas.indexOf(c) >= 0 || perdio ? c.toUpperCase() : "&nbsp;") + "</span>";
    }
    h += '<div class="pal">' + pal + "</div>";
    h += '<p class="nota">Errores: ' + err + " de " + L.MAX_ERRORES + (e.usadas.length ? " · Letras: " + e.usadas.join(" ").toUpperCase() : "") + "</p>";
    if (ok || perdio) {
      var adivina = !setter;
      h += '<p class="gr">' + (ok ? (adivina ? "¡Adivinaste! 🎉" : "El otro adivinó la palabra") : (adivina ? "Perdiste. Era: " + e.palabra.toUpperCase() : "¡No la adivinó! 🎉")) + "</p>";
      h += '<div class="fila"><button data-a="ra">Revancha (cambian de papel)</button></div>';
    } else if (setter) {
      h += '<p class="nota">El otro jugador está adivinando…</p>';
    } else {
      h += '<div class="teclas">' + "abcdefghijklmnopqrstuvwxyz".split("").map(function (c) {
        var usada = e.usadas.indexOf(c) >= 0;
        return '<button data-a="l" data-c="' + c + '"' + (usada ? " disabled" : "") + ">" + c.toUpperCase() + "</button>";
      }).join("") + "</div>";
    }
    return h;
  }

  function vistaParchis(e, k) {
    var cx = 150, cy = 150, R = 125, h = "";
    var pos = function (idx, r) {
      var a = idx / 68 * Math.PI * 2 - Math.PI / 2;
      return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
    };
    var s = '<svg viewBox="0 0 300 300" width="100%" style="max-width:340px;display:block;margin:0 auto">';
    s += '<circle cx="150" cy="150" r="142" fill="#0b1020" stroke="#2a3558"/>';
    for (var i = 0; i < 68; i++) {
      var p = pos(i, R), seg = L.SEG.indexOf(i) >= 0;
      var dueno = i === L.START[0] ? 0 : (i === L.START[1] ? 1 : -1);
      s += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="4.6" fill="' + (dueno >= 0 ? COL[dueno] : (seg ? "#3a4a7a" : "#1b2543")) + '" stroke="#2a3558"/>';
    }
    // pasillos finales y meta
    for (var q = 0; q < 2; q++) {
      var ent = L.celda(q, 62);
      for (var t = 63; t <= 69; t++) {
        var rr = R - (t - 62) * 13, pp = pos(ent, rr);
        s += '<circle cx="' + pp[0].toFixed(1) + '" cy="' + pp[1].toFixed(1) + '" r="4.6" fill="' + COL[q] + '" fill-opacity=".35" stroke="' + COL[q] + '"/>';
      }
    }
    s += '<circle cx="150" cy="150" r="13" fill="#162040" stroke="#f4b54a"/><text x="150" y="154" text-anchor="middle" font-size="11" fill="#f4b54a">META</text>';
    // fichas
    var cuenta = {};
    for (var kk = 0; kk < 2; kk++) {
      for (var f = 0; f < 4; f++) {
        var pr = e.f[kk][f], xy;
        if (pr === -1) {
          var base = pos(L.START[kk], 70);
          xy = [base[0] + (f % 2) * 14 - 7, base[1] + Math.floor(f / 2) * 14 - 7];
        } else if (pr <= 62) {
          var c = L.celda(kk, pr);
          cuenta[c] = (cuenta[c] || 0) + 1;
          xy = pos(c, R - (cuenta[c] - 1) * 7);
        } else if (pr < 70) {
          xy = pos(L.celda(kk, 62), R - (pr - 62) * 13);
        } else {
          xy = [150 + (kk ? 6 : -6) + (f % 2) * 4 - 2, 150 + Math.floor(f / 2) * 4 - 2];
        }
        s += '<circle cx="' + xy[0].toFixed(1) + '" cy="' + xy[1].toFixed(1) + '" r="6" fill="' + COL[kk] + '" stroke="#fff" stroke-width="1.5"/>';
        s += '<text x="' + xy[0].toFixed(1) + '" y="' + (xy[1] + 3).toFixed(1) + '" text-anchor="middle" font-size="8" font-weight="700" fill="#10162b">' + (f + 1) + "</text>";
      }
    }
    s += '<text x="150" y="104" text-anchor="middle" font-size="10" fill="' + COL[0] + '">Amarillo</text><text x="150" y="203" text-anchor="middle" font-size="10" fill="' + COL[1] + '">Celeste</text>';
    s += "</svg>";
    h += s;
    if (e.jug[1] === null) return h + '<p class="nota">Esperando al otro jugador…</p>';
    var nom = ["Amarillo", "Celeste"];
    h += '<p class="nota">Tú eres ' + (k >= 0 ? "<b style='color:" + COL[k] + "'>" + nom[k] + "</b>. " : "espectador. ") +
      "Sale una ficha con un 5. Un 6 da otro turno. Las casillas oscuras son seguras.</p>";
    if (e.msg) h += '<p class="nota"><b>' + esc(e.msg) + "</b></p>";
    if (e.gan) {
      h += '<p class="gr">' + (e.gan.g === k ? "¡Ganaste! 🎉" : "Ganó el otro jugador") + "</p>";
      h += '<div class="fila"><button data-a="rp">Revancha</button></div>';
      return h;
    }
    if (e.turno !== k) return h + '<p class="nota">Turno del otro jugador (' + nom[e.turno] + ")…</p>";
    if (e.dado === null) return h + '<div class="fila"><button class="gr2" data-a="t">🎲 Tirar dado</button></div>';
    h += '<p class="gr">Sacaste un ' + e.dado + ". Elige una ficha:</p><div class=\"fila\">";
    var leg = L.legales(e.f, k, e.dado);
    leg.forEach(function (m) {
      var de = e.f[k][m.i];
      h += '<button data-a="m" data-i="' + m.i + '">Ficha ' + (m.i + 1) + (de === -1 ? " (sale)" : "") + "</button>";
    });
    return h + "</div>";
  }

  var CSS = [
    ":host{all:initial}",
    "*{box-sizing:border-box;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}",
    "#panel{position:fixed;left:8px;right:8px;bottom:290px;max-width:430px;margin-left:auto;max-height:64vh;overflow:auto;background:rgba(12,16,32,.96);color:#fff;border:1px solid #2a3558;border-radius:16px;padding:10px;box-shadow:0 14px 40px rgba(0,0,0,.55)}",
    "#panel[hidden]{display:none}",
    ".cab{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px}",
    ".cab b{font-size:15px;color:#4ac8f4}",
    ".cab button{padding:7px 10px;border-radius:10px;border:0;background:#1b2543;color:#fff;font-weight:700;font-size:13px}",
    ".menu{display:grid;gap:8px}",
    ".menu button{display:flex;align-items:center;gap:12px;padding:12px;border-radius:12px;border:2px solid #2a3558;background:#1b2543;color:#fff;font-size:15px;font-weight:700;text-align:left}",
    ".menu button b{font-size:26px;font-weight:400}",
    ".menu small{display:block;color:#cfd6e6;font-weight:400;font-size:12px}",
    ".nota{color:#cfd6e6;font-size:13px;margin:6px 0}",
    ".gr{color:#f4b54a;font-size:15px;font-weight:800;margin:8px 0;text-align:center}",
    ".err{color:#ff8a80;font-size:12px;margin:6px 0}",
    ".tab{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;max-width:260px;margin:8px auto}",
    ".cel{aspect-ratio:1;border-radius:12px;border:2px solid #2a3558;background:#1b2543;font-size:34px;color:#fff;padding:0}",
    ".cel.res{border-color:#f4b54a;background:#3a3016}",
    ".pal{display:flex;flex-wrap:wrap;gap:5px;justify-content:center;margin:8px 0}",
    ".lt{display:inline-block;min-width:24px;padding:2px 0 3px;border-bottom:3px solid #f4b54a;text-align:center;font-size:22px;font-weight:800}",
    ".teclas{display:grid;grid-template-columns:repeat(7,1fr);gap:5px}",
    ".teclas button{padding:9px 0;border-radius:8px;border:0;background:#1b2543;color:#fff;font-weight:800;font-size:15px}",
    ".teclas button:disabled{opacity:.25}",
    ".fila{display:flex;gap:8px;flex-wrap:wrap;justify-content:center;margin:8px 0}",
    ".fila button{padding:10px 14px;border-radius:10px;border:0;background:#4ac8f4;color:#06222e;font-weight:800;font-size:14px}",
    ".fila .gr2{font-size:18px;padding:12px 22px;background:#f4b54a;color:#1a1205}",
    "input{width:100%;padding:10px;margin:6px 0;border-radius:10px;border:1px solid #2a3558;background:#0b1020;color:#fff;font-size:16px}"
  ].join("");

  var host = null, raiz = null;

  function cuerpo() {
    var e = S.e, s = sala();
    var h = '<div class="cab"><b>🎮 Juegos de la llamada</b><span>';
    if (e && e.j) h += '<button data-a="menu">Cambiar</button> ';
    h += '<button data-a="x">✕</button></span></div>';
    if (!s) {
      return h + '<p class="nota">No pude saber el número de sala de la llamada. Escribe el mismo código en los dos teléfonos (por ejemplo 1234).</p>' +
        '<input id="cod" maxlength="30" placeholder="Código de sala"><div class="fila"><button data-a="cod">Entrar</button></div>';
    }
    if (errRed) h += '<p class="err">' + esc(errRed) + "</p>";
    if (!e || !e.j) {
      h += '<div class="menu">' +
        '<button data-a="e" data-j="vieja"><b>❌</b><span>La vieja<small>Tres en raya, 2 jugadores</small></span></button>' +
        '<button data-a="e" data-j="ahorcado"><b>🔤</b><span>Ahorcado<small>Uno pone la palabra, el otro adivina</small></span></button>' +
        '<button data-a="e" data-j="parchis"><b>🎲</b><span>Parchís<small>Versión rápida para 2 jugadores</small></span></button></div>';
      return h;
    }
    var k = e.jug.indexOf(yo);
    if (k < 0) {
      if (e.jug[1] === null) {
        return h + '<p class="nota">El otro jugador empezó <b>' + ({ vieja: "La vieja", ahorcado: "Ahorcado", parchis: "Parchís" }[e.j]) + "</b>.</p>" +
          '<div class="fila"><button data-a="u">Unirme</button></div>';
      }
      return h + '<p class="nota">Ya hay dos jugadores en esta partida. Si quieres otra, toca «Cambiar» y empieza una nueva.</p>';
    }
    if (e.j === "vieja") return h + vistaVieja(e, k);
    if (e.j === "ahorcado") return h + vistaAhorcado(e, k);
    if (e.j === "parchis") return h + vistaParchis(e, k);
    return h;
  }

  function pintar() {
    if (!raiz) return;
    var panel = raiz.getElementById("panel"), arriba = panel ? panel.scrollTop : 0;
    var pal = raiz.getElementById("pal"), cod = raiz.getElementById("cod");
    var vp = pal ? pal.value : null, vc = cod ? cod.value : null;
    var e = S.e;
    hayAviso = !!(e && e.j && e.jug && e.jug.indexOf(yo) < 0 && !abierto);
    raiz.innerHTML = "<style>" + CSS + '</style><div id="panel"' + (abierto ? "" : " hidden") + ">" + cuerpo() + "</div>";
    avisarCambio();
    if (vp !== null && raiz.getElementById("pal")) raiz.getElementById("pal").value = vp;
    if (vc !== null && raiz.getElementById("cod")) raiz.getElementById("cod").value = vc;
    if (raiz.getElementById("panel")) raiz.getElementById("panel").scrollTop = arriba;
  }

  function clic(ev) {
    ev.stopPropagation();
    var t = ev.target.closest ? ev.target.closest("button") : null;
    if (!t) return;
    var a = t.dataset.a, d = t.dataset;
    if (a === "x") { abierto = false; pintar(); }
    else if (a === "menu") salirJuego();
    else if (a === "e") empezar(d.j);
    else if (a === "u") unirme();
    else if (a === "v") jugarVieja(+d.i);
    else if (a === "rv") revanchaVieja();
    else if (a === "pp") ponerPalabra(raiz.getElementById("pal").value);
    else if (a === "pa") azar();
    else if (a === "l") letra(d.c);
    else if (a === "ra") revanchaAhorcado();
    else if (a === "t") tirar();
    else if (a === "m") moverFicha(+d.i);
    else if (a === "rp") revanchaParchis();
    else if (a === "cod") { codigoManual = String(raiz.getElementById("cod").value || "").trim(); pintar(); traer(); }
  }

  function montar() {
    if (host) return;
    host = document.createElement("div");
    host.style.cssText = "position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646";
    raiz = host.attachShadow({ mode: "open" });
    ["click", "pointerdown", "pointerup", "touchstart", "touchend", "mousedown", "mouseup"].forEach(function (n) {
      raiz.addEventListener(n, function (ev) { ev.stopPropagation(); }, { passive: true });
    });
    raiz.addEventListener("click", clic);
    pintar();
    document.documentElement.appendChild(host);
    setInterval(function () { if (abierto) traer(); }, 1500);
    setInterval(function () { if (!abierto) traer(); }, 4000);
    traer();
  }

  window.__chamosJuegos = {
    version: 2,
    abierto: function () { return abierto; },
    aviso: function () { return hayAviso; },
    alternar: function () { abierto = !abierto; pintar(); if (abierto) traer(); }
  };
  // Los juegos solo existen en videollamadas: se activan cuando la llamada enciende la cámara.
  function arrancar() {
    if (document.documentElement) montar(); else document.addEventListener("DOMContentLoaded", montar);
  }
  if (window.__chamosVideoActivo) arrancar();
  else window.addEventListener("chamos-video", function once() { window.removeEventListener("chamos-video", once); arrancar(); });
})();
