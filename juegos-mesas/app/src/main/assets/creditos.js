/* Créditos estilo cine: suben y suben sin acabarse. Se muestran al terminar una partida (con "Saltar") y desde "Créditos" en el menú. */
(function () {
  var LINEAS = [
    ["t", "JUEGOS DE MESAS VENEZOLANOS"],
    ["s", ""],
    ["m", "Un regalo de"],
    ["img", ""],
    ["g", "BENSHALYAHU"],
    ["m", "el hebreo de Venezuela"],
    ["s", ""],
    ["h", "IDEA, DIRECCIÓN Y PRODUCCIÓN"],
    ["n", "Benshalyahu, el hebreo de Venezuela"],
    ["s", ""],
    ["h", "REPARTO"],
    ["n", "La señora del globo"],
    ["n", "Don Pedro, el que siempre gana"],
    ["n", "Maní y Tostón"],
    ["n", "Los de la mesa de la esquina"],
    ["n", "El que grita ¡BINGO! sin tener bingo"],
    ["s", ""],
    ["h", "DOMINÓ · BINGO · CAÍDA · AJILEI"],
    ["h", "ROBAPILÓN · TREINTA Y UNO"],
    ["h", "CHAPITAS · FÚTBOL CALLEJERO"],
    ["s", ""],
    ["h", "AGRADECIMIENTOS ESPECIALES"],
    ["n", "A las abuelas que nunca perdían"],
    ["n", "A los vecinos que prestaban la mesa"],
    ["n", "A la tarde que se alargaba y nadie quería irse"],
    ["n", "A todos los que alguna vez dijeron «una más y nos vamos»"],
    ["s", ""],
    ["m", "Hecho en Venezuela, con cariño."],
    ["s", ""],
    ["m", "Ningún cartón fue maltratado durante la producción."],
    ["m", "Alguna ficha de dominó sí fue golpeada contra la mesa."],
    ["s", ""],
    ["g", "GRACIAS POR JUGAR"],
    ["s", ""],
    ["m", "Y cuando creas que se acabaron los créditos…"],
    ["m", "empiezan otra vez."],
    ["s", ""],
    ["s", ""]
  ];
  var estilos = "#cred-fondo{position:fixed;inset:0;z-index:99999;background:#000;overflow:hidden;color:#f3eddc;font-family:Georgia,'Times New Roman',serif;text-align:center}" +
    "#cred-fondo .cr-rollo{position:absolute;left:0;right:0;top:0;will-change:transform}" +
    "#cred-fondo .cr-t{font-size:2rem;letter-spacing:.18em;color:#d4af37;margin:0}" +
    "#cred-fondo .cr-g{font-size:2.2rem;letter-spacing:.2em;color:#fff;margin:6px 0}" +
    "#cred-fondo .cr-h{font-size:.95rem;letter-spacing:.3em;color:#d4af37;margin:46px 0 10px}" +
    "#cred-fondo .cr-n{font-size:1.25rem;margin:8px 0}" +
    "#cred-fondo .cr-m{font-size:1.1rem;font-style:italic;color:#cfc7ad;margin:6px 0}" +
    "#cred-fondo .cr-s{height:90px}" +
    "#cred-fondo img{width:190px;height:190px;border-radius:50%;margin:10px auto;display:block}" +
    "#cred-fondo .cr-top,#cred-fondo .cr-bot{position:absolute;left:0;right:0;height:18%;z-index:2;pointer-events:none}" +
    "#cred-fondo .cr-top{top:0;background:linear-gradient(#000,transparent)}" +
    "#cred-fondo .cr-bot{bottom:0;background:linear-gradient(transparent,#000)}" +
    "#cred-fondo button{position:absolute;z-index:3;top:calc(12px + env(safe-area-inset-top,0px));right:14px;background:rgba(255,255,255,.12);color:#fff;border:1px solid rgba(255,255,255,.4);border-radius:999px;padding:9px 16px;font:inherit;font-size:.95rem;cursor:pointer}" +
    "@keyframes cr-sube{from{transform:translateY(var(--cr-ini))}to{transform:translateY(var(--cr-fin))}}";
  var abierto = false, pendiente = null;

  function bloque() {
    var h = '<div style="height:100vh"></div>';
    LINEAS.forEach(function (l) {
      if (l[0] === "img") h += '<img src="marca.png" alt="">';
      else if (l[0] === "s") h += '<div class="cr-s"></div>';
      else h += '<div class="cr-' + l[0] + '">' + l[1] + "</div>";
    });
    return h;
  }

  function abrir() {
    if (abierto) return;
    abierto = true;
    if (pendiente) { clearTimeout(pendiente); pendiente = null; }
    var st = document.createElement("style"); st.id = "cred-estilo"; st.textContent = estilos; document.head.appendChild(st);
    var f = document.createElement("div"); f.id = "cred-fondo"; f.setAttribute("role", "dialog"); f.setAttribute("aria-label", "Créditos");
    f.innerHTML = '<div class="cr-top"></div><div class="cr-bot"></div><button type="button" id="cred-cerrar">Saltar ✕</button><div class="cr-rollo"><div class="cr-b">' + bloque() + '</div><div class="cr-b">' + bloque() + "</div></div>";
    document.body.appendChild(f);
    var rollo = f.querySelector(".cr-rollo"), una = f.querySelector(".cr-b");
    var alto = una.offsetHeight || 2000;
    /* dos copias seguidas: al terminar la primera vuelta, la segunda ocupa su lugar y el ciclo vuelve a empezar sin corte */
    rollo.style.setProperty("--cr-ini", "0px");
    rollo.style.setProperty("--cr-fin", "-" + alto + "px");
    rollo.style.animation = "cr-sube " + Math.max(40, alto / 55) + "s linear infinite";
    document.getElementById("cred-cerrar").onclick = cerrar;
    f.addEventListener("keydown", function (e) { if (e.key === "Escape") cerrar(); });
  }
  function cerrar() {
    var f = document.getElementById("cred-fondo"), st = document.getElementById("cred-estilo");
    if (f) f.remove(); if (st) st.remove();
    abierto = false;
  }
  window.Creditos = {
    abrir: abrir,
    cerrar: cerrar,
    /* Se llama cuando acaba una partida: espera un instante para que se vea el resultado y entonces suben los créditos. */
    alTerminar: function () {
      if (abierto || pendiente) return;
      pendiente = setTimeout(function () { pendiente = null; abrir(); }, 1800);
    }
  };
})();
