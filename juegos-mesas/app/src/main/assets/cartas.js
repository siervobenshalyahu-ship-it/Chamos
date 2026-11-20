/* Baraja española de 40 cartas dibujada en SVG, sin imágenes externas.
   Uso: Cartas.svg(rango, palo) -> string SVG. Rangos: 1-7, 10 (sota), 11 (caballo), 12 (rey).
   Palos: "oros", "copas", "espadas", "bastos". */
(function () {
  "use strict";
  var LINE = "#2b2118", SKIN = "#f4cfa6", SKIN2 = "#e2b283";
  var INK = { oros: "#7a560a", copas: "#7a1d17", espadas: "#1b3a75", bastos: "#1a5733" };
  var PAL = {
    oros:    { main: "#e3a824", alt: "#c23a22", trim: "#fbe6a0", dark: "#8a5f0b" },
    copas:   { main: "#d0382e", alt: "#2f63b8", trim: "#f6e2b0", dark: "#7a1d17" },
    espadas: { main: "#2f63b8", alt: "#e0ac2c", trim: "#e3eeff", dark: "#1b3a75" },
    bastos:  { main: "#2f9a5e", alt: "#c8372d", trim: "#ecf6d6", dark: "#1a5733" }
  };
  var NOMBRE = { 1: "As", 10: "Sota", 11: "Caballo", 12: "Rey" };

  /* Degradados compartidos: se definen una sola vez en la página. */
  var DEFS = '<defs>' +
    '<radialGradient id="cgOro" cx=".38" cy=".32" r=".8"><stop offset="0" stop-color="#fff3a8"/><stop offset=".55" stop-color="#f0bb2e"/><stop offset="1" stop-color="#b97b0c"/></radialGradient>' +
    '<linearGradient id="cgGold" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe88f"/><stop offset="1" stop-color="#c98f14"/></linearGradient>' +
    '<linearGradient id="cgCopa" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd35e"/><stop offset="1" stop-color="#e28a1c"/></linearGradient>' +
    '<linearGradient id="cgBlade" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#4a7fd4"/><stop offset=".45" stop-color="#eaf4ff"/><stop offset="1" stop-color="#3f72c8"/></linearGradient>' +
    '<linearGradient id="cgClub" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8f531f"/><stop offset=".5" stop-color="#d8a060"/><stop offset="1" stop-color="#7d4217"/></linearGradient>' +
    '<linearGradient id="cgGreen" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#69cf8f"/><stop offset="1" stop-color="#2b8a52"/></linearGradient>' +
    '</defs>';
  function injectDefs() {
    if (typeof document === "undefined" || document.getElementById("cartas-defs")) return;
    var host = document.body || document.documentElement;
    var d = document.createElement("div");
    d.id = "cartas-defs";
    d.setAttribute("aria-hidden", "true");
    d.style.cssText = "position:absolute;width:0;height:0;overflow:hidden";
    d.innerHTML = '<svg width="0" height="0" xmlns="http://www.w3.org/2000/svg">' + DEFS + "</svg>";
    host.insertBefore(d, host.firstChild);
  }
  injectDefs();
  if (typeof document !== "undefined" && !document.body) document.addEventListener("DOMContentLoaded", injectDefs);

  /* ---------- Símbolos cortos (oros y copas), en una caja de 24 x 24 ---------- */
  function coin() {
    return '<circle cx="12" cy="12" r="10.8" fill="url(#cgOro)" stroke="#7a560a" stroke-width="1.2"/>' +
      '<circle cx="12" cy="12" r="9.2" fill="none" stroke="#8a6310" stroke-width=".9" stroke-dasharray="1.1 1.1"/>' +
      '<circle cx="12" cy="12" r="7" fill="none" stroke="#8a6310" stroke-width=".8"/>' +
      '<circle cx="12" cy="9.6" r="2.5" fill="#b27a12"/>' +
      '<path d="M7.2 17.6Q12 10.8 16.8 17.6Z" fill="#b27a12"/>' +
      '<path d="M7.2 17.6h9.6" stroke="#8a6310" stroke-width=".8"/>';
  }
  function cup() {
    return '<path d="M3.4 4.6Q-.6 5.4 2.6 9.8M20.6 4.6Q24.6 5.4 21.4 9.8" fill="none" stroke="#c98f14" stroke-width="1.5" stroke-linecap="round"/>' +
      '<path d="M3.6 3H20.4C20.4 9.4 17.4 14 12 14S3.6 9.4 3.6 3Z" fill="url(#cgCopa)" stroke="#7a3a0a" stroke-width="1.2" stroke-linejoin="round"/>' +
      '<path d="M4 5.6Q12 8.4 20 5.6" fill="none" stroke="#2f6fd0" stroke-width="1.7"/>' +
      '<path d="M4.8 8.8Q12 11.6 19.2 8.8" fill="none" stroke="#c8372d" stroke-width="1.5"/>' +
      '<path d="M12 14v5" stroke="url(#cgGold)" stroke-width="2.4" stroke-linecap="round"/>' +
      '<circle cx="12" cy="16.4" r="1.9" fill="url(#cgGold)" stroke="#7a560a" stroke-width=".7"/>' +
      '<path d="M6 22.4Q12 18.8 18 22.4Z" fill="url(#cgGold)" stroke="#7a560a" stroke-width=".8" stroke-linejoin="round"/>';
  }
  function put(draw, x, y, k, rot) {
    return '<g transform="translate(' + x + " " + y + ") rotate(" + (rot || 0) + ") scale(" + k + ") translate(-12 -12)\">" + draw() + "</g>";
  }

  /* ---------- Piezas largas (espadas y bastos), en una caja de 20 x 56 ---------- */
  function sword() {
    return '<path d="M10 1L14.2 9V37H5.8V9Z" fill="url(#cgBlade)" stroke="#1b3a75" stroke-width=".9" stroke-linejoin="round"/>' +
      '<path d="M10 4V36" stroke="#fff" stroke-opacity=".75" stroke-width=".9"/>' +
      '<path d="M.8 38.6Q10 33.4 19.2 38.6L17.6 42.2Q10 38.6 2.4 42.2Z" fill="url(#cgGold)" stroke="#7a560a" stroke-width=".9" stroke-linejoin="round"/>' +
      '<rect x="8" y="41.4" width="4" height="10" rx="1.2" fill="#7b3f1d" stroke="#3b1d0c" stroke-width=".7"/>' +
      '<path d="M8 44.4h4M8 47.4h4" stroke="#e8b84a" stroke-width=".8"/>' +
      '<circle cx="10" cy="53.4" r="2.6" fill="url(#cgGold)" stroke="#7a560a" stroke-width=".8"/>';
  }
  function club() {
    return '<path d="M4 7Q10 -1.4 16 7Q19.4 17 15.6 29Q15.8 40 13.6 51Q10 55.6 6.4 51Q4.2 40 4.4 29Q.6 17 4 7Z" fill="url(#cgClub)" stroke="#5a2c0c" stroke-width="1" stroke-linejoin="round"/>' +
      '<path d="M7.4 9Q5.6 22 7.2 44" stroke="#ffe0a8" stroke-opacity=".55" stroke-width="1.2" fill="none" stroke-linecap="round"/>' +
      '<path d="M5 20Q10 22.6 15 20M4.8 34Q10 36.6 15.4 34" stroke="#5a2c0c" stroke-opacity=".55" stroke-width=".9" fill="none"/>' +
      '<circle cx="3.4" cy="14" r="2.1" fill="#f3c27a" stroke="#5a2c0c" stroke-width=".8"/>' +
      '<circle cx="16.8" cy="40" r="2.1" fill="#f3c27a" stroke="#5a2c0c" stroke-width=".8"/>' +
      '<path d="M16.4 14Q23 11 21.6 4.6Q14.8 6 15.4 13.4Z" fill="url(#cgGreen)" stroke="#1a5733" stroke-width=".7" stroke-linejoin="round"/>' +
      '<path d="M4 31Q-2.4 28.6 -.6 22Q5.8 23.4 5.4 30.6Z" fill="url(#cgGreen)" stroke="#1a5733" stroke-width=".7" stroke-linejoin="round"/>' +
      '<path d="M10 4Q6.6 -1.6 3.4 .4Q6.6 5.2 10 4Z" fill="url(#cgGreen)" stroke="#1a5733" stroke-width=".7" stroke-linejoin="round"/>';
  }
  function putLong(draw, x, y, k, rot, piv) {
    return '<g transform="translate(' + x + " " + y + ") rotate(" + (rot || 0) + ") scale(" + k + ") translate(-10 -" + (piv === undefined ? 28 : piv) + ')">' + draw() + "</g>";
  }

  /* ---------- Distribución de los símbolos por número ---------- */
  var L = 33, R = 67, M = 50;
  var POS = {
    2: [[M, 40], [M, 116]],
    3: [[M, 34], [M, 78], [M, 122]],
    4: [[L, 40], [R, 40], [L, 116], [R, 116]],
    5: [[L, 40], [R, 40], [M, 78], [L, 116], [R, 116]],
    6: [[L, 36], [R, 36], [L, 78], [R, 78], [L, 120], [R, 120]],
    7: [[L, 36], [R, 36], [M, 57], [L, 78], [R, 78], [L, 120], [R, 120]]
  };
  /* Espadas y bastos: 2 y 3 van cruzadas o en abanico; del 4 al 7, de pie. */
  var LONG = {
    4: { k: 1.0, p: [[L, 46], [R, 46], [L, 112], [R, 112]] },
    5: { k: 0.75, p: [[L, 34], [R, 34], [M, 78], [L, 122], [R, 122]] },
    6: { k: 0.75, p: [[L, 34], [R, 34], [L, 78], [R, 78], [L, 122], [R, 122]] },
    7: { k: 0.75, p: [[L, 34], [R, 34], [M, 56], [L, 78], [R, 78], [L, 122], [R, 122]] }
  };

  function pips(r, s) {
    var o = "", draw;
    if (s === "oros" || s === "copas") {
      draw = s === "oros" ? coin : cup;
      var k = s === "oros" ? 1.3 : 1.25;
      if (r === 7) k -= 0.14;
      POS[r].forEach(function (p) { o += put(draw, p[0], p[1], k); });
      return o;
    }
    draw = s === "espadas" ? sword : club;
    if (r === 2) return putLong(draw, 50, 78, 1.9, -32) + putLong(draw, 50, 78, 1.9, 32);
    if (r === 3) return putLong(draw, 50, 112, 1.5, -24, 44) + putLong(draw, 50, 112, 1.5, 24, 44) + putLong(draw, 50, 112, 1.5, 0, 44);
    var c = LONG[r];
    c.p.forEach(function (p) { o += putLong(draw, p[0], p[1], c.k, 0); });
    return o;
  }

  /* ---------- Ases ---------- */
  function as(s) {
    var c = PAL[s], o = "";
    if (s === "oros") {
      o += '<circle cx="50" cy="78" r="38" fill="url(#cgOro)" stroke="#7a560a" stroke-width="1.6"/>' +
        '<circle cx="50" cy="78" r="33" fill="none" stroke="#8a6310" stroke-width="1.4" stroke-dasharray="2.4 2.4"/>' +
        '<circle cx="50" cy="78" r="25" fill="none" stroke="#8a6310" stroke-width="1.2"/>' +
        '<circle cx="50" cy="68" r="9" fill="#b27a12"/><path d="M30 106Q50 70 70 106Z" fill="#b27a12"/>' +
        '<path d="M30 106h40" stroke="#8a6310" stroke-width="1.2"/>';
    } else if (s === "copas") {
      o += put(cup, 50, 72, 3.6);
    } else {
      var d = s === "espadas" ? sword : club;
      o += '<path d="M24 118Q16 78 50 36M76 118Q84 78 50 36" fill="none" stroke="#3ea76a" stroke-width="3.6" stroke-linecap="round" opacity=".85"/>' +
        '<path d="M24 118Q16 78 50 36M76 118Q84 78 50 36" fill="none" stroke="#8fd9a9" stroke-width="1" stroke-linecap="round" stroke-dasharray="1 6" opacity=".9"/>' +
        putLong(d, 50, 80, 2.15, -14);
    }
    return o;
  }

  /* ---------- Figuras: sota, caballo y rey ---------- */
  function held(s, x, y, k, rot) {
    if (s === "oros") return put(coin, x, y, k, rot);
    if (s === "copas") return put(cup, x, y, k, rot);
    return putLong(s === "espadas" ? sword : club, x, y, k * 0.62, rot);
  }
  var O = ' stroke="' + LINE + '" stroke-width="1.1" stroke-linejoin="round" stroke-linecap="round"';

  function sota(s) {
    var c = PAL[s], g = "";
    g += '<path d="M40 108v26h8v-26zM52 108v26h8v-26z" fill="' + c.main + '"' + O + "/>";
    g += '<path d="M52 108v26h8v-26z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M37 134h12M51 134h12" stroke="#3b2414" stroke-width="3.4" stroke-linecap="round"/>';
    g += '<path d="M33 100h34l3 11H30z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M36 66Q50 58 64 66L67 101H33Z" fill="' + c.main + '"' + O + "/>";
    g += '<path d="M50 62V101" stroke="' + c.dark + '" stroke-width="1.2"/>';
    g += '<path d="M35 92h30" stroke="' + c.trim + '" stroke-width="2.2"/>';
    g += '<circle cx="50" cy="75" r="1.3" fill="' + c.trim + '"/><circle cx="50" cy="83" r="1.3" fill="' + c.trim + '"/>';
    g += '<path d="M38 66L26 92l5 3 11-22zM62 66l11 24-6 3-10-20z" fill="' + c.main + '"' + O + "/>";
    g += '<circle cx="28" cy="95" r="3" fill="' + SKIN + '"' + O + "/>";
    g += held(s, 73, 90, 1.25, 0);
    g += '<circle cx="71" cy="92" r="3" fill="' + SKIN + '"' + O + "/>";
    g += '<ellipse cx="50" cy="63" rx="11" ry="3.6" fill="' + c.trim + '"' + O + "/>";
    g += '<circle cx="50" cy="49" r="9.4" fill="' + SKIN + '"' + O + "/>";
    g += '<path d="M40.6 47Q42 39 50 39Q58 39 59.4 47Q50 43 40.6 47z" fill="#5a3a1c"/>';
    g += '<path d="M37 44C37 28 63 28 63 44Q50 38 37 44Z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M60 35Q79 20 76 46" fill="none" stroke="' + c.alt + '" stroke-width="3.2" stroke-linecap="round"/>';
    g += '<path d="M60 35Q79 20 76 46" fill="none" stroke="' + c.trim + '" stroke-width="1" stroke-linecap="round" stroke-dasharray="1.5 3"/>';
    g += '<circle cx="46.4" cy="50" r="1.1" fill="' + LINE + '"/><circle cx="53.6" cy="50" r="1.1" fill="' + LINE + '"/>';
    g += '<path d="M47.4 54.4Q50 56 52.6 54.4" fill="none" stroke="#8a3b2a" stroke-width="1"/>';
    return g;
  }

  function caballo(s) {
    var c = PAL[s], g = "", white = (s === "copas" || s === "bastos");
    var hc = white ? "#f4f1e8" : "#8c5a2e", hd = white ? "#c9c4b4" : "#5a3718";
    g += '<path d="M33 112v24M42 114v22M63 114v22M72 112v24" stroke="' + hc + '" stroke-width="5" stroke-linecap="round"/>';
    g += '<path d="M33 112v24M42 114v22M63 114v22M72 112v24" stroke="' + LINE + '" stroke-width=".7" stroke-linecap="round" opacity=".55"/>';
    g += '<path d="M30 136h6M39 136h6M60 136h6M69 136h6" stroke="#3b2414" stroke-width="2.6" stroke-linecap="round"/>';
    g += '<path d="M77 100Q93 98 90 126Q86 112 77 110z" fill="' + hd + '"' + O + "/>";
    g += '<ellipse cx="54" cy="103" rx="27" ry="13.5" fill="' + hc + '"' + O + "/>";
    g += '<path d="M33 98L28 70 20 63 10 71l3 9 8-1 4 22z" fill="' + hc + '"' + O + "/>";
    g += '<path d="M28 70Q36 74 36 92" fill="none" stroke="' + hd + '" stroke-width="4" stroke-linecap="round"/>';
    g += '<circle cx="18" cy="71" r="1.3" fill="' + LINE + '"/>';
    g += '<path d="M12 76l5 1" stroke="' + LINE + '" stroke-width=".9" stroke-linecap="round"/>';
    g += '<path d="M40 93h28v17H40z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M40 108h28" stroke="' + c.trim + '" stroke-width="2" stroke-dasharray="2 2"/>';
    g += '<path d="M47 91l1-27h14l2 27z" fill="' + c.main + '"' + O + "/>";
    g += '<path d="M48 78h16" stroke="' + c.trim + '" stroke-width="2"/>';
    g += '<path d="M60 70Q72 64 78 52" stroke="' + c.main + '" stroke-width="7" stroke-linecap="round" fill="none"/>';
    g += '<circle cx="79" cy="48" r="3" fill="' + SKIN + '"' + O + "/>";
    g += held(s, 79, 38, 1.1, 0);
    g += '<circle cx="55" cy="54" r="8" fill="' + SKIN + '"' + O + "/>";
    g += '<path d="M46 52C47 38 63 38 64 52Q55 47 46 52Z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M60 43Q72 33 71 49" fill="none" stroke="' + c.trim + '" stroke-width="2.4" stroke-linecap="round"/>';
    g += '<circle cx="52" cy="55" r="1" fill="' + LINE + '"/><circle cx="58.4" cy="55" r="1" fill="' + LINE + '"/>';
    return g;
  }

  function rey(s) {
    var c = PAL[s], g = "";
    g += '<path d="M18 140L21 50Q50 38 79 50L82 140z" fill="' + c.alt + '"' + O + "/>";
    g += '<path d="M24 138L27 56Q50 46 73 56L76 138z" fill="' + c.dark + '" opacity=".35"/>';
    g += '<path d="M26 140L32 76Q50 66 68 76L74 140z" fill="' + c.main + '"' + O + "/>";
    g += '<path d="M50 70V140" stroke="' + c.dark + '" stroke-width="1.2" opacity=".7"/>';
    g += '<path d="M31 77Q50 90 69 77L66 91Q50 99 34 91z" fill="#fbf6e8"' + O + "/>";
    g += '<circle cx="40" cy="84" r="1.2" fill="' + LINE + '"/><circle cx="50" cy="89" r="1.2" fill="' + LINE + '"/><circle cx="60" cy="84" r="1.2" fill="' + LINE + '"/><circle cx="45" cy="93" r="1" fill="' + LINE + '"/><circle cx="55" cy="93" r="1" fill="' + LINE + '"/>';
    g += '<path d="M31 80L23 106M69 80l8 24" stroke="' + c.main + '" stroke-width="9" stroke-linecap="round"/>';
    g += '<path d="M31 80L23 106M69 80l8 24" stroke="' + LINE + '" stroke-width=".7" stroke-linecap="round" opacity=".5"/>';
    g += '<circle cx="23" cy="108" r="3.4" fill="' + SKIN + '"' + O + '/><circle cx="77" cy="106" r="3.4" fill="' + SKIN + '"' + O + "/>";
    g += '<path d="M86 34v104" stroke="' + LINE + '" stroke-width="2.4" stroke-linecap="round"/><circle cx="86" cy="32" r="4.2" fill="url(#cgGold)"' + O + "/>";
    g += '<path d="M80 36h12" stroke="#c98f14" stroke-width="1.6" stroke-linecap="round"/>';
    g += held(s, 22, 122, 1.0, 0);
    g += '<circle cx="50" cy="54" r="12.5" fill="' + SKIN + '"' + O + "/>";
    g += '<path d="M37.6 57Q39 78 50 80Q61 78 62.4 57Q50 68 37.6 57Z" fill="#e4dfd4"' + O + "/>";
    g += '<path d="M44 62Q50 59 56 62" fill="none" stroke="#bdb6a4" stroke-width="1.3"/>';
    g += '<path d="M36.6 42L38.6 24 45 34 50 21 55 34 61.4 24 63.4 42z" fill="url(#cgGold)"' + O + "/>";
    g += '<path d="M36.6 42h26.8" stroke="#7a560a" stroke-width="1.4"/>';
    g += '<circle cx="50" cy="38" r="1.8" fill="#c23a22"/><circle cx="43.6" cy="38.6" r="1.3" fill="#2f63b8"/><circle cx="56.4" cy="38.6" r="1.3" fill="#2f63b8"/>';
    g += '<circle cx="45" cy="53" r="1.2" fill="' + LINE + '"/><circle cx="55" cy="53" r="1.2" fill="' + LINE + '"/>';
    return g;
  }

  function svg(r, s) {
    var ink = INK[s], c = PAL[s], body;
    if (r === 1) body = as(s);
    else if (r === 10) body = sota(s);
    else if (r === 11) body = caballo(s);
    else if (r === 12) body = rey(s);
    else body = pips(r, s);
    var txt = 'font-family="Fraunces, Georgia, serif" font-weight="700" font-size="11" fill="' + ink + '" fill-opacity=".85"';
    return '<svg viewBox="0 0 100 156" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="' + r + " de " + s + '" focusable="false">' +
      '<rect x="1" y="1" width="98" height="154" rx="9" fill="#fffcf2" stroke="#cfc4a4" stroke-width="1.4"/>' +
      '<rect x="5" y="5" width="90" height="146" rx="5" fill="none" stroke="' + c.main + '" stroke-opacity=".5" stroke-width=".9"/>' +
      body +
      '<text x="9" y="17" ' + txt + ">" + r + "</text>" +
      '<text x="91" y="139" ' + txt + ' transform="rotate(180 91 139)">' + r + "</text>" +
      "</svg>";
  }

  window.Cartas = { svg: svg, ranks: [1, 2, 3, 4, 5, 6, 7, 10, 11, 12], suits: ["oros", "copas", "espadas", "bastos"], nombre: function (r) { return NOMBRE[r] || String(r); } };
})();
