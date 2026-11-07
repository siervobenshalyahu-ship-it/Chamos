/**
 * Chamos Cuentas (Cloudflare Worker, gratis)
 *
 * Vincula el número de teléfono de cada usuario con su cuenta de Matrix y permite encontrar
 * contactos por número. El número se verifica con un bot de Telegram (el usuario comparte su
 * contacto y Telegram entrega el número que él ya verificó), así que no cuesta nada mandar SMS.
 *
 * Secretos y ajustes (ver LEEME.md):
 *   TG_TOKEN        token del bot (BotFather)            [secreto]
 *   TG_SECRET       texto largo al azar para el webhook  [secreto]
 *   PEPPER          texto largo al azar para cifrar los números [secreto]
 *   TG_BOT          nombre del bot sin @, por ejemplo ChamosCuentasBot
 *   MATRIX_SERVER   servidor de las cuentas (por defecto matrix.org)
 *   FEDERATION_URL  (opcional) dirección de federación de ese servidor
 *   CUENTAS         KV namespace
 *
 * Los números NUNCA se guardan en claro: solo su HMAC con PEPPER y los dos últimos dígitos.
 * La identidad Matrix de quien llama se comprueba con un token OpenID emitido por su servidor.
 */

const SESSION_TTL = 600;
const MAX_BUSCAR = 500;
const MAX_BODY = 64 * 1024;
const enc = new TextEncoder();

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS } });

function hex(buf) {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function hmac(secret, msg) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", key, enc.encode(msg)));
}
function tokenAleatorio() {
  return hex(crypto.getRandomValues(new Uint8Array(16)));
}

/** "+58 414 123 4567", "58414..." -> "+584141234567". Devuelve null si no parece un número. */
export function normalizar(texto) {
  const d = String(texto ?? "").replace(/\D/g, "");
  if (d.length < 8 || d.length > 15) return null;
  return "+" + d;
}
function parcial(ultimos) {
  return "•••• " + ultimos;
}

/** Comprueba con el servidor de Matrix quién es el dueño del token OpenID. Devuelve "@usuario:servidor" o null. */
export async function identidad(openid, env) {
  const tok = openid && typeof openid.access_token === "string" ? openid.access_token : "";
  if (!tok || tok.length > 512) return null;
  const servidor = env.MATRIX_SERVER || "matrix.org";
  const bases = env.FEDERATION_URL ? [env.FEDERATION_URL] : servidor === "matrix.org" ? ["https://matrix-federation.matrix.org", "https://matrix.org"] : ["https://" + servidor];
  for (const base of bases) {
    try {
      const r = await fetch(base + "/_matrix/federation/v1/openid/userinfo?access_token=" + encodeURIComponent(tok));
      if (!r.ok) continue;
      const j = await r.json();
      const sub = typeof j.sub === "string" ? j.sub : "";
      if (/^@[^:\s]+:[^\s]+$/.test(sub) && sub.endsWith(":" + servidor)) return sub;
    } catch {}
  }
  return null;
}

/** Revisa la configuración sin mostrar ningún secreto: abre /diagnostico en el navegador. */
async function diagnostico(env) {
  const r = {
    kv_CUENTAS: !!env.CUENTAS,
    secreto_PEPPER: !!env.PEPPER,
    secreto_TG_TOKEN: !!env.TG_TOKEN,
    secreto_TG_SECRET: !!env.TG_SECRET,
  };
  if (env.TG_TOKEN) {
    try {
      const me = await (await fetch("https://api.telegram.org/bot" + env.TG_TOKEN + "/getMe")).json();
      r.bot = me.ok ? "@" + me.result.username : "TOKEN NO VÁLIDO: " + (me.description || "");
      const w = await (await fetch("https://api.telegram.org/bot" + env.TG_TOKEN + "/getWebhookInfo")).json();
      if (w.ok) {
        r.webhook_url = w.result.url || "(sin webhook)";
        r.webhook_ultimo_error = w.result.last_error_message || "(ninguno)";
        r.webhook_pendientes = w.result.pending_update_count;
      }
    } catch (e) {
      r.telegram = "no se pudo consultar: " + e;
    }
  }
  try {
    const f = await fetch((env.FEDERATION_URL || "https://matrix-federation.matrix.org") + "/_matrix/federation/v1/openid/userinfo?access_token=x");
    r.federacion_http = f.status;
  } catch (e) {
    r.federacion_http = "sin conexión: " + e;
  }
  return json(r);
}

async function leer(req) {
  const txt = await req.text();
  if (txt.length > MAX_BODY) return null;
  try {
    return JSON.parse(txt || "{}");
  } catch {
    return null;
  }
}

