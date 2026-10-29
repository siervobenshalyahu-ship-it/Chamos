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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
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
      return json({ servicio: "Chamos push gateway", ok: true, firebase_configurado: configured });
    }
    return json({ error: "No encontrado" }, 404);
  },
};
