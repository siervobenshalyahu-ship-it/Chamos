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
  "access-control-allow-methods": "POST, GET, OPTIONS",
  "access-control-allow-headers": "content-type, x-clave",
};
const jsonCors = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...CORS } });

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
  ]);
  tablasListas = true;
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
  if (!["visita", "descarga", "instalacion", "registro"].includes(tipo)) return jsonCors({ error: "Tipo desconocido" }, 400);
  await prepararTablas(env);
  const ahora = Date.now();
  const pais = corto(request.cf && request.cf.country, 4);
  const anon = corto(d.id, 64);
  if (tipo === "registro") {
    const uid = corto(d.u, 100);
    if (!uid.startsWith("@")) return jsonCors({ error: "Falta usuario" }, 400);
    await env.DB.prepare(
      "INSERT INTO usuarios (user_id, nombre, version, modelo, pais, anon, primera, ultima) VALUES (?1,?2,?3,?4,?5,?6,?7,?7) " +
        "ON CONFLICT(user_id) DO UPDATE SET nombre=?2, version=?3, modelo=?4, pais=?5, anon=?6, ultima=?7",
    )
      .bind(uid, corto(d.n, 80), corto(d.v, 20), corto(d.m, 60), pais, anon, ahora)
      .run();
  } else {
    const extra = JSON.stringify({ v: corto(d.v, 20), m: corto(d.m, 60), p: corto(d.p, 100) });
    await env.DB.prepare("INSERT INTO eventos (ts, tipo, anon, pais, extra) VALUES (?1,?2,?3,?4,?5)")
      .bind(ahora, tipo, anon, pais, extra)
      .run();
  }
  return jsonCors({ ok: true });
}

async function resumen(request, env) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  if (!env.ADMIN_KEY) return jsonCors({ error: "Falta el secreto ADMIN_KEY" }, 503);
  if (request.headers.get("x-clave") !== env.ADMIN_KEY) return jsonCors({ error: "Clave incorrecta" }, 401);
  await prepararTablas(env);
  const hace30 = Date.now() - 30 * 24 * 3600 * 1000;
  const [tot, unicos, dias, paises, usuarios, nUsuarios, versiones] = await env.DB.batch([
    env.DB.prepare("SELECT tipo, COUNT(*) AS n FROM eventos GROUP BY tipo"),
    env.DB.prepare("SELECT tipo, COUNT(DISTINCT anon) AS n FROM eventos GROUP BY tipo"),
    env.DB.prepare(
      "SELECT strftime('%Y-%m-%d', ts/1000, 'unixepoch', '-4 hours') AS dia, tipo, COUNT(*) AS n FROM eventos WHERE ts > ?1 GROUP BY dia, tipo ORDER BY dia DESC",
    ).bind(hace30),
    env.DB.prepare("SELECT pais, COUNT(*) AS n FROM eventos WHERE tipo='visita' GROUP BY pais ORDER BY n DESC LIMIT 10"),
    env.DB.prepare("SELECT user_id, nombre, version, modelo, pais, primera, ultima FROM usuarios ORDER BY primera DESC LIMIT 200"),
    env.DB.prepare("SELECT COUNT(*) AS n FROM usuarios"),
    env.DB.prepare("SELECT version, COUNT(*) AS n FROM usuarios GROUP BY version ORDER BY n DESC"),
  ]);
  return jsonCors({
    totales: tot.results,
    unicos: unicos.results,
    dias: dias.results,
    paises: paises.results,
    usuarios: usuarios.results,
    total_usuarios: nUsuarios.results[0].n,
    versiones: versiones.results,
  });
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
   '<div class="c"><b>'+d.total_usuarios+'</b><span>Usuarios registrados (aceptaron el aviso)</span></div></div>'+
   '<h2>Últimos 30 días</h2><div class="sc"><table><tr><th>Día</th><th>Visitas</th><th>Descargas</th><th>Instal.</th></tr>'+
   Object.keys(dias).map(x=>'<tr><td>'+x+'</td><td>'+(dias[x].visita||0)+'</td><td>'+(dias[x].descarga||0)+'</td><td>'+(dias[x].instalacion||0)+'</td></tr>').join('')+'</table></div>'+
   '<h2>Visitas por país</h2><div>'+d.paises.map(p=>(p.pais||'?')+': '+p.n).join(' · ')+'</div>'+
   '<h2>Versiones de la app</h2><div>'+d.versiones.map(v=>(v.version||'?')+': '+v.n).join(' · ')+'</div>'+
   '<h2>Usuarios</h2><div class="sc"><table><tr><th>Usuario</th><th>Nombre</th><th>Versión</th><th>Teléfono</th><th>Desde</th><th>Última vez</th></tr>'+
   d.usuarios.map(u=>'<tr><td>'+esc(u.user_id)+'</td><td>'+esc(u.nombre)+'</td><td>'+esc(u.version)+'</td><td>'+esc(u.modelo)+'</td><td>'+fmt(u.primera)+'</td><td>'+fmt(u.ultima)+'</td></tr>').join('')+'</table></div>';
}
function esc(s){return String(s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
if(sessionStorage.getItem('k'))go();
</script></body></html>`;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (url.pathname === "/e" && request.method === "POST") {
      try {
        return await guardarEvento(request, env);
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
      return json({ servicio: "Chamos push gateway", ok: true, firebase_configurado: configured, estadisticas_configuradas: !!(env.DB && env.ADMIN_KEY) });
    }
    return json({ error: "No encontrado" }, 404);
  },
};
