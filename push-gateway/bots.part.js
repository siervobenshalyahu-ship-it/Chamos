// ───────────────────────── Bots de Chamos ─────────────────────────
// Cuentas temáticas (siempre marcadas como bot) que publican en el Muro y en Chamos Videos.
// Secretos: BOTS_ACTIVOS=1 (interruptor), BOTS_CUENTAS="maria usuario clave;baul usuario clave;..." y PEXELS_KEY (opcional, videos).
// Necesita un Cron Trigger de cada hora ("0 * * * *"). Límite duro: BOT_MAX_DIA publicaciones por bot y día.
const MS_API = "https://matrix-client.matrix.org/_matrix/client/v3";
const MS_MEDIA = "https://matrix-client.matrix.org/_matrix/media/v3";
const BOT_MURO_ALIAS = "#chamos-muro:matrix.org";
const BOT_REELS_ALIAS = "#chamos-reels:matrix.org";
const M_POST = "⁣";
const M_VIDEO = "⁥";
const UA_BOT = "ChamosBot/1.0 (https://siervobenshalyahu-ship-it.github.io/Chamos-apk-web/)";
const BOT_URL = "https://chamos-push.siervodeyhwh57.workers.dev";
const BOT_MAX_DIA = 4;
const BOT_MAX_FORZADO = 12;
const BOT_VIDEO_MAX = 12 * 1024 * 1024;
const BOT_VIDEO_HORAS = 48;

