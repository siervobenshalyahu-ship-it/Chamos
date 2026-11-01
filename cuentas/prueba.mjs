// Prueba local sin Cloudflare: node cuentas/prueba.mjs
import worker, { normalizar } from "./worker.js";
import assert from "node:assert/strict";

const kv = new Map();
const KV = {
  async get(k, t) { const v = kv.get(k); if (v === undefined) return null; return t === "json" ? JSON.parse(v) : v; },
  async put(k, v) { kv.set(k, String(v)); },
  async delete(k) { kv.delete(k); },
};
const env = { CUENTAS: KV, PEPPER: "pimienta", TG_SECRET: "sec", TG_TOKEN: "tok", TG_BOT: "ChamosBot", MATRIX_SERVER: "matrix.org" };
const usuarios = { A: "@ana:matrix.org", B: "@beto:matrix.org", X: "@mal:otro.org" };
const enviados = [];
globalThis.fetch = async (url, init) => {
  url = String(url);
  if (url.includes("openid/userinfo")) {
    const t = new URL(url).searchParams.get("access_token");
    return usuarios[t] ? new Response(JSON.stringify({ sub: usuarios[t] })) : new Response("{}", { status: 401 });
  }
  if (url.includes("api.telegram.org")) { enviados.push(JSON.parse(init.body)); return new Response("{}"); }
  throw new Error("fetch inesperado " + url);
};
const post = (ruta, cuerpo, cab = {}) => worker.fetch(new Request("https://x" + ruta, { method: "POST", body: JSON.stringify(cuerpo), headers: cab }), env).then(async (r) => [r.status, await r.json().catch(() => null)]);
const tgMsg = (message) => post("/telegram", { message }, { "x-telegram-bot-api-secret-token": "sec" });

assert.equal(normalizar("+58 414-123 4567"), "+584141234567");
assert.equal(normalizar("123"), null);

// identidad falsa o de otro servidor
assert.equal((await post("/vincular/iniciar", { openid: { access_token: "zzz" } }))[0], 401);
assert.equal((await post("/vincular/iniciar", { openid: { access_token: "X" } }))[0], 401);

// Ana y Beto vinculan sus números con Telegram
for (const [clave, tgId, num] of [["A", 11, "584141234567"], ["B", 22, "584245550000"]]) {
  const [s, ini] = await post("/vincular/iniciar", { openid: { access_token: clave } });
  assert.equal(s, 200); assert.match(ini.enlace, /^https:\/\/t\.me\/ChamosBot\?start=[0-9a-f]{32}$/);
  await tgMsg({ chat: { id: tgId }, from: { id: tgId }, text: "/start " + ini.token });
  assert.ok(enviados.at(-1).reply_markup.keyboard);
  // número de otra persona: rechazado
  await tgMsg({ chat: { id: tgId }, from: { id: tgId }, contact: { user_id: 999, phone_number: num } });
  assert.match(enviados.at(-1).text, /propio número/);
  await tgMsg({ chat: { id: tgId }, from: { id: tgId }, contact: { user_id: tgId, phone_number: num } });
  const est = await (await worker.fetch(new Request("https://x/vincular/estado?token=" + ini.token), env)).json();
  assert.equal(est.estado, "verificado");
}
// webhook sin secreto
assert.equal((await post("/telegram", { message: {} }))[0], 403);

// los números no se guardan en claro
assert.ok(![...kv.entries()].some(([k, v]) => k.includes("5841412") || v.includes("5841412")));

// Ana busca contactos
const [, r] = await post("/contactos/buscar", { openid: { access_token: "A" }, numeros: ["0414 123", "+58 424 555 0000", "+58 412 000 0000", "+584141234567"] });
assert.deepEqual(r.encontrados, [{ numero: "+584245550000", matrixId: "@beto:matrix.org" }]);

// mi número y desvincular
assert.deepEqual((await post("/mi/numero", { openid: { access_token: "A" } }))[1], { vinculado: true, telefono: "•••• 67" });
await post("/mi/desvincular", { openid: { access_token: "B" } });
const [, r2] = await post("/contactos/buscar", { openid: { access_token: "A" }, numeros: ["+584245550000"] });
assert.deepEqual(r2.encontrados, []);
console.log("Todo bien: la verificación, la búsqueda y la privacidad pasan las pruebas.");