/** Límite simple por usuario: n acciones cada ventana de segundos. */
async function limite(env, clave, n, ventana) {
  const k = "rl:" + clave;
  const actual = parseInt((await env.CUENTAS.get(k)) || "0", 10);
  if (actual >= n) return false;
  await env.CUENTAS.put(k, String(actual + 1), { expirationTtl: ventana });
  return true;
}

/** Guarda la pareja número <-> cuenta. Un número solo puede estar en una cuenta y una cuenta tiene un solo número. */
async function vincular(env, matrixId, telefono) {
  const h = await hmac(env.PEPPER, telefono);
  const ultimos = telefono.slice(-2);
  const previo = await env.CUENTAS.get("mx:" + matrixId, "json");
  if (previo && previo.h !== h) await env.CUENTAS.delete("tel:" + previo.h);
  const otro = await env.CUENTAS.get("tel:" + h);
  if (otro && otro !== matrixId) await env.CUENTAS.delete("mx:" + otro);
  await env.CUENTAS.put("tel:" + h, matrixId);
  await env.CUENTAS.put("mx:" + matrixId, JSON.stringify({ h, u: ultimos, t: Date.now() }));
}

/* ---------------- API para la app ---------------- */

// Nombre del bot: se le pregunta a Telegram con el mismo TG_TOKEN, así el enlace nunca apunta a un bot que no existe.
async function nombreBot(env) {
  const guardado = await env.CUENTAS.get("cfg:bot");
  if (guardado) return guardado;
  try {
    const r = await fetch("https://api.telegram.org/bot" + env.TG_TOKEN + "/getMe");
    const j = await r.json();
    if (j && j.ok && j.result && j.result.username) {
      await env.CUENTAS.put("cfg:bot", j.result.username, { expirationTtl: 86400 });
      return j.result.username;
    }
  } catch {}
  return String(env.TG_BOT || "").trim().replace(/^@/, "").replace(/^https?:\/\/t\.me\//, "");
}

async function iniciar(req, env) {
  const b = await leer(req);
  if (!b) return json({ error: "solicitud inválida" }, 400);
  const id = await identidad(b.openid, env);
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  if (!(await limite(env, "ini:" + id, 5, 600))) return json({ error: "demasiados intentos, espera unos minutos" }, 429);
  const token = tokenAleatorio();
  await env.CUENTAS.put("ses:" + token, JSON.stringify({ matrixId: id, estado: "pendiente" }), { expirationTtl: SESSION_TTL });
  const bot = await nombreBot(env);
  if (!/^[A-Za-z0-9_]{4,}$/.test(bot) || bot.startsWith("CAMBIA")) return json({ error: "falta configurar TG_TOKEN o TG_BOT" }, 500);
  return json({ token, enlace: "https://t.me/" + bot + "?start=" + token, expira_en: SESSION_TTL });
}

async function estado(url, env) {
  const token = url.searchParams.get("token") || "";
  if (!/^[0-9a-f]{32}$/.test(token)) return json({ error: "token inválido" }, 400);
  const s = await env.CUENTAS.get("ses:" + token, "json");
  if (!s) return json({ estado: "expirado" });
  return json({ estado: s.estado, telefono: s.ultimos ? parcial(s.ultimos) : null });
}

async function miNumero(req, env) {
  const b = await leer(req);
  const id = b && (await identidad(b.openid, env));
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  const m = await env.CUENTAS.get("mx:" + id, "json");
  return json({ vinculado: !!m, telefono: m ? parcial(m.u) : null });
}

async function desvincular(req, env) {
  const b = await leer(req);
  const id = b && (await identidad(b.openid, env));
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  const m = await env.CUENTAS.get("mx:" + id, "json");
  if (m) {
    await env.CUENTAS.delete("tel:" + m.h);
    await env.CUENTAS.delete("mx:" + id);
  }
  return json({ vinculado: false });
}

async function buscar(req, env) {
  const b = await leer(req);
  const id = b && (await identidad(b.openid, env));
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  if (!Array.isArray(b.numeros)) return json({ error: "faltan los números" }, 400);
  if (!(await limite(env, "bus:" + id, 20, 3600))) return json({ error: "demasiadas búsquedas, intenta más tarde" }, 429);
  const vistos = new Set();
  const encontrados = [];
  for (const crudo of b.numeros.slice(0, MAX_BUSCAR)) {
    const n = normalizar(crudo);
    if (!n || vistos.has(n)) continue;
    vistos.add(n);
    const dueño = await env.CUENTAS.get("tel:" + (await hmac(env.PEPPER, n)));
    if (dueño && dueño !== id) encontrados.push({ numero: n, matrixId: dueño });
  }
  return json({ encontrados });
}

/* Cuentas de Chamos: cada app registra su cuenta al abrir; la búsqueda solo muestra las registradas */
async function registrar(req, env) {
  const b = await leer(req);
  const id = b && (await identidad(b.openid, env));
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  await env.CUENTAS.put("cu:" + id, "1");
  return json({ ok: true });
}

async function filtrar(req, env) {
  const b = await leer(req);
  const id = b && (await identidad(b.openid, env));
  if (!id) return json({ error: "no se pudo comprobar tu cuenta" }, 401);
  if (!Array.isArray(b.ids)) return json({ error: "faltan las cuentas" }, 400);
  const son = [];
  for (const x of b.ids.slice(0, 50)) {
    if (typeof x === "string" && x.length < 200 && (await env.CUENTAS.get("cu:" + x))) son.push(x);
  }
  return json({ son });
}

/* ---------------- Bot de Telegram ---------------- */

async function tg(env, metodo, datos) {
  await fetch("https://api.telegram.org/bot" + env.TG_TOKEN + "/" + metodo, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(datos),
  });
}

