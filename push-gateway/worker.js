/**
 * Chamos Push Gateway (Cloudflare Worker)
 *
 * Recibe los avisos del servidor Matrix (POST /_matrix/push/v1/notify) y los reenvía
 * a Firebase Cloud Messaging (FCM) para que Android despierte la app aunque esté cerrada.
 *
 * Único ajuste que necesita: el secreto  FCM_SERVICE_ACCOUNT  con el contenido completo del
 * JSON de la cuenta de servicio de Firebase. NUNCA lo subas a GitHub.
 */

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const MAX_DEVICES = 20;
const MAX_BODY_BYTES = 64 * 1024;

let cachedToken = null; // { value, expiresAt }

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

function b64url(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem) {
  const clean = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(clean);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

function loadServiceAccount(env) {
  if (!env.FCM_SERVICE_ACCOUNT) throw new Error("Falta el secreto FCM_SERVICE_ACCOUNT");
  let sa;
  try {
    sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  } catch {
    throw new Error("FCM_SERVICE_ACCOUNT no es un JSON válido");
  }
  if (!sa.client_email || !sa.private_key || !sa.project_id) {
    throw new Error("FCM_SERVICE_ACCOUNT no parece una clave de cuenta de servicio de Firebase");
  }
  return sa;
}

async function getAccessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) return cachedToken.value;

  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(
    JSON.stringify({ iss: sa.client_email, scope: FCM_SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 }),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${b64url(sig)}`;

  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`Google rechazó la clave de servicio (${res.status})`);
  const data = await res.json();
  cachedToken = { value: data.access_token, expiresAt: now + (data.expires_in || 3600) };
  return cachedToken.value;
}

/** Datos que la app lee en FirebasePushParser: event_id, room_id, unread y cs. Todo en texto. */
function buildData(n, device) {
  const data = {};
  if (n.event_id) data.event_id = String(n.event_id);
  if (n.room_id) data.room_id = String(n.room_id);
  const unread = n.counts && n.counts.unread;
  if (unread !== undefined && unread !== null) data.unread = String(unread);
  const payload = device.data && device.data.default_payload;
  if (payload && typeof payload === "object") {
    for (const [k, v] of Object.entries(payload)) {
      if (v !== null && v !== undefined && !(k in data)) data[k] = String(v);
    }
  }
  return data;
}

async function sendToDevice(sa, accessToken, n, device) {
  const body = {
    message: {
      token: device.pushkey,
      data: buildData(n, device),
      android: { priority: n.prio === "low" ? "NORMAL" : "HIGH" },
    },
  };
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (res.ok) return "ok";
  const text = await res.text();
  // Token de teléfono que ya no existe: se le dice al servidor Matrix que lo olvide.
  if (res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT/.test(text)) return "rejected";
  console.error("FCM error", res.status, text.slice(0, 300));
  return "error";
}

async function handleNotify(request, env) {
  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) return json({ error: "Cuerpo demasiado grande" }, 413);
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }
  const n = payload && payload.notification;
  const devices = n && Array.isArray(n.devices) ? n.devices : null;
  if (!devices) return json({ error: "Falta notification.devices" }, 400);

  const sa = loadServiceAccount(env);
  const accessToken = await getAccessToken(sa);
  const rejected = [];
  let failed = false;
  for (const device of devices.slice(0, MAX_DEVICES)) {
    if (!device || typeof device.pushkey !== "string" || !device.pushkey) continue;
    const result = await sendToDevice(sa, accessToken, n, device);
    if (result === "rejected") rejected.push(device.pushkey);
    if (result === "error") failed = true;
  }
  // 502: el servidor Matrix reintentará más tarde.
  if (failed && rejected.length === 0) return json({ error: "FCM no respondió bien" }, 502);
  return json({ rejected });
}

// ───────────────────────── Estadísticas de Chamos (base de datos D1) ─────────────────────────
// Necesita: una base D1 enlazada con el nombre DB y el secreto ADMIN_KEY (la clave del panel).
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, GET, HEAD, OPTIONS",
  "access-control-allow-headers": "content-type, x-clave, authorization, range",
};
const jsonCors = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...CORS } });

// Partidas de la videollamada (la vieja, ahorcado, parchís): el estado se guarda por sala y versión.
async function juego(request, env, url) {
  await prepararTablas(env);
  let sala = "";
  try { sala = decodeURIComponent(url.pathname.slice(7)); } catch (_) {}
  sala = sala.slice(0, 120);
  if (!sala) return jsonCors({ error: "Falta la sala" }, 400);
  const leer = async () => {
    const r = await env.DB.prepare("SELECT v, estado FROM juegos WHERE sala = ?").bind(sala).first();
    return r ? { v: r.v, estado: JSON.parse(r.estado) } : { v: 0, estado: null };
  };
  if (request.method === "GET") return jsonCors(await leer());
  if (request.method !== "POST") return jsonCors({ error: "Usa GET o POST" }, 405);
  const texto = await request.text();
  if (texto.length > 4000) return jsonCors({ error: "Demasiado grande" }, 413);
  const b = JSON.parse(texto);
  const v = Number(b.v) || 0;
  const ahora = Date.now();
  await env.DB.prepare(
    "INSERT INTO juegos (sala, v, estado, ts) VALUES (?, ?, ?, ?) ON CONFLICT(sala) DO UPDATE SET v = excluded.v, estado = excluded.estado, ts = excluded.ts WHERE juegos.v < excluded.v",
  ).bind(sala, v, JSON.stringify(b.estado), ahora).run();
  const actual = await leer();
  if (Math.random() < 0.02) await env.DB.prepare("DELETE FROM juegos WHERE ts < ?").bind(ahora - 24 * 3600 * 1000).run();
  if (actual.v !== v) return jsonCors({ ...actual, rechazado: true });
  return jsonCors({ v });
}

let tablasListas = false;
async function prepararTablas(env) {
  if (tablasListas) return;
  await env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS eventos (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, tipo TEXT, anon TEXT, pais TEXT, extra TEXT)",
    ),
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS usuarios (user_id TEXT PRIMARY KEY, nombre TEXT, version TEXT, modelo TEXT, pais TEXT, anon TEXT, primera INTEGER, ultima INTEGER)",
    ),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_eventos_tipo_ts ON eventos (tipo, ts)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS ia_uso (clave TEXT, dia TEXT, n INTEGER, PRIMARY KEY (clave, dia))"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS juegos (sala TEXT PRIMARY KEY, v INTEGER, estado TEXT, ts INTEGER)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS seguidores (seguidor TEXT, seguido TEXT, ts INTEGER, PRIMARY KEY (seguidor, seguido))"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_seguidores_seguido ON seguidores (seguido)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS opiniones (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, nombre TEXT, texto TEXT, pais TEXT, ip TEXT)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS video_acciones (evento TEXT, autor TEXT, usuario TEXT, tipo TEXT, ts INTEGER, PRIMARY KEY (evento, usuario, tipo))"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_video_acciones_autor ON video_acciones (autor, tipo)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS estado_vistas (evento TEXT, usuario TEXT, dueno TEXT, nombre TEXT, ts INTEGER, PRIMARY KEY (evento, usuario))"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS estado_reacciones (evento TEXT, usuario TEXT, dueno TEXT, nombre TEXT, emoji TEXT, ts INTEGER, PRIMARY KEY (evento, usuario))"),
  ]);
  // Columna de la dirección IP (si ya existe, el error se ignora).
  for (const tabla of ["eventos", "usuarios"]) {
    try {
      await env.DB.prepare("ALTER TABLE " + tabla + " ADD COLUMN ip TEXT").run();
    } catch (_) {}
  }
  // Dispositivo: modelo del teléfono, o sistema y navegador si entró desde una PC.
  try {
    await env.DB.prepare("ALTER TABLE eventos ADD COLUMN disp TEXT").run();
  } catch (_) {}
  tablasListas = true;
}

// Resume "SM-A135M · Android 13 · Chrome 120" o "PC · Windows · Firefox 119".
function dispositivo(ua, d) {
  ua = ua || "";
  let nav = "Otro";
  let m;
  if ((m = ua.match(/(Edg|EdgA|EdgiOS)\/(\d+)/))) nav = "Edge " + m[2];
  else if ((m = ua.match(/OPR\/(\d+)/))) nav = "Opera " + m[1];
  else if ((m = ua.match(/SamsungBrowser\/(\d+)/))) nav = "Samsung Internet " + m[1];
  else if ((m = ua.match(/(Firefox|FxiOS)\/(\d+)/))) nav = "Firefox " + m[2];
  else if ((m = ua.match(/(Chrome|CriOS)\/(\d+)/))) nav = "Chrome " + m[2];
  else if ((m = ua.match(/Version\/(\d+).*Safari/))) nav = "Safari " + m[1];
  const app = ua.match(/Instagram|FBAN|FBAV|TikTok|WhatsApp/i);
  if (app) nav += " (dentro de " + app[0] + ")";
  let so = "";
  let modelo = corto(d && d.m, 60);
  if ((m = ua.match(/Android ([\d.]+)/))) {
    so = "Android " + m[1];
    if (!modelo || modelo === "K") {
      const mm = ua.match(/Android [\d.]+; ([^;)]+)/);
      modelo = mm && mm[1] !== "K" ? mm[1].replace(/ Build.*/, "") : "";
    }
  } else if (/iPhone|iPad/.test(ua)) {
    so = "iOS";
    modelo = (ua.match(/iPhone|iPad/) || [""])[0];
  } else if (/Windows/.test(ua)) so = "Windows";
  else if (/Mac OS X/.test(ua)) so = "Mac";
  else if (/CrOS/.test(ua)) so = "ChromeOS";
  else if (/Linux/.test(ua)) so = "Linux";
  const movil = /Android|iPhone|iPad|Mobile/.test(ua);
  return [movil ? modelo || "Teléfono" : "PC", so, nav].filter(Boolean).join(" · ").slice(0, 160);
}

const corto = (v, n) => (v === undefined || v === null ? "" : String(v).slice(0, n));

async function guardarEvento(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  const raw = await request.text();
  if (raw.length > 2048) return jsonCors({ error: "Demasiado grande" }, 413);
  let d;
  try {
    d = JSON.parse(raw);
  } catch {
    return jsonCors({ error: "JSON inválido" }, 400);
  }
  const tipo = corto(d.t, 20);
  if (!["visita", "descarga", "instalacion", "registro", "descarga_media", "denuncia", "publicacion"].includes(tipo)) return jsonCors({ error: "Tipo desconocido" }, 400);
  if (String(env.SOLO_RELEASE || "").trim() === "1" && !["visita", "descarga"].includes(tipo) && request.headers.get("x-chamos-app") !== "com.chamos.app") return jsonCors(CERRADA, 426);
  await prepararTablas(env);
  const ahora = Date.now();
  const pais = corto(request.cf && request.cf.country, 4);
  const anon = corto(d.id, 64);
  const ip = corto(request.headers.get("cf-connecting-ip"), 64);
  const disp = dispositivo(request.headers.get("user-agent"), d);
  if (tipo === "registro") {
    const uid = corto(d.u, 100);
    if (!uid.startsWith("@")) return jsonCors({ error: "Falta usuario" }, 400);
    await env.DB.prepare(
      "INSERT INTO usuarios (user_id, nombre, version, modelo, pais, anon, primera, ultima, ip) VALUES (?1,?2,?3,?4,?5,?6,?7,?7,?8) " +
        "ON CONFLICT(user_id) DO UPDATE SET nombre=?2, version=?3, modelo=?4, pais=?5, anon=?6, ultima=?7, ip=?8",
    )
      .bind(uid, corto(d.n, 80), corto(d.v, 20), corto(d.m, 60), pais, anon, ahora, ip)
      .run();
  } else {
    const extra = JSON.stringify({ v: corto(d.v, 20), m: corto(d.m, 60), p: corto(d.p, 100) });
    await env.DB.prepare("INSERT INTO eventos (ts, tipo, anon, pais, extra, ip, disp) VALUES (?1,?2,?3,?4,?5,?6,?7)")
      .bind(ahora, tipo, anon, pais, extra, ip, disp)
      .run();
  }
  return jsonCors({ ok: true });
}

// Estados: quién los vio y con qué emoji reaccionó cada persona. Solo el dueño del estado ve la lista.
async function estadoSocialApi(request, env, url) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  const yo = await videoAuth(request);
  if (!yo) return jsonCors({ error: "Sesión no válida" }, 401);
  await prepararTablas(env);
  const ahora = Date.now();
  if (request.method === "POST") {
    let d;
    try { d = JSON.parse(await request.text()); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
    const evento = corto(d.e, 120);
    const dueno = corto(d.d, 100);
    const nombre = corto(d.n, 60) || yo;
    if (!evento.startsWith("$") || !dueno.startsWith("@") || dueno === yo) return jsonCors({ error: "Datos no válidos" }, 400);
    if (url.pathname === "/estado/visto") {
      await env.DB.prepare("INSERT OR IGNORE INTO estado_vistas (evento, usuario, dueno, nombre, ts) VALUES (?1,?2,?3,?4,?5)").bind(evento, yo, dueno, nombre, ahora).run();
    } else {
      const emoji = corto(d.x, 16);
      if (emoji) {
        await env.DB.prepare("INSERT OR REPLACE INTO estado_reacciones (evento, usuario, dueno, nombre, emoji, ts) VALUES (?1,?2,?3,?4,?5,?6)").bind(evento, yo, dueno, nombre, emoji, ahora).run();
      } else {
        await env.DB.prepare("DELETE FROM estado_reacciones WHERE evento=?1 AND usuario=?2").bind(evento, yo).run();
      }
    }
    if (Math.random() < 0.05) {
      const limite = ahora - 48 * 3600 * 1000;
      await env.DB.batch([
        env.DB.prepare("DELETE FROM estado_vistas WHERE ts < ?1").bind(limite),
        env.DB.prepare("DELETE FROM estado_reacciones WHERE ts < ?1").bind(limite),
      ]);
    }
    return jsonCors({ ok: true });
  }
  const ids = (url.searchParams.get("e") || "").split(",").map((x) => corto(x, 120)).filter((x) => x.startsWith("$")).slice(0, 40);
  const out = {};
  for (const id of ids) {
    const [v, r, m] = await env.DB.batch([
      env.DB.prepare("SELECT usuario, nombre, ts FROM estado_vistas WHERE evento=?1 AND dueno=?2 ORDER BY ts DESC LIMIT 300").bind(id, yo),
      env.DB.prepare("SELECT usuario, nombre, emoji FROM estado_reacciones WHERE evento=?1 AND dueno=?2 ORDER BY ts DESC LIMIT 300").bind(id, yo),
      env.DB.prepare("SELECT emoji FROM estado_reacciones WHERE evento=?1 AND usuario=?2").bind(id, yo),
    ]);
    out[id] = { v: v.results, r: r.results, mia: m.results.length ? m.results[0].emoji : "" };
  }
  return jsonCors(out);
}

// Acciones sobre los videos de alguien (like, guardar, compartir, denuncia) y resumen para su dueño.
async function videoSocialApi(request, env, url) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  const yo = await videoAuth(request);
  if (!yo) return jsonCors({ error: "Sesión no válida" }, 401);
  await prepararTablas(env);
  const ahora = Date.now();
  if (request.method === "POST") {
    let d;
    try { d = JSON.parse(await request.text()); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
    const evento = corto(d.e, 120);
    const autor = corto(d.a, 100);
    const tipo = corto(d.t, 12);
    if (!evento.startsWith("$") || !autor.startsWith("@") || autor === yo) return jsonCors({ error: "Datos no válidos" }, 400);
    if (tipo === "unlike") {
      await env.DB.prepare("DELETE FROM video_acciones WHERE evento=?1 AND usuario=?2 AND tipo='like'").bind(evento, yo).run();
    } else if (["like", "guardar", "compartir", "denuncia"].includes(tipo)) {
      await env.DB.prepare("INSERT OR REPLACE INTO video_acciones (evento, autor, usuario, tipo, ts) VALUES (?1,?2,?3,?4,?5)").bind(evento, autor, yo, tipo, ahora).run();
    } else {
      return jsonCors({ error: "Acción no válida" }, 400);
    }
    return jsonCors({ ok: true });
  }
  const [t, s] = await env.DB.batch([
    env.DB.prepare("SELECT tipo, COUNT(*) AS n FROM video_acciones WHERE autor=?1 GROUP BY tipo").bind(yo),
    env.DB.prepare("SELECT COUNT(*) AS n FROM seguidores WHERE seguido=?1").bind(yo),
  ]);
  const por = {};
  for (const r of t.results) por[r.tipo] = r.n;
  return jsonCors({ likes: por.like || 0, guardados: por.guardar || 0, compartidos: por.compartir || 0, denuncias: por.denuncia || 0, seguidores: s.results[0].n });
}

// Opiniones de la web ("Danos tu opinión"). Pública, con límite de 3 por hora y por dirección.
async function opinionApi(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  const raw = await request.text();
  if (raw.length > 4096) return jsonCors({ error: "Demasiado largo" }, 413);
  let d;
  try { d = JSON.parse(raw); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
  if (d.web) return jsonCors({ ok: true }); // campo trampa para robots
  const texto = corto(d.texto, 1000).trim();
  const nombre = corto(d.nombre, 60).trim();
  if (texto.length < 3) return jsonCors({ error: "Escribe tu opinión" }, 400);
  await prepararTablas(env);
  const ip = corto(request.headers.get("cf-connecting-ip"), 64);
  const ahora = Date.now();
  const r = await env.DB.prepare("SELECT COUNT(*) AS n FROM opiniones WHERE ip=?1 AND ts>?2").bind(ip, ahora - 3600 * 1000).all();
  if (r.results[0].n >= 3) return jsonCors({ error: "Ya enviaste varias opiniones. Inténtalo más tarde." }, 429);
  await env.DB.prepare("INSERT INTO opiniones (ts, nombre, texto, pais, ip) VALUES (?1,?2,?3,?4,?5)")
    .bind(ahora, nombre, texto, corto(request.cf && request.cf.country, 4), ip).run();
  return jsonCors({ ok: true });
}

// Seguir / dejar de seguir. La persona se identifica con su sesión de Matrix (igual que los videos).
async function seguirApi(request, env, url) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  const yo = await videoAuth(request);
  if (!yo) return jsonCors({ error: "Sesión no válida" }, 401);
  await prepararTablas(env);
  let otro = "";
  let accion = null;
  if (request.method === "POST") {
    let d;
    try { d = JSON.parse(await request.text()); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
    otro = corto(d.u, 100);
    accion = d.s === true ? "seguir" : "dejar_seguir";
  } else {
    otro = corto(url.searchParams.get("u"), 100);
  }
  if (!otro.startsWith("@") || otro === yo) return jsonCors({ error: "Usuario no válido" }, 400);
  const ahora = Date.now();
  if (accion === "seguir") {
    await env.DB.prepare("INSERT OR IGNORE INTO seguidores (seguidor, seguido, ts) VALUES (?1,?2,?3)").bind(yo, otro, ahora).run();
  } else if (accion === "dejar_seguir") {
    await env.DB.prepare("DELETE FROM seguidores WHERE seguidor=?1 AND seguido=?2").bind(yo, otro).run();
  }
  if (accion) {
    await env.DB.prepare("INSERT INTO eventos (ts, tipo, anon, pais, extra) VALUES (?1,?2,?3,?4,?5)")
      .bind(ahora, accion, "", corto(request.cf && request.cf.country, 4), JSON.stringify({ u: yo, a: otro }))
      .run();
  }
  const [n, sigo] = await env.DB.batch([
    env.DB.prepare("SELECT COUNT(*) AS n FROM seguidores WHERE seguido=?1").bind(otro),
    env.DB.prepare("SELECT COUNT(*) AS n FROM seguidores WHERE seguidor=?1 AND seguido=?2").bind(yo, otro),
  ]);
  return jsonCors({ n: n.results[0].n, sigo: sigo.results[0].n > 0 });
}

async function resumen(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  if (!env.ADMIN_KEY) return jsonCors({ error: "Falta el secreto ADMIN_KEY" }, 503);
  if (request.headers.get("x-clave") !== env.ADMIN_KEY) return jsonCors({ error: "Clave incorrecta" }, 401);
  await prepararTablas(env);
  const hace30 = Date.now() - 30 * 24 * 3600 * 1000;
  const [tot, unicos, dias, paises, usuarios, nUsuarios, versiones, recientes, topSeg, nSeg, opiniones, modelos] = await env.DB.batch([
    env.DB.prepare("SELECT tipo, COUNT(*) AS n FROM eventos GROUP BY tipo"),
    env.DB.prepare("SELECT tipo, COUNT(DISTINCT anon) AS n FROM eventos GROUP BY tipo"),
    env.DB.prepare(
      "SELECT strftime('%Y-%m-%d', ts/1000, 'unixepoch', '-4 hours') AS dia, tipo, COUNT(*) AS n FROM eventos WHERE ts > ?1 GROUP BY dia, tipo ORDER BY dia DESC",
    ).bind(hace30),
    env.DB.prepare("SELECT pais, COUNT(*) AS n FROM eventos WHERE tipo='visita' GROUP BY pais ORDER BY n DESC LIMIT 10"),
    env.DB.prepare("SELECT user_id, nombre, version, modelo, pais, primera, ultima, ip FROM usuarios ORDER BY primera DESC LIMIT 200"),
    env.DB.prepare("SELECT COUNT(*) AS n FROM usuarios"),
    env.DB.prepare("SELECT version, COUNT(*) AS n FROM usuarios GROUP BY version ORDER BY n DESC"),
    env.DB.prepare("SELECT ts, tipo, pais, ip, disp FROM eventos WHERE tipo IN ('visita','descarga','instalacion') ORDER BY ts DESC LIMIT 200"),
    env.DB.prepare("SELECT seguido AS usuario, COUNT(*) AS n FROM seguidores GROUP BY seguido ORDER BY n DESC LIMIT 15"),
    env.DB.prepare("SELECT COUNT(*) AS n FROM seguidores"),
    env.DB.prepare("SELECT ts, nombre, texto, pais FROM opiniones ORDER BY ts DESC LIMIT 50"),
    env.DB.prepare(
      "SELECT COALESCE(NULLIF(json_extract(extra,'$.m'),''),'(sin modelo)') AS modelo, COUNT(DISTINCT anon) AS telefonos, MAX(ts) AS ultima " +
        "FROM eventos WHERE tipo='instalacion' GROUP BY modelo ORDER BY telefonos DESC, ultima DESC LIMIT 300",
    ),
  ]);
  return jsonCors({
    totales: tot.results,
    unicos: unicos.results,
    dias: dias.results,
    paises: paises.results,
    usuarios: usuarios.results,
    total_usuarios: nUsuarios.results[0].n,
    versiones: versiones.results,
    recientes: recientes.results,
    top_seguidos: topSeg.results,
    total_seguidores: nSeg.results[0].n,
    opiniones: opiniones.results,
    modelos: modelos.results,
  });
}

// ───────────────────────── Chamos IA: chat público (Workers AI, sin ejecutar nada) ─────────────────────────
// Necesita: enlace (binding) de Workers AI con el nombre AI, y la base D1 (DB) para los límites.
const ORIGENES_IA = ["https://siervobenshalyahu-ship-it.github.io"];
const MODELOS_IA = ["@cf/meta/llama-3.1-8b-instruct", "@cf/meta/llama-3.2-3b-instruct", "@cf/meta/llama-3-8b-instruct"];
const LIMITE_PERSONA_DIA = 30;
const LIMITE_GLOBAL_DIA = 200;
const SISTEMA_IA =
  "Eres Chamos IA, el asistente de la aplicación Chamos, una app de mensajería hecha por y para venezolanos. " +
  "Hablas en español venezolano, cercano y respetuoso, con respuestas cortas y claras. " +
  "Puedes conversar, explicar cosas, ayudar a redactar mensajes, contar sobre la cultura, el habla y la comida de Venezuela, y explicar cómo usar Chamos. " +
  "No das diagnósticos médicos, asesoría legal ni financiera definitiva, y no pides datos personales, claves ni números de tarjeta. " +
  "Si no sabes algo, lo dices. Si te piden algo dañino o ilegal, te niegas con amabilidad.";

async function hashCorto(texto) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(texto));
  return Array.from(new Uint8Array(buf)).slice(0, 8).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function chatIA(request, env) {
  const origen = request.headers.get("origin") || "";
  if (!ORIGENES_IA.includes(origen)) return json({ error: "Origen no permitido" }, 403);
  const cors = { "access-control-allow-origin": origen, "vary": "origin" };
  const resp = (obj, status = 200) =>
    new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...cors } });
  if (!env.AI) return resp({ error: "Chamos IA todavía no está activada." }, 503);
  if (!env.DB) return resp({ error: "Chamos IA todavía no está activada." }, 503);

  const raw = await request.text();
  if (raw.length > 8000) return resp({ error: "Mensaje demasiado largo." }, 413);
  let d;
  try {
    d = JSON.parse(raw);
  } catch {
    return resp({ error: "JSON inválido" }, 400);
  }
  const lista = Array.isArray(d.mensajes) ? d.mensajes.slice(-8) : [];
  const mensajes = lista
    .filter((m) => m && (m.rol === "user" || m.rol === "assistant") && typeof m.texto === "string" && m.texto.trim())
    .map((m) => ({ role: m.rol, content: m.texto.slice(0, 600) }));
  if (!mensajes.length || mensajes[mensajes.length - 1].role !== "user") return resp({ error: "Falta tu mensaje." }, 400);

  await prepararTablas(env);
  const dia = new Date(Date.now() - 4 * 3600 * 1000).toISOString().slice(0, 10);
  const ip = request.headers.get("cf-connecting-ip") || "sin-ip";
  const clavePersona = "p:" + (await hashCorto(ip));
  const sumar = (clave) =>
    env.DB.prepare("INSERT INTO ia_uso (clave, dia, n) VALUES (?1, ?2, 1) ON CONFLICT(clave, dia) DO UPDATE SET n = n + 1 RETURNING n").bind(clave, dia);
  const [persona, global] = await env.DB.batch([sumar(clavePersona), sumar("global")]);
  if (persona.results[0].n > LIMITE_PERSONA_DIA)
    return resp({ error: "Llegaste al límite de mensajes de hoy. ¡Vuelve mañana, chamo!" }, 429);
  if (global.results[0].n > LIMITE_GLOBAL_DIA)
    return resp({ error: "Chamos IA está muy concurrida hoy. Intenta de nuevo mañana." }, 429);

  const errores = [];
  for (const modelo of MODELOS_IA) {
    try {
      const salida = await env.AI.run(modelo, {
        messages: [{ role: "system", content: SISTEMA_IA }, ...mensajes],
        max_tokens: 400,
      });
      const texto = typeof salida === "string" ? salida : salida && (salida.response || (salida.result && salida.result.response));
      if (!texto || !String(texto).trim()) {
        errores.push(modelo + ": respuesta vacía");
        continue;
      }
      await env.DB.prepare("INSERT INTO eventos (ts, tipo, anon, pais, extra) VALUES (?1,'ia',?2,?3,'')")
        .bind(Date.now(), clavePersona, corto(request.cf && request.cf.country, 4)).run();
      return resp({ respuesta: String(texto).trim() });
    } catch (e) {
      console.error(e);
      errores.push(modelo + ": " + corto(e && e.message ? e.message : e, 160));
    }
  }
  return resp({ error: "No pude responder ahora. Intenta otra vez.", detalle: errores.join(" | ").slice(0, 400) }, 502);
}

const PANEL_HTML = `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Panel de Chamos</title><style>
:root{color-scheme:dark}body{margin:0;background:#0b1220;color:#e8eefc;font:15px system-ui,sans-serif;padding:16px}
h1{font-size:20px;margin:0 0 12px}h2{font-size:15px;margin:22px 0 8px;color:#7fb8ff}
input,button{font:inherit;padding:10px 12px;border-radius:10px;border:1px solid #2a3a5c;background:#13203a;color:#fff}
button{background:#1c8fe0;border:0;cursor:pointer}
.g{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.c{background:#13203a;border-radius:14px;padding:14px}
.c b{display:block;font-size:26px}.c span{color:#9fb2d6;font-size:13px}
table{width:100%;border-collapse:collapse;font-size:13px}td,th{padding:6px 4px;border-bottom:1px solid #1f2d4a;text-align:left}
.sc{overflow-x:auto}#err{color:#ff8a8a}
</style></head><body><h1>Panel de Chamos</h1>
<div id="login"><input id="k" type="password" placeholder="Clave del panel"> <button onclick="go()">Entrar</button><p id="err"></p></div>
<div id="app" hidden></div>
<script>
const fmt=ts=>new Date(ts).toLocaleString('es-VE',{timeZone:'America/Caracas'});
function n(l,t){const x=(l||[]).find(r=>r.tipo===t);return x?x.n:0}
async function go(){
  const k=document.getElementById('k').value||sessionStorage.getItem('k')||'';
  const r=await fetch('/api/resumen',{headers:{'x-clave':k}});
  const d=await r.json();
  if(!r.ok){document.getElementById('err').textContent=d.error||'Error';return}
  sessionStorage.setItem('k',k);
  document.getElementById('login').hidden=true;
  const a=document.getElementById('app');a.hidden=false;
  const dias={};d.dias.forEach(x=>{(dias[x.dia]=dias[x.dia]||{})[x.tipo]=x.n});
  a.innerHTML='<div class="g">'+
   '<div class="c"><b>'+n(d.totales,'visita')+'</b><span>Visitas a la web ('+n(d.unicos,'visita')+' personas distintas)</span></div>'+
   '<div class="c"><b>'+n(d.totales,'descarga')+'</b><span>Toques en Descargar ('+n(d.unicos,'descarga')+' personas)</span></div>'+
   '<div class="c"><b>'+n(d.totales,'instalacion')+'</b><span>Instalaciones abiertas ('+n(d.unicos,'instalacion')+' teléfonos)</span></div>'+
   '<div class="c"><b>'+d.total_usuarios+'</b><span>Usuarios registrados (aceptaron el aviso)</span></div>'+
   '<div class="c"><b>'+n(d.totales,'ia')+'</b><span>Mensajes a Chamos IA ('+n(d.unicos,'ia')+' personas)</span></div>'+
   '<div class="c"><b>'+n(d.totales,'descarga_media')+'</b><span>Videos y fotos guardados con marca de agua</span></div>'+
   '<div class="c"><b>'+n(d.totales,'denuncia')+'</b><span>Denuncias enviadas</span></div>'+
   '<div class="c"><b>'+d.total_seguidores+'</b><span>Seguimientos activos ('+n(d.totales,'seguir')+' en total, '+n(d.totales,'dejar_seguir')+' dejados)</span></div></div>'+
   '<h2>Opiniones de la web ('+(d.opiniones||[]).length+')</h2><div class="sc"><table><tr><th>Fecha</th><th>Nombre</th><th>Opinión</th></tr>'+(d.opiniones||[]).map(o=>'<tr><td>'+new Date(o.ts).toLocaleString()+'</td><td>'+esc(o.nombre||'—')+' '+esc(o.pais||'')+'</td><td>'+esc(o.texto)+'</td></tr>').join('')+'</table></div>'+
   '<h2>Más seguidos</h2><div class="sc"><table><tr><th>Usuario</th><th>Seguidores</th></tr>'+(d.top_seguidos||[]).map(t=>'<tr><td>'+esc(t.usuario)+'</td><td>'+t.n+'</td></tr>').join('')+'</table></div>'+
   '<h2>Últimos 30 días</h2><div class="sc"><table><tr><th>Día</th><th>Visitas</th><th>Descargas</th><th>Instal.</th></tr>'+
   Object.keys(dias).map(x=>'<tr><td>'+x+'</td><td>'+(dias[x].visita||0)+'</td><td>'+(dias[x].descarga||0)+'</td><td>'+(dias[x].instalacion||0)+'</td></tr>').join('')+'</table></div>'+
   '<h2>Visitas por país</h2><div>'+d.paises.map(p=>(p.pais||'?')+': '+p.n).join(' · ')+'</div>'+
   '<h2>Versiones de la app</h2><div>'+d.versiones.map(v=>(v.version||'?')+': '+v.n).join(' · ')+'</div>'+
   '<h2>Usuarios</h2><div class="sc"><table><tr><th>Usuario</th><th>Nombre</th><th>Versión</th><th>Teléfono</th><th>IP</th><th>Desde</th><th>Última vez</th></tr>'+
   d.usuarios.map(u=>'<tr><td>'+esc(u.user_id)+'</td><td>'+esc(u.nombre)+'</td><td>'+esc(u.version)+'</td><td>'+esc(u.modelo)+'</td><td>'+esc(u.ip)+'</td><td>'+fmt(u.primera)+'</td><td>'+fmt(u.ultima)+'</td></tr>').join('')+'</table></div>'+
   '<h2>Modelos de teléfono con Chamos instalada ('+(d.modelos||[]).length+' modelos)</h2><div class="sc"><table><tr><th>Modelo</th><th>Teléfonos</th><th>Última vez</th></tr>'+(d.modelos||[]).map(m=>'<tr><td>'+esc(m.modelo)+'</td><td>'+m.telefonos+'</td><td>'+fmt(m.ultima)+'</td></tr>').join('')+'</table></div>'+
   '<h2>Últimas visitas, descargas e instalaciones</h2><div class="sc"><table><tr><th>Cuándo</th><th>Qué</th><th>País</th><th>IP</th><th>Dispositivo</th></tr>'+
   (d.recientes||[]).map(r=>'<tr><td>'+fmt(r.ts)+'</td><td>'+esc(r.tipo)+'</td><td>'+esc(r.pais)+'</td><td>'+esc(r.ip)+'</td><td>'+esc(r.disp)+'</td></tr>').join('')+'</table></div>';
  bots();
}
function esc(s){return String(s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
async function bots(){
  const k=sessionStorage.getItem('k')||'';
  const r=await fetch('/bots/estado',{headers:{'x-clave':k}});
  const d=await r.json();
  if(!r.ok)return;
  const a=document.getElementById('app');
  const old=document.getElementById('botsbox');if(old)old.remove();
  a.insertAdjacentHTML('beforeend','<div id="botsbox"><h2>Bots ('+(d.activos?'ACTIVOS':'APAGADOS')+' · Videos: '+(d.videos||'sin clave')+' · B2 '+(d.b2?'sí':'no')+')</h2><div class="sc"><table><tr><th>Bot</th><th>Cuenta</th><th>Hoy</th><th>Últimos</th><th>Probar</th></tr>'+
   d.bots.map(b=>'<tr><td>'+esc(b.nombre)+'</td><td>'+(b.cuenta?'sí':'FALTA')+'</td><td>'+b.hoy+'</td><td>'+b.ultimos.map(u=>fmt(u.ts)+' '+u.modo+' '+(u.ok?'OK':'ERROR')+' '+esc(u.nota)).join('<br>')+'</td><td><button data-b="'+b.id+'" data-m="muro" onclick="probar(this)">Muro</button>'+(b.video?' <button data-b="'+b.id+'" data-m="reel" onclick="probar(this)">Video</button>':'')+'</td></tr>').join('')+'</table></div><p id="botres"></p></div>');
}
async function probar(el){
  const k=sessionStorage.getItem('k')||'';
  const p=document.getElementById('botres');p.textContent='Publicando...';
  try{
    const r=await fetch('/bots/ahora?bot='+el.dataset.b+'&modo='+el.dataset.m,{method:'POST',headers:{'x-clave':k}});
    const d=await r.json();
    p.textContent=(d.ok?'Listo: ':'Falló: ')+(d.nota||d.error||'');
    bots();
  }catch(e){p.textContent='Error: '+e}
}
if(sessionStorage.getItem('k'))go();
</script></body></html>`;


// ---- Backblaze B2 (opcional, 10 GB gratis): se activa con USAR_B2=1 y los secretos B2_KEY_ID, B2_KEY, B2_BUCKET ----
// Firma AWS SigV4 (B2 habla el protocolo S3). Sin USAR_B2 todo sigue en KV.
const _enc = new TextEncoder();
const _hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
async function _sha(d) { return _hex(await crypto.subtle.digest("SHA-256", typeof d === "string" ? _enc.encode(d) : d)); }
async function _hmac(k, d) {
  const key = await crypto.subtle.importKey("raw", typeof k === "string" ? _enc.encode(k) : k, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return crypto.subtle.sign("HMAC", key, _enc.encode(d));
}
async function sigv4(method, host, path, query, headers, payloadHash, amzdate, region, id, secret) {
  const dia = amzdate.slice(0, 8);
  const nombres = Object.keys(headers).map((h) => h.toLowerCase()).sort();
  const hl = {}; for (const k of Object.keys(headers)) hl[k.toLowerCase()] = String(headers[k]).trim();
  const canon = [method, path, query, nombres.map((n) => n + ":" + hl[n] + "\n").join(""), nombres.join(";"), payloadHash].join("\n");
  const alcance = dia + "/" + region + "/s3/aws4_request";
  const porFirmar = ["AWS4-HMAC-SHA256", amzdate, alcance, await _sha(canon)].join("\n");
  let k = await _hmac("AWS4" + secret, dia);
  k = await _hmac(k, region); k = await _hmac(k, "s3"); k = await _hmac(k, "aws4_request");
  const firma = _hex(await _hmac(k, porFirmar));
  return "AWS4-HMAC-SHA256 Credential=" + id + "/" + alcance + ", SignedHeaders=" + nombres.join(";") + ", Signature=" + firma;
}
const _t = (v) => String(v || "").trim();
function b2Activo(env) { return _t(env.USAR_B2) === "1" && _t(env.B2_KEY_ID) && _t(env.B2_KEY) && _t(env.B2_BUCKET); }
async function b2Fetch(env, method, clave, cuerpo) {
  const region = _t(env.B2_REGION) || "us-east-005";
  const host = "s3." + region + ".backblazeb2.com";
  const path = "/" + _t(env.B2_BUCKET) + "/" + clave.split("/").map(encodeURIComponent).join("/");
  const amz = new Date().toISOString().replace(/[:-]|\.\d{3}/g, "");
  const hash = cuerpo ? await _sha(cuerpo) : await _sha("");
  const h = { host, "x-amz-content-sha256": hash, "x-amz-date": amz };
  const auth = await sigv4(method, host, path, "", h, hash, amz, region, _t(env.B2_KEY_ID), _t(env.B2_KEY));
  return fetch("https://" + host + path, { method, headers: { "x-amz-content-sha256": hash, "x-amz-date": amz, authorization: auth }, body: cuerpo || undefined });
}
// ---- Videos de Reels en Workers KV (plan gratis, sin tarjeta): la app los sube en pedazos de 20 MiB ----
const VIDEO_HORAS = 12;
async function videoAuth(request) {
  const auth = request.headers.get("authorization") || "";
  const w = await fetch("https://matrix-client.matrix.org/_matrix/client/v3/account/whoami", { headers: { authorization: auth } });
  if (!w.ok) return null;
  return (await w.json()).user_id || "";
}
async function videoSubir(request, env, url) {
  if (!env.VIDEOS && !b2Activo(env)) return jsonCors({ error: "KV no esta conectado (falta el enlace VIDEOS)" }, 503);
  const m = /^\/v\/([a-f0-9]{32})\/(\d+|fin)$/.exec(url.pathname);
  if (!m) return jsonCors({ error: "Ruta no valida" }, 404);
  const quien = await videoAuth(request);
  if (quien === null) return jsonCors({ error: "Sesion no valida" }, 401);
  const horas = Math.min(168, Math.max(1, parseInt(url.searchParams.get("h") || String(VIDEO_HORAS), 10) || VIDEO_HORAS));
  const id = m[1], ttl = { expirationTtl: horas * 3600 };
  if (m[2] === "fin") {
    const n = parseInt(url.searchParams.get("n") || "0", 10);
    if (!n || n > 6) return jsonCors({ error: "Cantidad de pedazos no valida" }, 400);
    if (b2Activo(env)) {
      const r = await b2Fetch(env, "PUT", "v/" + id + "/m", JSON.stringify({ n, u: quien, e: Date.now() + horas * 3600000 }));
      if (!r.ok) return jsonCors({ error: "B2 manifiesto " + r.status }, 502);
    } else await env.VIDEOS.put("m:" + id, JSON.stringify({ n, u: quien }), ttl);
    return jsonCors({ id, url: url.origin + "/v/" + id + ".mp4" });
  }
  const i = parseInt(m[2], 10);
  if (i > 5) return jsonCors({ error: "Pedazo fuera de rango" }, 400);
  const buf = await request.arrayBuffer();
  if (!buf.byteLength || buf.byteLength > 25 * 1024 * 1024 - 1024) return jsonCors({ error: "Tamaño de pedazo no valido" }, 413);
  if (b2Activo(env)) {
    const r = await b2Fetch(env, "PUT", "v/" + id + "/" + i, buf);
    if (!r.ok) return jsonCors({ error: "B2 pedazo " + r.status }, 502);
  } else await env.VIDEOS.put("c:" + id + ":" + i, buf, ttl);
  return jsonCors({ ok: true, i });
}
async function videoVer(request, env, url) {
  if (!env.VIDEOS && !b2Activo(env)) return jsonCors({ error: "KV no conectado" }, 503);
  const m = /^\/v\/([a-f0-9]{32})\.mp4$/.exec(url.pathname);
  if (!m) return jsonCors({ error: "No encontrado" }, 404);
  let man = null, enB2 = false;
  if (b2Activo(env)) {
    const r = await b2Fetch(env, "GET", "v/" + m[1] + "/m");
    if (r.ok) { man = await r.json(); enB2 = true; if (man.e && man.e < Date.now()) man = null; }
  }
  if (!man && !enB2 && env.VIDEOS) man = await env.VIDEOS.get("m:" + m[1], "json");
  if (!man) return jsonCors({ error: "El video ya no esta disponible" }, 404);
  const h = new Headers(CORS);
  h.set("content-type", "video/mp4");
  h.set("cache-control", "public, max-age=3600");
  if (request.method === "HEAD") return new Response(null, { status: 200, headers: h });
  let i = 0, actual = null;
  const flujo = new ReadableStream({
    async pull(ctl) {
      try {
        for (;;) {
          if (!actual) {
            if (i >= man.n) { ctl.close(); return; }
            let s;
            if (enB2) { const r = await b2Fetch(env, "GET", "v/" + m[1] + "/" + i); s = r.ok ? r.body : null; }
            else s = await env.VIDEOS.get("c:" + m[1] + ":" + i, "stream");
            i++;
            if (!s) { ctl.error(new Error("falta un pedazo")); return; }
            actual = s.getReader();
          }
          const r = await actual.read();
          if (r.done) { actual = null; continue; }
          ctl.enqueue(r.value);
          return;
        }
      } catch (e) { ctl.error(e); }
    },
  });
  return new Response(flujo, { status: 200, headers: h });
}

// ───────────────────────── Bots de Chamos ─────────────────────────
// Cuentas temáticas (siempre marcadas como bot) que publican en el Muro y en Chamos Videos.
// Secretos: BOTS_ACTIVOS=1, BOTS_CUENTAS y PIXABAY_KEY o PEXELS_KEY (videos).
// Necesita un Cron Trigger de cada hora ("0 * * * *"). Límite duro: BOT_MAX_DIA publicaciones por bot y día.
const MS_API = "https://matrix-client.matrix.org/_matrix/client/v3";
const MS_MEDIA = "https://matrix-client.matrix.org/_matrix/media/v3";
const BOT_MURO_ALIAS = "#chamos-muro:matrix.org";
const BOT_REELS_ALIAS = "#chamos-reels:matrix.org";
const M_POST = "⁣";
const M_VIDEO = "⁥";
const UA_BOT = "ChamosBot/1.0 (https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/)";
const BOT_URL = "https://chamos-push.siervodeyhwh57.workers.dev";
const BOT_MAX_DIA = 8;
const BOT_MAX_FORZADO = 16;
const BOT_VIDEO_MAX = 12 * 1024 * 1024;
const BOT_VIDEO_HORAS = 48;

const BUSCAS = {
  belleza: ["makeup tutorial", "skincare routine", "hair care", "beauty tips"],
  comida: ["cooking food", "street food", "baking", "kitchen recipe"],
  lugares: ["travel landscape", "city travel", "beach island", "mountain nature"],
};
// Dos bots, varios temas: muro = { hora: tema }, reel = { hora: tema }.
const BOTS = {
  maria: { nombre: "María Chamos 🤖", muro: { 8: "comida", 9: "belleza", 11: "social", 16: "lugares", 19: "comida" }, reel: {} },
  baul: { nombre: "Chamo del Baúl 🤖", muro: { 7: "noticias", 12: "noticias", 14: "salsa", 17: "noticias", 20: "salsa" }, reel: {} },
};
function botTema(bot, modo) {
  const m = BOTS[bot][modo], { hora } = botHoy();
  if (m[hora]) return m[hora];
  const v = Object.values(m);
  return v[Math.floor(Math.random() * v.length)];
}

const BELLEZA = [
  "Protector solar todos los días, aunque esté nublado. Es lo que más ayuda a cuidar la piel con los años. Ponte una cantidad generosa en cara y cuello y repite cada 2 a 3 horas si estás al sol.",
  "Desmaquíllate siempre antes de dormir. Dormir con maquillaje tapa los poros y la piel no descansa. Si no tienes desmaquillante, un jabón suave y agua tibia también sirven.",
  "Bebe suficiente agua durante el día. La piel, el cabello y las uñas se ven mejor cuando estás bien hidratada.",
  "Para el cabello: lávalo con agua tibia, no muy caliente, y enjuágalo al final con agua fresca. Ayuda a que se vea con más brillo.",
  "No te toques la cara con las manos sucias. Es una de las causas más comunes de granitos. Lávate las manos antes de aplicarte cremas o maquillaje.",
  "Duerme entre 7 y 8 horas cuando puedas. El descanso se nota en la piel: menos ojeras y mejor color.",
  "Limpia tus brochas y esponjas de maquillaje una vez por semana con agua y jabón suave. Acumulan grasa y bacterias.",
  "Hidratante después del baño, con la piel todavía un poquito húmeda. Así retiene mejor la humedad.",
  "Cuida tus labios: usa bálsamo con protección solar y no te los muerdas. Si se resecan, bebe más agua.",
  "Las puntas abiertas no se arreglan con productos, solo se cortan. Un despunte cada 2 o 3 meses mantiene el cabello sano.",
  "Antes de usar un producto nuevo en la cara, pruébalo en una zona pequeña, como detrás de la oreja o en la muñeca, y espera 24 horas. Así evitas reacciones.",
  "Peina el cabello mojado con cuidado y con peine de dientes anchos. Mojado es cuando más se quiebra.",
  "El secador y la plancha dañan el cabello con el tiempo. Úsalos con protector térmico y no todos los días.",
  "No te exfolies la cara más de 1 o 2 veces por semana. Exfoliar de más irrita y la piel se pone sensible.",
  "Las uñas se fortalecen con buena alimentación y evitando que se mojen demasiado. Usa guantes para lavar los platos.",
  "Cambia la funda de tu almohada cada semana. Se acumula grasa y suciedad que pasa a tu cara y a tu cabello.",
  "Una rutina simple vale más que diez productos: limpiar, hidratar y proteger del sol. Lo importante es la constancia.",
  "Si tienes la piel grasosa, también necesita hidratante. Busca uno ligero, sin aceite, de textura gel.",
  "Los labiales y sombras tienen fecha de vencimiento. Si cambian de olor o de textura, es mejor botarlos.",
  "Cuando te maquilles, primero la piel limpia e hidratada. Una buena base de cuidado hace que el maquillaje dure más y se vea más natural.",
  "El estrés también se nota en la piel y en el cabello. Un rato al día de caminar, respirar o escuchar tu música ayuda más de lo que parece.",
  "Aplícate el protector solar también en las orejas, el cuello y las manos. Son zonas que casi todos olvidamos.",
  "Si te maquillas poco, cuida las cejas: peinarlas con un cepillito y rellenar los huecos cambia toda la cara.",
  "Evita compartir maquillaje, sobre todo el de ojos y labios. Es una forma fácil de pasar infecciones.",
];

const SALSA = [
  ["Celia Cruz, la Reina de la Salsa", "Con su grito de ¡Azúcar! la cubana Celia Cruz se convirtió en una de las voces más queridas de la música latina en todo el mundo.", "Celia Cruz Quimbara"],
  ["Héctor Lavoe, El Cantante", "El puertorriqueño Héctor Lavoe fue una de las voces más grandes de la salsa. Su tema El Cantante, escrito por Rubén Blades, sigue sonando en todas las fiestas.", "Hector Lavoe El Cantante"],
  ["Oscar D'León, el Sonero del Mundo", "El venezolano Oscar D'León es orgullo nacional: cantante, bajista y showman, uno de los grandes de la salsa. ¿Cuál es tu tema favorito de él?", "Oscar D'Leon Llorarás"],
  ["Fania All Stars", "La Fania All Stars reunió a las grandes figuras de la salsa de Nueva York en los años 60 y 70 y llevó este ritmo a escenarios de todo el mundo.", "Fania All Stars Quimbara"],
  ["Willie Colón y su trombón", "Willie Colón, trombonista y director de orquesta, junto a Héctor Lavoe hizo clásicos que todavía bailamos. Uno de los grandes de la salsa dura.", "Willie Colon Hector Lavoe Calle Luna Calle Sol"],
  ["Rubén Blades y Pedro Navaja", "Pedro Navaja, de Rubén Blades, cuenta una historia de barrio como una película. Una de las canciones más famosas de la salsa con letra.", "Ruben Blades Pedro Navaja"],
  ["Ismael Rivera, el Sonero Mayor", "El puertorriqueño Ismael Rivera es recordado como el Sonero Mayor, con un estilo y una improvisación únicos. Las caras lindas es de las más queridas.", "Ismael Rivera Las Caras Lindas"],
  ["Tito Puente, el Rey del Timbal", "Tito Puente fue percusionista y director de orquesta, y se le conoce como el Rey del Timbal. Su Oye cómo va se hizo famosa en todo el mundo.", "Tito Puente Oye Como Va"],
  ["Dimensión Latina", "Dimensión Latina, la orquesta caraqueña de la que salió Oscar D'León, llenó los años 70 de salsa venezolana con un sonido propio.", "Dimension Latina salsa"],
  ["Billo's Caracas Boys", "Billo Frómeta fundó en Caracas la orquesta Billo's Caracas Boys, que por décadas puso la música de las fiestas y de los diciembres en Venezuela.", "Billos Caracas Boys"],
  ["Cheo Feliciano", "El puertorriqueño Cheo Feliciano dejó clásicos como Anacaona. Voz suave, elegante y con mucho sabor.", "Cheo Feliciano Anacaona"],
  ["Johnny Pacheco", "El dominicano Johnny Pacheco fue músico y cofundador del sello Fania, clave para que la salsa se hiciera conocida en el mundo.", "Johnny Pacheco Celia Cruz Quimbara"],
  ["Eddie Palmieri", "El pianista Eddie Palmieri llevó la salsa al terreno del jazz y la innovación, con su orquesta La Perfecta.", "Eddie Palmieri salsa"],
  ["El Gran Combo de Puerto Rico", "Fundado en 1962 por Rafael Ithier, El Gran Combo es una de las orquestas más duraderas de la salsa. Ha puesto a bailar a varias generaciones.", "El Gran Combo de Puerto Rico"],
  ["Larry Harlow, el Judío Maravilloso", "El pianista y arreglista Larry Harlow fue una figura de la Fania y uno de los grandes de la salsa neoyorquina.", "Larry Harlow salsa"],
  ["Joe Arroyo, La Rebelión", "El colombiano Joe Arroyo dejó La Rebelión, una canción que cuenta una historia de lucha y que todavía se baila y se canta.", "Joe Arroyo La Rebelion"],
  ["Grupo Niche", "Desde Cali, el Grupo Niche le dio a Colombia un lugar propio en la salsa. Su Cali pachanguero es todo un himno.", "Grupo Niche Cali Pachanguero"],
  ["Gilberto Santa Rosa, el Caballero de la Salsa", "El puertorriqueño Gilberto Santa Rosa es conocido como el Caballero de la Salsa por su elegancia y su voz.", "Gilberto Santa Rosa"],
  ["¿Qué es la clave?", "La clave es el patrón rítmico que ordena toda la salsa: de ahí salen el baile, la percusión y los cortes. Es el corazón del ritmo.", "clave ritmo salsa explicacion"],
  ["Los instrumentos de la salsa", "Congas, timbales, bongó, piano, bajo y una sección de metales: así suena una orquesta de salsa. Cada instrumento cuenta su parte.", "orquesta de salsa instrumentos"],
  ["¿De dónde viene la salsa?", "La salsa nació mezclando el son cubano, la guaracha, el mambo y el jazz, sobre todo en Nueva York en los años 60 y 70, con músicos de Puerto Rico, Cuba y toda Latinoamérica.", "historia de la salsa documental"],
  ["Pregunta para el baúl", "Comparte con tu gente: ¿cuál fue la primera salsa que bailaste? Cuéntanos en los comentarios.", "salsa clasica para bailar"],
];

const SOCIAL = [
  "Contra el dengue: elimina el agua estancada. Tapa tanques y baldes, cambia el agua de floreros y bebederos de mascotas, y bota recipientes que acumulen lluvia. El zancudo se cría en agua quieta.",
  "Lávate las manos con agua y jabón por al menos 20 segundos, antes de comer y después de ir al baño. Es una de las formas más simples de evitar enfermedades.",
  "Hidrátate sobre todo en días de calor. Con mucho calor, los niños y los adultos mayores necesitan tomar agua aunque no sientan sed.",
  "Si alguien se desmaya y no respira, pide ayuda de inmediato a los servicios de emergencia y a quien tengas cerca. Una persona entrenada puede dar compresiones en el pecho mientras llega la ayuda.",
  "En la calle y en el transporte: guarda tu teléfono mientras caminas, avisa por mensaje a alguien de confianza cuando llegues y evita mostrar objetos de valor.",
  "Los adultos mayores también son chamos. Llama o visita hoy a un abuelo, vecino o familiar que viva solo. Un rato de compañía vale mucho.",
  "Antes de compartir un mensaje de cadena, verifica. Mira quién lo dice, la fecha y si otras fuentes confiables lo confirman. Compartir noticias falsas hace daño.",
  "Si tienes ropa o útiles que ya no usas y están en buen estado, regálalos a una familia cercana que los necesite. La solidaridad empieza en el barrio.",
  "Ahorrar agua en casa: cierra la llave mientras te cepillas los dientes y arregla las fugas. Pequeños hábitos hacen una gran diferencia.",
  "Cruza siempre por las esquinas y mira a ambos lados. Si manejas, el cinturón de seguridad y el casco en moto salvan vidas.",
  "Cuida tu salud mental: hablar con alguien de confianza, descansar y pedir ayuda no es debilidad. Si te sientes mal por mucho tiempo, busca apoyo de un profesional de salud.",
  "Revisa que tus vacunas y las de tus hijos estén al día y pregunta en tu centro de salud más cercano por las jornadas gratuitas.",
  "Guarda los medicamentos fuera del alcance de los niños y revisa las fechas de vencimiento. Nunca tomes medicinas sin indicación de un médico o farmacéutico.",
  "En época de lluvias: evita cruzar ríos o quebradas crecidas y no toques cables caídos. Pon a salvo a tu familia primero.",
  "Un gesto de amabilidad cambia el día de alguien. Saluda a tu vecino, ofrece tu puesto a quien lo necesite, da las gracias. Eso también construye país.",
  "Lleva siempre contigo los datos de una persona de confianza a quien llamar en caso de emergencia, anotados fuera del teléfono por si se queda sin batería.",
  "Separar la basura y no tirarla en la calle ayuda a evitar inundaciones y plagas. Cuidar la ciudad es cuidarnos entre todos.",
  "Si eres víctima de maltrato o conoces a alguien que lo sea, no estás solo. Busca a un familiar o amigo de confianza y a las organizaciones de protección de tu zona.",
];
const SOCIAL_PIE = "\n\nInformación general, no reemplaza a un profesional. En una emergencia busca ayuda médica o de las autoridades.";

// Páginas de Wikipedia en español: se leen en el momento (texto + foto libre). Si alguna no existe, se salta.
const COMIDAS = [
  "Arepa", "Pabellón_criollo", "Hallaca", "Cachapa", "Tequeño", "Empanada", "Asado_negro", "Pan_de_jamón", "Mondongo", "Sancocho",
  "Tres_leches", "Quesillo", "Bienmesabe", "Dulce_de_lechosa", "Mandoca", "Casabe", "Huevos_pericos", "Chicha_(bebida)", "Hervido",
  "Paella", "Ceviche", "Taco", "Pizza", "Lasaña", "Churro", "Empanadilla", "Tortilla_de_patatas", "Gazpacho", "Sushi", "Feijoada",
];
const LUGARES = [
  "Salto_Ángel", "Parque_nacional_Canaima", "Archipiélago_Los_Roques", "Parque_nacional_Morrocoy", "Mérida_(Venezuela)", "Choroní",
  "Machu_Picchu", "Chichén_Itzá", "Cristo_Redentor_(Río_de_Janeiro)", "Cataratas_del_Iguazú", "Torre_Eiffel", "Coliseo_de_Roma",
  "Taj_Mahal", "Gran_Muralla_China", "Petra_(Jordania)", "Pirámides_de_Guiza", "Islas_Galápagos", "Salar_de_Uyuni", "Cartagena_de_Indias",
  "Cusco", "Santorini", "Venecia", "Kioto", "Isla_de_Pascua", "Torres_del_Paine", "Glaciar_Perito_Moreno",
  "Angkor_Wat", "Alhambra", "Sagrada_Familia", "Gran_Cañón_del_Colorado", "Cataratas_del_Niágara"
];

const _bz = (s) => String(s || "").replace(/\|/g, "/").trim();

async function botsTablas(env) {
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS bots_log (id INTEGER PRIMARY KEY AUTOINCREMENT, bot TEXT, ts INTEGER, dia TEXT, hora INTEGER, modo TEXT, clave TEXT, ok INTEGER, nota TEXT)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS bots_tok (bot TEXT PRIMARY KEY, token TEXT, user_id TEXT, muro TEXT, reels TEXT)"),
  ]);
}
function botCuentas(env) {
  const r = {};
  for (const e of String(env.BOTS_CUENTAS || "").split(";")) {
    const p = e.trim().split(/\s+/);
    if (p.length >= 3 && BOTS[p[0]]) r[p[0]] = { u: p[1].replace(/^@/, "").split(":")[0], p: p.slice(2).join(" ") };
  }
  return r;
}
function botHoy() {
  const d = new Date(Date.now() - 4 * 3600 * 1000);
  return { dia: d.toISOString().slice(0, 10), hora: d.getUTCHours() };
}
async function botSesion(env, bot, cuentas, renovar) {
  let fila = await env.DB.prepare("SELECT * FROM bots_tok WHERE bot=?1").bind(bot).first();
  if (fila && fila.token && !renovar) return fila;
  const c = cuentas[bot];
  if (!c) throw new Error("Falta la cuenta de " + bot + " en BOTS_CUENTAS");
  const r = await fetch(MS_API + "/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ type: "m.login.password", identifier: { type: "m.id.user", user: c.u }, password: c.p, initial_device_display_name: "Chamos bot" }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error("Login " + bot + ": " + (j.error || r.status));
  await env.DB.prepare("INSERT INTO bots_tok (bot, token, user_id, muro, reels) VALUES (?1,?2,?3,'','') ON CONFLICT(bot) DO UPDATE SET token=?2, user_id=?3, muro='', reels=''")
    .bind(bot, j.access_token, j.user_id).run();
  try {
    await fetch(MS_API + "/profile/" + encodeURIComponent(j.user_id) + "/displayname", {
      method: "PUT",
      headers: { authorization: "Bearer " + j.access_token, "content-type": "application/json" },
      body: JSON.stringify({ displayname: BOTS[bot].nombre }),
    });
  } catch (_) {}
  return await env.DB.prepare("SELECT * FROM bots_tok WHERE bot=?1").bind(bot).first();
}
// Llamada a Matrix con el token del bot; si el token ya no sirve, vuelve a entrar una vez.
async function botMatrix(env, bot, cuentas, metodo, ruta, cuerpo, tipo, base) {
  for (let intento = 0; intento < 2; intento++) {
    const ses = await botSesion(env, bot, cuentas, intento > 0);
    const h = { authorization: "Bearer " + ses.token };
    let body;
    if (cuerpo instanceof ArrayBuffer) { h["content-type"] = tipo || "application/octet-stream"; body = cuerpo; }
    else if (cuerpo !== undefined) { h["content-type"] = "application/json"; body = JSON.stringify(cuerpo); }
    const r = await fetch((base || MS_API) + ruta, { method: metodo, headers: h, body });
    if (r.status === 401 && intento === 0) continue;
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error("Matrix " + r.status + " " + (j.errcode || "") + " " + (j.error || ""));
    return j;
  }
  throw new Error("Matrix: no se pudo entrar");
}
async function botSala(env, bot, cuentas, cual) {
  const ses = await botSesion(env, bot, cuentas, false);
  if (ses[cual]) return ses[cual];
  const alias = cual === "muro" ? BOT_MURO_ALIAS : BOT_REELS_ALIAS;
  const j = await botMatrix(env, bot, cuentas, "POST", "/join/" + encodeURIComponent(alias), {});
  const id = j.room_id;
  if (!id) throw new Error("No pude entrar a " + alias);
  await env.DB.prepare("UPDATE bots_tok SET " + cual + "=?1 WHERE bot=?2").bind(id, bot).run();
  return id;
}
const botTxn = () => "b" + Date.now() + Math.random().toString(36).slice(2, 8);
async function botEnviar(env, bot, cuentas, sala, contenido) {
  const j = await botMatrix(env, bot, cuentas, "PUT", "/rooms/" + encodeURIComponent(sala) + "/send/m.room.message/" + botTxn(), contenido);
  return j.event_id;
}
async function botMuroTexto(env, bot, cuentas, texto) {
  const sala = await botSala(env, bot, cuentas, "muro");
  return botEnviar(env, bot, cuentas, sala, { msgtype: "m.text", body: M_POST + "P|" + _bz(texto) });
}
async function botMuroFoto(env, bot, cuentas, texto, fotoUrl) {
  const f = await fetch(fotoUrl, { headers: { "user-agent": UA_BOT } });
  if (!f.ok) throw new Error("Foto " + f.status);
  const buf = await f.arrayBuffer();
  if (!buf.byteLength || buf.byteLength > 600 * 1024) throw new Error("Foto muy pesada (" + buf.byteLength + ")");
  const mime = (f.headers.get("content-type") || "image/jpeg").split(";")[0];
  const nombre = "foto." + (mime.includes("png") ? "png" : mime.includes("webp") ? "webp" : "jpg");
  const up = await botMatrix(env, bot, cuentas, "POST", "/upload?filename=" + nombre, buf, mime, MS_MEDIA);
  const sala = await botSala(env, bot, cuentas, "muro");
  return botEnviar(env, bot, cuentas, sala, {
    msgtype: "m.image", body: M_POST + "P|" + _bz(texto), filename: nombre, url: up.content_uri, info: { mimetype: mime, size: buf.byteLength },
  });
}

// Una página de Wikipedia: título, texto corto, foto y enlace. null si no sirve.
// Foto libre de Pixabay para el muro (usa PIXABAY_KEY; si no hay o falla, se publica solo el texto).
async function botFotoPixabay(env, consultas) {
  if (!env.PIXABAY_KEY) return null;
  const q = consultas[Math.floor(Math.random() * consultas.length)];
  const r = await fetch("https://pixabay.com/api/?key=" + encodeURIComponent(String(env.PIXABAY_KEY).trim()) + "&image_type=photo&orientation=horizontal&safesearch=true&per_page=40&q=" + encodeURIComponent(q), { headers: { "user-agent": UA_BOT } });
  if (!r.ok) return null;
  const j = await r.json().catch(() => null);
  const h = j && j.hits && j.hits.length ? j.hits[Math.floor(Math.random() * j.hits.length)] : null;
  return h && h.webformatURL ? { url: h.webformatURL, autor: h.user } : null;
}
async function botPublicar(env, bot, cuentas, texto, foto) {
  if (foto) {
    try { await botMuroFoto(env, bot, cuentas, texto, foto); return "foto"; }
    catch (e) { await botMuroTexto(env, bot, cuentas, texto); return "texto (foto fallo: " + e.message + ")"; }
  }
  await botMuroTexto(env, bot, cuentas, texto);
  return "texto";
}
async function wikiResumen(titulo) {
  const r = await fetch("https://es.wikipedia.org/api/rest_v1/page/summary/" + encodeURIComponent(titulo), { headers: { "user-agent": UA_BOT, accept: "application/json" } });
  if (!r.ok) return null;
  const j = await r.json().catch(() => null);
  if (!j || j.type !== "standard" || !j.extract) return null;
  let t = String(j.extract).replace(/\s*\([^)]*\)/g, "");
  const frases = t.split(/(?<=[.!?])\s+/);
  t = "";
  for (const fr of frases) { if ((t + " " + fr).length > 380) break; t = (t + " " + fr).trim(); }
  if (t.length < 40) t = String(j.extract).slice(0, 380);
  return {
    titulo: j.title, texto: t,
    foto: j.thumbnail && j.thumbnail.source ? j.thumbnail.source : "",
    enlace: j.content_urls && j.content_urls.desktop ? j.content_urls.desktop.page : "",
  };
}

// Elige algo que no se haya publicado hace poco: recorre todo el banco antes de repetir.
async function botElegir(env, bot, modo, claves) {
  const usadas = new Set(
    ((await env.DB.prepare("SELECT clave FROM bots_log WHERE bot=?1 AND modo=?2 AND ok=1 ORDER BY id DESC LIMIT ?3").bind(bot, modo, Math.max(1, claves.length - 1)).all()).results || []).map((x) => x.clave),
  );
  const libres = claves.filter((c) => !usadas.has(c));
  const lista = libres.length ? libres : claves;
  return lista[Math.floor(Math.random() * lista.length)];
}
const yt = (q) => "https://www.youtube.com/results?search_query=" + encodeURIComponent(q);

// Arma y publica una entrada en el Muro. Devuelve { clave, nota }.
async function botHacerMuro(env, bot, cuentas) {
  const b = { ...BOTS[bot], tema: botTema(bot, "muro") };
  if (b.tema === "belleza") {
    const i = await botElegir(env, bot, "muro", BELLEZA.map((_, k) => "b" + k));
    const f = await botFotoPixabay(env, ["skincare", "makeup", "beauty hair", "cosmetics spa"]).catch(() => null);
    const texto = "💄 Consejo de belleza\n\n" + BELLEZA[parseInt(i.slice(1), 10)] + (f ? "\n\nFoto: " + f.autor + " (Pixabay)" : "") + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    return { clave: i, nota: await botPublicar(env, bot, cuentas, texto, f && f.url) };
  }
  if (b.tema === "salsa") {
    const i = await botElegir(env, bot, "muro", SALSA.map((_, k) => "s" + k));
    const s = SALSA[parseInt(i.slice(1), 10)];
    const texto = "🎺 " + s[0] + "\n\n" + s[1] + "\n\n🎧 Escúchala aquí: " + yt(s[2]) + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    const w = await wikiResumen(s[0].split(",")[0]).catch(() => null);
    return { clave: i, nota: await botPublicar(env, bot, cuentas, texto + (w && w.foto ? "\nFoto: Wikipedia (licencia libre)" : ""), w && w.foto) };
  }
  if (b.tema === "social") {
    const i = await botElegir(env, bot, "muro", SOCIAL.map((_, k) => "c" + k));
    const texto = "🤝 Para cuidarnos entre todos\n\n" + SOCIAL[parseInt(i.slice(1), 10)] + SOCIAL_PIE;
    const f = await botFotoPixabay(env, ["friends helping", "community support", "volunteers", "family together"]).catch(() => null);
    const texto2 = texto + (f ? "\n\nFoto: " + f.autor + " (Pixabay)" : "") + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    return { clave: i, nota: await botPublicar(env, bot, cuentas, texto2, f && f.url) };
  }
  if (b.tema === "noticias") return botHacerNoticias(env, bot, cuentas);
  // comida y lugares: Wikipedia (texto y foto libres)
  const lista = b.tema === "comida" ? COMIDAS : LUGARES;
  const emoji = b.tema === "comida" ? "🍽️" : "🌎";
  const etiqueta = b.tema === "comida" ? "Plato del día" : "Lugar del mundo";
  let intentos = 0, ultimo = "";
  while (intentos < 4) {
    intentos++;
    const t = await botElegir(env, bot, "muro", lista);
    const w = await wikiResumen(t);
    if (!w) { await env.DB.prepare("INSERT INTO bots_log (bot, ts, dia, hora, modo, clave, ok, nota) VALUES (?1,?2,'',0,'muro',?3,1,'pagina no valida')").bind(bot, Date.now(), t).run(); ultimo = t; continue; }
    const texto = emoji + " " + etiqueta + ": " + w.titulo + "\n\n" + w.texto + (w.enlace ? "\n\nMás en: " + w.enlace : "") + "\nFoto y texto: Wikipedia (licencia libre)\n\n— " + b.nombre + " · cuenta automática de Chamos";
    let nota = "texto";
    if (w.foto) { try { await botMuroFoto(env, bot, cuentas, texto, w.foto); nota = "foto"; } catch (e) { await botMuroTexto(env, bot, cuentas, texto); nota = "texto (foto fallo: " + e.message + ")"; } }
    else await botMuroTexto(env, bot, cuentas, texto);
    return { clave: t, nota };
  }
  throw new Error("No encontré una página válida (último: " + ultimo + ")");
}

// Noticias: solo titular + fuente + enlace (Google Noticias, secciones sin política) y, por la mañana, el dólar BCV.
const NOTICIAS_TEMAS = {
  BUSINESS: "Economía y negocios", SPORTS: "Deportes", TECHNOLOGY: "Tecnología", SCIENCE: "Ciencia", HEALTH: "Salud", ENTERTAINMENT: "Espectáculos",
};
const _xml = (t) => String(t || "").replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&").trim();
async function botTasaBCV() {
  try {
    const t = await tasaBCV();
    if (!t) return "";
    const f = t.fecha && /^\d{4}-\d{2}-\d{2}$/.test(t.fecha) ? " (valor del " + t.fecha.slice(8, 10) + "/" + t.fecha.slice(5, 7) + ")" : "";
    return "💵 Dólar BCV: Bs. " + t.usd.toFixed(2).replace(".", ",") + f + "\n\n";
  } catch (_) { return ""; }
}
async function botHacerNoticias(env, bot, cuentas) {
  const { hora } = botHoy();
  const mapa = { 7: ["BUSINESS"], 12: ["SPORTS"], 17: ["TECHNOLOGY", "SCIENCE", "HEALTH", "ENTERTAINMENT"] };
  const temas = mapa[hora] || Object.keys(NOTICIAS_TEMAS);
  const tema = temas[Math.floor(Math.random() * temas.length)];
  const r = await fetch("https://news.google.com/rss/headlines/section/topic/" + tema + "?hl=es-419&gl=VE&ceid=VE:es-419", { headers: { "user-agent": UA_BOT } });
  if (!r.ok) throw new Error("Noticias " + r.status);
  const xml = await r.text();
  const items = [];
  for (const m of xml.matchAll(/<item>([\s\S]*?)<\/item>/g)) {
    const t = /<title>([\s\S]*?)<\/title>/.exec(m[1]);
    const l = /<link>([\s\S]*?)<\/link>/.exec(m[1]);
    if (t && l) items.push({ titulo: _xml(t[1]), enlace: _xml(l[1]) });
    if (items.length >= 15) break;
  }
  const usadas = new Set(
    ((await env.DB.prepare("SELECT clave FROM bots_log WHERE bot=?1 AND modo='muro' AND ok=1 ORDER BY id DESC LIMIT 150").bind(bot).all()).results || []).map((x) => x.clave),
  );
  const nuevas = items.filter((i) => !usadas.has(i.titulo.slice(0, 120))).slice(0, 3);
  if (!nuevas.length) throw new Error("No hay noticias nuevas en " + tema);
  const tasa = hora <= 9 ? await botTasaBCV() : "";
  const texto = "📰 " + NOTICIAS_TEMAS[tema] + "\n\n" + tasa + nuevas.map((i) => "• " + i.titulo + "\n" + i.enlace).join("\n\n") + "\n\nTitulares de Google Noticias. Toca el enlace para leer la nota completa en la fuente.\n— " + BOTS[bot].nombre + " · cuenta automática de Chamos";
  await botMuroTexto(env, bot, cuentas, texto);
  // Se guardan los titulares ya publicados para no repetirlos.
  for (const i of nuevas.slice(1)) await env.DB.prepare("INSERT INTO bots_log (bot, ts, dia, hora, modo, clave, ok, nota) VALUES (?1,?2,'',0,'muro',?3,1,'pagina no valida')").bind(bot, Date.now(), i.titulo.slice(0, 120)).run();
  return { clave: nuevas[0].titulo.slice(0, 120), nota: NOTICIAS_TEMAS[tema] + " · " + nuevas.length + " titulares" };
}

// Video de Pixabay o Pexels (licencia libre) → Backblaze/KV → Chamos Videos.
// Pixabay: PIXABAY_KEY (clave gratis al registrarse). Pexels: PEXELS_KEY (ahora pausa nuevas claves). Se usa la que esté.
async function botBuscarVideo(env, q, vistos) {
  const ua = { "user-agent": UA_BOT };
  if (env.PIXABAY_KEY) {
    const r = await fetch("https://pixabay.com/api/videos/?key=" + encodeURIComponent(String(env.PIXABAY_KEY).trim()) + "&safesearch=true&per_page=30&q=" + encodeURIComponent(q), { headers: ua });
    if (!r.ok) throw new Error("Pixabay " + r.status);
    const j = await r.json();
    const lista = [];
    for (const h of j.hits || []) {
      if (!h.duration || h.duration > 45 || vistos.has("x" + h.id)) continue;
      const opc = ["medium", "small", "tiny"].map((k) => h.videos && h.videos[k]).filter((v) => v && v.url && v.size && v.size <= BOT_VIDEO_MAX && v.width <= 1100);
      if (opc.length) lista.push({ clave: "x" + h.id, url: opc[0].url, autor: h.user, fuente: "Pixabay" });
    }
    return lista;
  }
  if (env.PEXELS_KEY) {
    const r = await fetch("https://api.pexels.com/videos/search?per_page=20&orientation=portrait&query=" + encodeURIComponent(q), { headers: { authorization: String(env.PEXELS_KEY).trim(), ...ua } });
    if (!r.ok) throw new Error("Pexels " + r.status);
    const j = await r.json();
    const lista = [];
    for (const v of j.videos || []) {
      if (v.duration > 45 || vistos.has("p" + v.id)) continue;
      const f = (v.video_files || []).filter((x) => x.file_type === "video/mp4" && x.width && x.width <= 1100).sort((x, y) => y.width - x.width)[0];
      if (f) lista.push({ clave: "p" + v.id, url: f.link, autor: v.user && v.user.name, fuente: "Pexels" });
    }
    return lista;
  }
  throw new Error("Falta el secreto PIXABAY_KEY (o PEXELS_KEY)");
}
async function botHacerReel(env, bot, cuentas) {
  const tema = Object.keys(BOTS[bot].reel).length ? botTema(bot, "reel") : "";
  const b = { ...BOTS[bot], tema, busca: BUSCAS[tema] || [] };
  if (!b.busca.length) throw new Error("Este bot no publica videos");
  const q = b.busca[Math.floor(Math.random() * b.busca.length)];
  const vistos = new Set(
    ((await env.DB.prepare("SELECT clave FROM bots_log WHERE bot=?1 AND modo='reel' AND ok=1 ORDER BY id DESC LIMIT 200").bind(bot).all()).results || []).map((x) => x.clave),
  );
  const cand = await botBuscarVideo(env, q, vistos);
  if (!cand.length) throw new Error("No hay videos nuevos que quepan para '" + q + "'");
  const v = cand[Math.floor(Math.random() * cand.length)];
  const d = await fetch(v.url, { headers: { "user-agent": UA_BOT } });
  if (!d.ok) throw new Error("Descarga del video " + d.status);
  const largo = parseInt(d.headers.get("content-length") || "0", 10);
  if (largo && largo > BOT_VIDEO_MAX) throw new Error("Video muy pesado (" + largo + ")");
  const buf = await d.arrayBuffer();
  if (!buf.byteLength || buf.byteLength > BOT_VIDEO_MAX) throw new Error("Video muy pesado o vacío (" + buf.byteLength + ")");
  const ses = await botSesion(env, bot, cuentas, false);
  const id = [...crypto.getRandomValues(new Uint8Array(16))].map((x) => x.toString(16).padStart(2, "0")).join("");
  const exp = Date.now() + BOT_VIDEO_HORAS * 3600000;
  if (b2Activo(env)) {
    const a = await b2Fetch(env, "PUT", "v/" + id + "/0", buf);
    if (!a.ok) throw new Error("B2 pedazo " + a.status);
    const m = await b2Fetch(env, "PUT", "v/" + id + "/m", JSON.stringify({ n: 1, u: ses.user_id, e: exp }));
    if (!m.ok) throw new Error("B2 manifiesto " + m.status);
  } else if (env.VIDEOS) {
    const ttl = { expirationTtl: BOT_VIDEO_HORAS * 3600 };
    await env.VIDEOS.put("c:" + id + ":0", buf, ttl);
    await env.VIDEOS.put("m:" + id, JSON.stringify({ n: 1, u: ses.user_id }), ttl);
  } else throw new Error("No hay dónde guardar el video (ni B2 ni KV)");
  const enlace = BOT_URL + "/v/" + id + ".mp4";
  const emoji = b.tema === "belleza" ? "💅" : b.tema === "comida" ? "🍳" : "✈️";
  const desc = emoji + " " + (v.autor ? "Video de " + v.autor + " · " : "") + v.fuente + " · " + b.nombre + " · cuenta automática de Chamos";
  const sala = await botSala(env, bot, cuentas, "reels");
  await botEnviar(env, bot, cuentas, sala, { msgtype: "m.text", body: M_VIDEO + enlace + "\n" + desc });
  return { clave: v.clave, nota: Math.round(buf.byteLength / 1024) + " KB · " + q + " · " + v.fuente };
}

async function botEjecutar(env, bot, modo, forzar) {
  await botsTablas(env);
  const cuentas = botCuentas(env);
  const { dia, hora } = botHoy();
  const hoy = (await env.DB.prepare("SELECT COUNT(*) AS n FROM bots_log WHERE bot=?1 AND dia=?2 AND ok=1 AND hora>=0 AND nota!='pagina no valida'").bind(bot, dia).first()).n;
  if (hoy >= (forzar ? BOT_MAX_FORZADO : BOT_MAX_DIA)) return { ok: false, nota: "Límite diario alcanzado (" + hoy + ")" };
  let res, err = "";
  try {
    res = modo === "reel" ? await botHacerReel(env, bot, cuentas) : await botHacerMuro(env, bot, cuentas);
  } catch (e) {
    err = String(e.message || e);
  }
  await env.DB.prepare("INSERT INTO bots_log (bot, ts, dia, hora, modo, clave, ok, nota) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)")
    .bind(bot, Date.now(), dia, hora, modo, res ? res.clave : "", res ? 1 : 0, (res ? res.nota : err).slice(0, 300)).run();
  return res ? { ok: true, nota: res.nota, clave: res.clave } : { ok: false, nota: err };
}

// Cada hora: publica lo que toque según los horarios de cada bot (hora de Caracas).
async function botsCron(env) {
  if (!env.DB || String(env.BOTS_ACTIVOS || "").trim() !== "1") return;
  await botsTablas(env);
  const cuentas = botCuentas(env);
  const { dia, hora } = botHoy();
  for (const bot of Object.keys(BOTS)) {
    if (!cuentas[bot]) continue;
    for (const modo of ["muro", "reel"]) {
      const toca = modo === "muro" ? BOTS[bot].muro[hora] : BOTS[bot].reel[hora];
      if (!toca) continue;
      const ya = await env.DB.prepare("SELECT COUNT(*) AS n FROM bots_log WHERE bot=?1 AND dia=?2 AND hora=?3 AND modo=?4").bind(bot, dia, hora, modo).first();
      if (ya.n > 0) continue;
      try { await botEjecutar(env, bot, modo, false); } catch (e) { console.error("bot", bot, e); }
    }
  }
}

function botsAdmin(request, env) {
  return !!env.ADMIN_KEY && request.headers.get("x-clave") === env.ADMIN_KEY;
}
async function botsEstado(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  if (!botsAdmin(request, env)) return jsonCors({ error: "Clave incorrecta" }, 401);
  await botsTablas(env);
  const cuentas = botCuentas(env);
  const { dia } = botHoy();
  const bots = [];
  for (const id of Object.keys(BOTS)) {
    const hoy = (await env.DB.prepare("SELECT COUNT(*) AS n FROM bots_log WHERE bot=?1 AND dia=?2 AND ok=1").bind(id, dia).first()).n;
    const ult = (await env.DB.prepare("SELECT ts, modo, ok, nota FROM bots_log WHERE bot=?1 AND nota!='pagina no valida' ORDER BY id DESC LIMIT 3").bind(id).all()).results || [];
    bots.push({ id, nombre: BOTS[id].nombre, cuenta: !!cuentas[id], video: Object.keys(BOTS[id].reel).length > 0, hoy, ultimos: ult });
  }
  return jsonCors({ activos: String(env.BOTS_ACTIVOS || "").trim() === "1", videos: env.PIXABAY_KEY ? "Pixabay" : env.PEXELS_KEY ? "Pexels" : "", b2: b2Activo(env), bots });
}
async function botsAhora(request, env, url) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  if (!botsAdmin(request, env)) return jsonCors({ error: "Clave incorrecta" }, 401);
  const bot = url.searchParams.get("bot") || "";
  const modo = url.searchParams.get("modo") === "reel" ? "reel" : "muro";
  if (!BOTS[bot]) return jsonCors({ error: "Bot desconocido" }, 400);
  return jsonCors(await botEjecutar(env, bot, modo, true));
}


// Tasa oficial del BCV: se lee de bcv.org.ve (lo mismo que ve la gente en su página) y, si no se puede, de DolarAPI.
// Se guarda 5 minutos en la caché del Worker, así la app y los bots muestran lo mismo y se actualiza solo.
const _num = (t) => {
  const n = parseFloat(String(t).trim().replace(/\.(?=\d{3}\b)/g, "").replace(",", "."));
  return isFinite(n) ? n : 0;
};
async function tasaBCV() {
  const cache = caches.default;
  const llave = new Request("https://cache.chamos/tasa-bcv-v1");
  const guardada = await cache.match(llave);
  if (guardada) return await guardada.json();
  let out = null;
  try {
    const r = await fetch("https://www.bcv.org.ve/", { headers: { "user-agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36", accept: "text/html" } });
    if (r.ok) {
      const h = await r.text();
      const pos = (id) => { const i = h.search(new RegExp('id="' + id + '"', "i")); return i < 0 ? "" : h.slice(i, i + 900); };
      const num = (id) => { const m = /(\d{1,3}(?:\.\d{3})*,\d{2,8})/.exec(pos(id)); return m ? _num(m[1]) : 0; };
      const usd = num("dolar");
      if (usd > 1 && usd < 1e7) {
        const eur = num("euro");
        const f = /date-display-single[^>]*content="(\d{4}-\d{2}-\d{2})/i.exec(h) || /Fecha\s*Valor[^0-9]{0,80}(\d{1,2}\s+\w+\s+\d{4})/i.exec(h);
        out = { usd, eur: eur > 1 ? eur : null, fecha: f ? f[1] : null, fuente: "BCV" };
      }
    }
  } catch (_) {}
  if (!out) {
    try {
      const r = await fetch("https://ve.dolarapi.com/v1/dolares/oficial", { headers: { "user-agent": UA_BOT } });
      if (r.ok) {
        const j = await r.json();
        const v = Number(j.promedio || j.venta || j.compra);
        if (v > 1) out = { usd: v, eur: null, fecha: j.fechaActualizacion ? String(j.fechaActualizacion).slice(0, 10) : null, fuente: "DolarAPI" };
      }
    } catch (_) {}
  }
  if (out) await cache.put(llave, new Response(JSON.stringify(out), { headers: { "cache-control": "max-age=300", "content-type": "application/json" } }));
  return out;
}

// Cierre de las versiones antiguas (debug): con el secreto SOLO_RELEASE=1 las llamadas de la app que no traen
// la marca x-chamos-app: com.chamos.app se rechazan. La web, los bots y la reproducción de videos no se tocan.
const CERRADA = { error: "Esta versión de Chamos ya no tiene servicio. Instala la nueva Chamos." };
function soloRelease(request, env, url) {
  if (String(env.SOLO_RELEASE || "").trim() !== "1") return false;
  const p = url.pathname, m = request.method;
  const deApp = (p.startsWith("/v/") && m === "POST") || p === "/seguir" || p.startsWith("/video/") || p.startsWith("/estado/");
  return deApp && request.headers.get("x-chamos-app") !== "com.chamos.app";
}

// Registro de la página web: nombre + correo para poder descargar la app. El enlace de descarga pasa por aquí
// (firmado y con vencimiento) y el Worker entrega el APK de GitHub. Secreto: WEB_SECRET (o ADMIN_KEY si no existe).
const APK_URL = "https://github.com/siervobenshalyahu-ship-it/Chamos/releases/latest/download/Chamos.apk";
const _hexW = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
async function webFirma(env, texto) {
  const k = env.WEB_SECRET || env.ADMIN_KEY;
  if (!k) return null;
  return _hexW(await _hmac(k, texto));
}
const DESECHABLES = new Set(["mailinator.com","guerrillamail.com","10minutemail.com","tempmail.com","temp-mail.org","yopmail.com","trashmail.com","sharklasers.com","getnada.com","dispostable.com","maildrop.cc","throwawaymail.com","fakeinbox.com","mohmal.com","emailondeck.com","moakt.com","tempail.com","mintemail.com","spamgourmet.com","example.com","test.com"]);
const ERRATAS = { "gmial.com": "gmail.com", "gmai.com": "gmail.com", "gamil.com": "gmail.com", "gmail.con": "gmail.com", "gmail.co": "gmail.com", "gmail.cm": "gmail.com", "hotmial.com": "hotmail.com", "hotmail.con": "hotmail.com", "hotmai.com": "hotmail.com", "yahooo.com": "yahoo.com", "yaho.com": "yahoo.com", "yahoo.con": "yahoo.com", "outlok.com": "outlook.com", "outlook.con": "outlook.com" };
// Revisa que el dominio del correo exista y reciba correo (registro MX o A) usando DNS de Cloudflare.
async function dominioRecibeCorreo(dom) {
  const pedir = async (tipo) => {
    const r = await fetch("https://cloudflare-dns.com/dns-query?name=" + encodeURIComponent(dom) + "&type=" + tipo, { headers: { accept: "application/dns-json" } });
    if (!r.ok) throw new Error("dns");
    return r.json();
  };
  try {
    const mx = await pedir("MX");
    if (mx.Answer && mx.Answer.some((a) => a.type === 15 && a.data && a.data.trim() !== "0 .")) return true;
    if (mx.Status === 3) return false;
    const a = await pedir("A");
    return !!(a.Answer && a.Answer.some((x) => x.type === 1));
  } catch (_) { return true; }
}
async function webRegistro(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  let d;
  try { d = JSON.parse(await request.text()); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
  const nombre = corto(d.nombre, 80).trim();
  const email = corto(d.email, 120).trim().toLowerCase();
  if (nombre.length < 2) return jsonCors({ error: "Escribe tu nombre" }, 400);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) return jsonCors({ error: "Escribe un correo válido" }, 400);
  const dom = email.split("@")[1];
  if (ERRATAS[dom]) return jsonCors({ error: "¿Quisiste escribir " + email.split("@")[0] + "@" + ERRATAS[dom] + "?" }, 400);
  if (DESECHABLES.has(dom)) return jsonCors({ error: "Usa un correo real, no uno temporal" }, 400);
  if (!(await dominioRecibeCorreo(dom))) return jsonCors({ error: "Ese correo no existe, revisa cómo lo escribiste" }, 400);
  if (!env.BREVO_KEY || !env.MAIL_FROM) return jsonCors({ error: "Falta configurar el correo (BREVO_KEY y MAIL_FROM)" }, 503);
  const exp = Date.now() + 15 * 60 * 1000;
  const carga = exp + "." + btoa(unescape(encodeURIComponent(email)));
  const firma = await webFirma(env, carga);
  if (!firma) return jsonCors({ error: "Falta el secreto WEB_SECRET" }, 503);
  await prepararTablas(env);
  await env.DB.prepare("CREATE TABLE IF NOT EXISTS web_registros (email TEXT PRIMARY KEY, nombre TEXT, pais TEXT, primera INTEGER, ultima INTEGER, descargas INTEGER DEFAULT 0, ip TEXT)").run();
  const ahora = Date.now();
  const previo = await env.DB.prepare("SELECT ultima FROM web_registros WHERE email = ?1").bind(email).first();
  if (previo && ahora - previo.ultima < 60000) return jsonCors({ error: "Ya te enviamos un correo, espera un minuto para pedir otro" }, 429);
  const codigo = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
  await env.DB.prepare("CREATE TABLE IF NOT EXISTS web_codigos (email TEXT PRIMARY KEY, codigo TEXT, exp INTEGER, intentos INTEGER DEFAULT 0)").run();
  await env.DB.prepare("INSERT INTO web_codigos (email, codigo, exp, intentos) VALUES (?1,?2,?3,0) ON CONFLICT(email) DO UPDATE SET codigo=?2, exp=?3, intentos=0").bind(email, codigo, ahora + 10 * 60 * 1000).run();
  const esc = (x) => x.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const html = '<div style="font-family:Arial,sans-serif;background:#0a0e2b;color:#fff;padding:28px;border-radius:16px;max-width:480px;margin:auto;text-align:center">' +
    '<h2 style="color:#29c5ff;margin:0 0 10px">Hola ' + esc(nombre) + '</h2>' +
    '<p style="color:#b8c0ff">Tu código para descargar Chamos es:</p>' +
    '<p style="font-size:38px;letter-spacing:10px;font-weight:bold;color:#9b5cff;margin:18px 0">' + codigo + '</p>' +
    '<p style="color:#b8c0ff">Escríbelo en la página. Sirve por 10 minutos.</p>' +
    '<p style="color:#8890c8;font-size:12px">Si no pediste esto, ignora este correo.</p></div>';
  const r = await fetch("https://api.brevo.com/v3/smtp/email", {
    method: "POST",
    headers: { "api-key": env.BREVO_KEY, "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ sender: { name: "Chamos", email: env.MAIL_FROM }, to: [{ email, name: nombre }], subject: "Tu código de Chamos: " + codigo, htmlContent: html }),
  });
  if (!r.ok) { console.error("brevo", r.status, await r.text()); return jsonCors({ error: "No pudimos enviar el correo. Revisa que esté bien escrito." }, 502); }
  await env.DB.prepare(
    "INSERT INTO web_registros (email, nombre, pais, primera, ultima, ip) VALUES (?1,?2,?3,?4,?4,?5) ON CONFLICT(email) DO UPDATE SET nombre=?2, pais=?3, ultima=?4, ip=?5",
  ).bind(email, nombre, corto(request.cf && request.cf.country, 4), ahora, corto(request.headers.get("cf-connecting-ip"), 64)).run();
  return jsonCors({ ok: true });
}
async function webVerificar(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  let d;
  try { d = JSON.parse(await request.text()); } catch { return jsonCors({ error: "JSON inválido" }, 400); }
  const email = corto(d.email, 120).trim().toLowerCase();
  const codigo = corto(d.codigo, 10).replace(/\D/g, "");
  await prepararTablas(env);
  await env.DB.prepare("CREATE TABLE IF NOT EXISTS web_codigos (email TEXT PRIMARY KEY, codigo TEXT, exp INTEGER, intentos INTEGER DEFAULT 0)").run();
  const f = await env.DB.prepare("SELECT codigo, exp, intentos FROM web_codigos WHERE email = ?1").bind(email).first();
  if (!f || f.exp < Date.now()) return jsonCors({ error: "El código venció, pide uno nuevo" }, 400);
  if (f.intentos >= 5) return jsonCors({ error: "Demasiados intentos, pide un código nuevo" }, 429);
  if (codigo !== f.codigo) {
    await env.DB.prepare("UPDATE web_codigos SET intentos = intentos + 1 WHERE email = ?1").bind(email).run();
    return jsonCors({ error: "Código incorrecto" }, 400);
  }
  await env.DB.prepare("DELETE FROM web_codigos WHERE email = ?1").bind(email).run();
  const exp = Date.now() + 15 * 60 * 1000;
  const carga = exp + "." + btoa(unescape(encodeURIComponent(email)));
  const firma = await webFirma(env, carga);
  if (!firma) return jsonCors({ error: "Falta el secreto WEB_SECRET" }, 503);
  return jsonCors({ ok: true, url: new URL(request.url).origin + "/web/apk?t=" + encodeURIComponent(carga + "." + firma) });
}
async function webApk(request, env) {
  const t = new URL(request.url).searchParams.get("t") || "";
  const i = t.lastIndexOf(".");
  const carga = t.slice(0, i), firma = t.slice(i + 1);
  const esperada = await webFirma(env, carga);
  const exp = Number(carga.split(".")[0]);
  if (!esperada || firma !== esperada || !(exp > Date.now())) return new Response("Enlace vencido. Vuelve a la página de Chamos y regístrate otra vez.", { status: 403, headers: CORS });
  try {
    const email = decodeURIComponent(escape(atob(carga.split(".")[1])));
    await env.DB.prepare("UPDATE web_registros SET descargas = descargas + 1, ultima = ?2 WHERE email = ?1").bind(email, Date.now()).run();
  } catch (_) {}
  const r = await fetch(APK_URL, { redirect: "follow" });
  if (!r.ok) return new Response("No se pudo obtener la app, intenta de nuevo.", { status: 502, headers: CORS });
  const h = new Headers({ "content-type": "application/vnd.android.package-archive", "content-disposition": 'attachment; filename="Chamos.apk"', ...CORS });
  const l = r.headers.get("content-length");
  if (l) h.set("content-length", l);
  return new Response(r.body, { headers: h });
}
async function webLista(request, env) {
  if (!env.ADMIN_KEY || request.headers.get("x-clave") !== env.ADMIN_KEY) return jsonCors({ error: "Clave incorrecta" }, 401);
  await prepararTablas(env);
  await env.DB.prepare("CREATE TABLE IF NOT EXISTS web_registros (email TEXT PRIMARY KEY, nombre TEXT, pais TEXT, primera INTEGER, ultima INTEGER, descargas INTEGER DEFAULT 0, ip TEXT)").run();
  const r = await env.DB.prepare("SELECT email, nombre, pais, primera, ultima, descargas FROM web_registros ORDER BY primera DESC LIMIT 500").all();
  return jsonCors({ total: r.results.length, registros: r.results });
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (soloRelease(request, env, url)) return jsonCors(CERRADA, 426);
    if (url.pathname === "/web/registro" && request.method === "POST") {
      try { return await webRegistro(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/web/verificar" && request.method === "POST") {
      try { return await webVerificar(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/web/apk" && request.method === "GET") {
      try { return await webApk(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/web/registros" && request.method === "GET") {
      try { return await webLista(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname.startsWith("/v/") && request.method === "POST") {
      try { return await videoSubir(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname.startsWith("/v/") && (request.method === "GET" || request.method === "HEAD")) {
      try { return await videoVer(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if ((url.pathname === "/estado/visto" || url.pathname === "/estado/reaccion") && request.method === "POST" || url.pathname === "/estado/info" && request.method === "GET") {
      try { return await estadoSocialApi(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/video/accion" && request.method === "POST" || url.pathname === "/video/resumen" && request.method === "GET") {
      try { return await videoSocialApi(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/opinion" && request.method === "POST") {
      try { return await opinionApi(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/seguir" && (request.method === "GET" || request.method === "POST")) {
      try { return await seguirApi(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/e" && request.method === "POST") {
      try {
        return await guardarEvento(request, env);
      } catch (e) {
        console.error(e);
        return jsonCors({ error: String(e.message || e) }, 500);
      }
    }
    if (url.pathname.startsWith("/juego/")) {
      try {
        return await juego(request, env, url);
      } catch (e) {
        console.error(e);
        return jsonCors({ error: String(e.message || e) }, 500);
      }
    }
    if (url.pathname === "/api/resumen") {
      try {
        return await resumen(request, env);
      } catch (e) {
        console.error(e);
        return jsonCors({ error: String(e.message || e) }, 500);
      }
    }
    if (url.pathname === "/ia" && request.method === "POST") {
      try {
        return await chatIA(request, env);
      } catch (e) {
        console.error(e);
        return json({ error: "Error interno" }, 500);
      }
    }
    if (url.pathname === "/panel") {
      return new Response(PANEL_HTML, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
    }
    if (url.pathname === "/dolar" && request.method === "GET") {
      const t = await tasaBCV();
      return t ? jsonCors(t) : jsonCors({ error: "Sin datos" }, 502);
    }
    if (url.pathname === "/bots/estado" && request.method === "GET") {
      try { return await botsEstado(request, env); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/bots/ahora" && request.method === "POST") {
      try { return await botsAhora(request, env, url); } catch (e) { console.error(e); return jsonCors({ error: String(e.message || e) }, 500); }
    }
    if (url.pathname === "/_matrix/push/v1/notify") {
      if (request.method !== "POST") return json({ error: "Usa POST" }, 405);
      try {
        return await handleNotify(request, env);
      } catch (e) {
        console.error(e);
        return json({ error: String(e.message || e) }, 500);
      }
    }
    if (url.pathname === "/") {
      let configured = true;
      try {
        loadServiceAccount(env);
      } catch {
        configured = false;
      }
      return json({ servicio: "Chamos push gateway", ok: true, firebase_configurado: configured, estadisticas_configuradas: !!(env.DB && env.ADMIN_KEY), chamos_ia_configurada: !!(env.AI && env.DB), b2: { USAR_B2: _t(env.USAR_B2) === "1" ? "1" : (env.USAR_B2 ? "valor raro: " + JSON.stringify(env.USAR_B2) : "falta"), B2_KEY_ID: !!env.B2_KEY_ID, B2_KEY: !!env.B2_KEY, B2_BUCKET: !!env.B2_BUCKET, B2_REGION: env.B2_REGION || "(usa us-east-005)", activo: !!b2Activo(env) } });
    }
    return json({ error: "No encontrado" }, 404);
  },
  async scheduled(event, env, ctx) {
    ctx.waitUntil(botsCron(env).catch((e) => console.error("botsCron", e)));
  },
};
