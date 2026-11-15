// Envío de correos de Chamos con tu propio Gmail (Google Apps Script). Gratis, sin aprobación, hasta ~100 correos al día.
// 1) Cambia CLAVE por un texto largo inventado por ti (el mismo que pondrás como GAS_SECRET en Cloudflare).
// 2) Implementar > Nueva implementación > Tipo: Aplicación web > Ejecutar como: Yo > Acceso: Cualquier persona.
const CLAVE = "PON_AQUI_UNA_CLAVE_LARGA";

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.secret !== CLAVE) return salida({ ok: false, error: "clave" });
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(d.to || "")) return salida({ ok: false, error: "correo" });
    MailApp.sendEmail({ to: d.to, subject: d.subject, htmlBody: d.html, name: "Chamos" });
    return salida({ ok: true });
  } catch (err) {
    return salida({ ok: false, error: String(err) });
  }
}

function salida(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