async function telegram(req, env) {
  if (req.headers.get("x-telegram-bot-api-secret-token") !== env.TG_SECRET) return new Response("no", { status: 403 });
  const u = await leer(req);
  const m = u && u.message;
  if (!m || !m.chat || !m.from) return new Response("ok");
  const chat = m.chat.id;
  const decir = (text, extra) => tg(env, "sendMessage", { chat_id: chat, text, ...extra });

  if (typeof m.text === "string" && m.text.startsWith("/start")) {
    const token = (m.text.split(/\s+/)[1] || "").trim();
    const s = /^[0-9a-f]{32}$/.test(token) ? await env.CUENTAS.get("ses:" + token, "json") : null;
    if (!s) {
      await decir("Abre este bot desde la app Chamos (Ajustes > Vincular mi número) para empezar.");
    } else {
      await env.CUENTAS.put("tg:" + m.from.id, token, { expirationTtl: SESSION_TTL });
      await decir("Hola. Para vincular tu número con Chamos toca el botón y comparte tu contacto.", {
        reply_markup: { keyboard: [[{ text: "Compartir mi número", request_contact: true }]], resize_keyboard: true, one_time_keyboard: true },
      });
    }
  } else if (m.contact) {
    if (m.contact.user_id !== m.from.id) {
      await decir("Tiene que ser tu propio número. Usa el botón «Compartir mi número».");
    } else {
      const token = await env.CUENTAS.get("tg:" + m.from.id);
      const s = token ? await env.CUENTAS.get("ses:" + token, "json") : null;
      const tel = normalizar(m.contact.phone_number);
      if (!s || !tel) {
        await decir("Se venció el intento. Vuelve a Chamos y toca «Vincular mi número» otra vez.", { reply_markup: { remove_keyboard: true } });
      } else {
        await vincular(env, s.matrixId, tel);
        await env.CUENTAS.put("ses:" + token, JSON.stringify({ matrixId: s.matrixId, estado: "verificado", ultimos: tel.slice(-2) }), { expirationTtl: SESSION_TTL });
        await env.CUENTAS.delete("tg:" + m.from.id);
        await decir("Listo, tu número quedó vinculado. Vuelve a Chamos.", { reply_markup: { remove_keyboard: true } });
      }
    }
  }
  return new Response("ok");
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (url.pathname === "/diagnostico") return diagnostico(env);
    if (!env.CUENTAS || !env.PEPPER) return json({ error: "falta configurar el servidor" }, 500);
    const ruta = url.pathname;
    if (req.method === "POST" && ruta === "/telegram") return telegram(req, env);
    if (req.method === "POST" && ruta === "/vincular/iniciar") return iniciar(req, env);
    if (req.method === "GET" && ruta === "/vincular/estado") return estado(url, env);
    if (req.method === "POST" && ruta === "/mi/numero") return miNumero(req, env);
    if (req.method === "POST" && ruta === "/mi/desvincular") return desvincular(req, env);
    if (req.method === "POST" && ruta === "/contactos/buscar") return buscar(req, env);
    if (req.method === "POST" && ruta === "/chamos/registrar") return registrar(req, env);
    if (req.method === "POST" && ruta === "/chamos/filtrar") return filtrar(req, env);
    if (ruta === "/") return json({ servicio: "Chamos Cuentas", ok: true });
    return json({ error: "no existe" }, 404);
  },
};
