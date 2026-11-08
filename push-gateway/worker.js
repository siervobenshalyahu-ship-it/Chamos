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
  const [tot, unicos, dias, paises, usuarios, nUsuarios, versiones, recientes, topSeg, nSeg, opiniones] = await env.DB.batch([
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
   '<h2>Últimas visitas, descargas e instalaciones</h2><div class="sc"><table><tr><th>Cuándo</th><th>Qué</th><th>País</th><th>IP</th><th>Dispositivo</th></tr>'+
   (d.recientes||[]).map(r=>'<tr><td>'+fmt(r.ts)+'</td><td>'+esc(r.tipo)+'</td><td>'+esc(r.pais)+'</td><td>'+esc(r.ip)+'</td><td>'+esc(r.disp)+'</td></tr>').join('')+'</table></div>';
}
function esc(s){return String(s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
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
};
