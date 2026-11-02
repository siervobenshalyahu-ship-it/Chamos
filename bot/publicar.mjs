// Bot de Chamos: publica videos en los Reels (sala #chamos-reels:matrix.org) desde una cola.
// Uso: CUENTA=raul node publicar.mjs            -> publica los próximos videos de cuentas/raul/cola.json
//      node publicar.mjs perfil "Nombre"        -> cambia el nombre público de la cuenta
// Variables: MATRIX_TOKEN (obligatoria), MATRIX_USER (para "perfil"), MATRIX_HS (por defecto https://matrix.org),
//            POR_CORRIDA (cuántos videos por corrida, por defecto 1)
import { readFileSync, writeFileSync, statSync, existsSync, mkdtempSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, basename, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const CUENTA = (process.env.CUENTA || "raul").replace(/[^a-z0-9_-]/gi, "");
const AQUI = join(dirname(fileURLToPath(import.meta.url)), "cuentas", CUENTA);
const HS = (process.env.MATRIX_HS || "https://matrix.org").replace(/\/$/, "");
const TOKEN = process.env.MATRIX_TOKEN;
const POR_CORRIDA = Math.max(1, parseInt(process.env.POR_CORRIDA || "1", 10));
const ALIAS = process.env.REELS_ALIAS || "#chamos-reels:matrix.org";
const LIMITE = 50 * 1024 * 1024;

if (!TOKEN) { console.error("Falta MATRIX_TOKEN (el token de la cuenta del bot)."); process.exit(1); }

const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(metodo, ruta, cuerpo, extra = {}) {
  for (let intento = 0; intento < 5; intento++) {
    const r = await fetch(HS + ruta, {
      method: metodo,
      headers: { Authorization: "Bearer " + TOKEN, ...(cuerpo !== undefined && !extra.crudo ? { "Content-Type": "application/json" } : {}), ...(extra.cabeceras || {}) },
      body: cuerpo === undefined ? undefined : extra.crudo ? cuerpo : JSON.stringify(cuerpo),
    });
    if (r.status === 429) {
      const j = await r.json().catch(() => ({}));
      await dormir((j.retry_after_ms || 3000) + 500);
      continue;
    }
    const texto = await r.text();
    let j; try { j = JSON.parse(texto); } catch { j = { raw: texto }; }
    if (!r.ok) throw new Error(`${metodo} ${ruta} -> ${r.status} ${texto.slice(0, 200)}`);
    return j;
  }
  throw new Error("Demasiados reintentos: " + ruta);
}

const MIMES = { ".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm", ".m4v": "video/mp4", ".mkv": "video/x-matroska" };

function sondear(archivo) {
  // Medidas y duración con ffprobe (si no está instalado, se publica sin ellas).
  try {
    const out = execFileSync("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "json", archivo], { encoding: "utf8" });
    const j = JSON.parse(out);
    return { w: j.streams?.[0]?.width, h: j.streams?.[0]?.height, ms: Math.round(parseFloat(j.format?.duration || "0") * 1000) || undefined };
  } catch { return {}; }
}

function miniatura(archivo) {
  try {
    const dir = mkdtempSync(join(tmpdir(), "mini-"));
    const salida = join(dir, "mini.jpg");
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-ss", "1", "-i", archivo, "-frames:v", "1", "-vf", "scale=480:-2", salida]);
    if (statSync(salida).size > 0) return salida;
  } catch {}
  try {
    const dir = mkdtempSync(join(tmpdir(), "mini-"));
    const salida = join(dir, "mini.jpg");
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", archivo, "-frames:v", "1", "-vf", "scale=480:-2", salida]);
    return salida;
  } catch { return null; }
}

async function subir(ruta, mime, nombre) {
  const r = await api("POST", "/_matrix/media/v3/upload?filename=" + encodeURIComponent(nombre), readFileSync(ruta), { crudo: true, cabeceras: { "Content-Type": mime } });
  return r.content_uri;
}

async function entrarAReels() {
  const r = await api("POST", "/_matrix/client/v3/join/" + encodeURIComponent(ALIAS), {});
  return r.room_id;
}

async function publicar(sala, item) {
  const ruta = join(AQUI, item.archivo);
  if (!existsSync(ruta)) throw new Error("No existe el archivo " + item.archivo);
  const tam = statSync(ruta).size;
  if (tam > LIMITE) throw new Error(`${item.archivo} pesa más de 50 MB`);
  const nombre = basename(ruta);
  const mime = MIMES[extname(nombre).toLowerCase()] || "video/mp4";
  const med = sondear(ruta);
  const info = { mimetype: mime, size: tam };
  if (med.w) { info.w = med.w; info.h = med.h; }
  if (med.ms) info.duration = med.ms;
  const mini = miniatura(ruta);
  if (mini) {
    info.thumbnail_url = await subir(mini, "image/jpeg", "mini.jpg");
    info.thumbnail_info = { mimetype: "image/jpeg", size: statSync(mini).size };
  }
  const url = await subir(ruta, mime, nombre);
  const desc = (item.descripcion || "").trim();
  const txn = "chamosbot" + Date.now() + Math.floor(Math.random() * 1e6);
  const r = await api("PUT", `/_matrix/client/v3/rooms/${encodeURIComponent(sala)}/send/m.room.message/${txn}`, {
    msgtype: "m.video",
    body: desc || nombre,
    filename: nombre,
    url,
    info,
  });
  return r.event_id;
}

async function main() {
  if (process.argv[2] === "perfil") {
    const nombre = process.argv.slice(3).join(" ").trim();
    const usuario = process.env.MATRIX_USER;
    if (!nombre || !usuario) { console.error('Uso: MATRIX_USER=@bot:matrix.org node publicar.mjs perfil "Nombre"'); process.exit(1); }
    await api("PUT", `/_matrix/client/v3/profile/${encodeURIComponent(usuario)}/displayname`, { displayname: nombre });
    console.log("Nombre cambiado a:", nombre);
    return;
  }
  const colaRuta = join(AQUI, "cola.json");
  const cola = JSON.parse(readFileSync(colaRuta, "utf8"));
  const pendientes = cola.filter((c) => !c.publicado);
  if (!pendientes.length) { console.log("La cola está vacía: agrega videos a cola.json."); return; }
  let sala;
  try { sala = await entrarAReels(); } catch (e) {
    console.error("No pude entrar a los Reels (" + ALIAS + "). Si la sala no existe, abre Chamos con una cuenta de matrix.org y entra a Reels para crearla.\n" + e.message);
    process.exit(1);
  }
  let hechos = 0;
  for (const item of pendientes.slice(0, POR_CORRIDA)) {
    try {
      const id = await publicar(sala, item);
      item.publicado = new Date().toISOString();
      item.evento = id;
      hechos++;
      console.log("Publicado:", item.archivo, id);
      writeFileSync(colaRuta, JSON.stringify(cola, null, 2) + "\n");
    } catch (e) {
      console.error("Falló", item.archivo + ":", e.message);
      process.exitCode = 1;
      break;
    }
  }
  console.log(`Listo: ${hechos} publicado(s), quedan ${pendientes.length - hechos}.`);
}

main().catch((e) => { console.error(e.message); process.exit(1); });