const BOTS = {
  maria: { nombre: "María Chamos 🤖", tema: "belleza", horas: [9, 18], reel: 15, busca: ["makeup tutorial", "skincare routine", "hair care", "beauty tips"] },
  baul: { nombre: "Chamo del Baúl 🤖", tema: "salsa", horas: [12, 20], reel: null, busca: [] },
  sazon: { nombre: "Sazón Chamos 🤖", tema: "comida", horas: [8, 13, 19], reel: 17, busca: ["cooking food", "street food", "baking", "kitchen recipe"] },
  viajero: { nombre: "Chamo Viajero 🤖", tema: "lugares", horas: [10, 16], reel: 14, busca: ["travel landscape", "city travel", "beach island", "mountain nature"] },
  solidarios: { nombre: "Chamos Solidarios 🤖", tema: "social", horas: [11], reel: null, busca: [] },
  noticias: { nombre: "Chamos Noticias 🤖", tema: "noticias", horas: [7, 12, 17], reel: null, busca: [] },
};

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
  const b = BOTS[bot];
  if (b.tema === "belleza") {
    const i = await botElegir(env, bot, "muro", BELLEZA.map((_, k) => "b" + k));
    const texto = "💄 Consejo de belleza\n\n" + BELLEZA[parseInt(i.slice(1), 10)] + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    await botMuroTexto(env, bot, cuentas, texto);
    return { clave: i, nota: "texto" };
  }
  if (b.tema === "salsa") {
    const i = await botElegir(env, bot, "muro", SALSA.map((_, k) => "s" + k));
    const s = SALSA[parseInt(i.slice(1), 10)];
    const texto = "🎺 " + s[0] + "\n\n" + s[1] + "\n\n🎧 Escúchala aquí: " + yt(s[2]) + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    await botMuroTexto(env, bot, cuentas, texto);
    return { clave: i, nota: "texto" };
  }
  if (b.tema === "social") {
    const i = await botElegir(env, bot, "muro", SOCIAL.map((_, k) => "c" + k));
    const texto = "🤝 Para cuidarnos entre todos\n\n" + SOCIAL[parseInt(i.slice(1), 10)] + SOCIAL_PIE + "\n\n— " + b.nombre + " · cuenta automática de Chamos";
    await botMuroTexto(env, bot, cuentas, texto);
    return { clave: i, nota: "texto" };
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
    const r = await fetch("https://ve.dolarapi.com/v1/dolares/oficial", { headers: { "user-agent": UA_BOT } });
    if (!r.ok) return "";
    const j = await r.json();
    const v = Number(j.promedio || j.venta || j.compra);
    return v ? "💵 Dólar BCV hoy: Bs. " + v.toFixed(2).replace(".", ",") + "\n\n" : "";
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

// Video de Pexels (licencia libre) → Backblaze/KV → Chamos Videos.
async function botHacerReel(env, bot, cuentas) {
  const b = BOTS[bot];
  if (!env.PEXELS_KEY) throw new Error("Falta el secreto PEXELS_KEY");
  if (!b.busca.length) throw new Error("Este bot no publica videos");
  const q = b.busca[Math.floor(Math.random() * b.busca.length)];
  const r = await fetch("https://api.pexels.com/videos/search?per_page=20&orientation=portrait&query=" + encodeURIComponent(q), { headers: { authorization: String(env.PEXELS_KEY).trim(), "user-agent": UA_BOT } });
  if (!r.ok) throw new Error("Pexels " + r.status);
  const j = await r.json();
  const vistos = new Set(
    ((await env.DB.prepare("SELECT clave FROM bots_log WHERE bot=?1 AND modo='reel' AND ok=1 ORDER BY id DESC LIMIT 200").bind(bot).all()).results || []).map((x) => x.clave),
  );
  const cand = (j.videos || []).filter((v) => v.duration <= 45 && !vistos.has("p" + v.id));
  if (!cand.length) throw new Error("Pexels no trajo videos nuevos para '" + q + "'");
  const v = cand[Math.floor(Math.random() * cand.length)];
  const archivos = (v.video_files || []).filter((f) => f.file_type === "video/mp4" && f.width && f.width <= 1100).sort((a, c) => c.width - a.width);
  let buf = null;
  for (const f of archivos) {
    const d = await fetch(f.link, { headers: { "user-agent": UA_BOT } });
    if (!d.ok) continue;
    const largo = parseInt(d.headers.get("content-length") || "0", 10);
    if (largo && largo > BOT_VIDEO_MAX) { await d.body.cancel(); continue; }
    const b2 = await d.arrayBuffer();
    if (b2.byteLength && b2.byteLength <= BOT_VIDEO_MAX) { buf = b2; break; }
  }
  if (!buf) throw new Error("Ningún archivo del video cabe en " + (BOT_VIDEO_MAX / 1048576) + " MB");
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
  const desc = emoji + " " + (v.user && v.user.name ? "Video de " + v.user.name + " · " : "") + "Pexels · " + b.nombre + " · cuenta automática de Chamos";
  const sala = await botSala(env, bot, cuentas, "reels");
  await botEnviar(env, bot, cuentas, sala, { msgtype: "m.text", body: M_VIDEO + enlace + "\n" + desc });
  return { clave: "p" + v.id, nota: Math.round(buf.byteLength / 1024) + " KB · " + q };
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
      const toca = modo === "muro" ? BOTS[bot].horas.includes(hora) : BOTS[bot].reel === hora;
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
    bots.push({ id, nombre: BOTS[id].nombre, cuenta: !!cuentas[id], video: BOTS[id].reel !== null, hoy, ultimos: ult });
  }
  return jsonCors({ activos: String(env.BOTS_ACTIVOS || "").trim() === "1", pexels: !!env.PEXELS_KEY, b2: b2Activo(env), bots });
}
async function botsAhora(request, env, url) {
  if (!env.DB) return jsonCors({ error: "Base de datos no configurada" }, 503);
  if (!botsAdmin(request, env)) return jsonCors({ error: "Clave incorrecta" }, 401);
  const bot = url.searchParams.get("bot") || "";
  const modo = url.searchParams.get("modo") === "reel" ? "reel" : "muro";
  if (!BOTS[bot]) return jsonCors({ error: "Bot desconocido" }, 400);
  return jsonCors(await botEjecutar(env, bot, modo, true));
}

