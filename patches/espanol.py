# Todo en español venezolano: los textos por defecto pasan a ser los de español, se completan los que faltan
# y se quitan las otras traducciones. Asi ninguna frase sale en ingles ni en otro idioma. Uso: python3 espanol.py RAIZ
import sys, os, re, glob, shutil

root = sys.argv[1]

FALTAN = {
    "screen_identity_confirmation_sign_out": "Cerrar sesión",
    "banner_swipe_avatar_content": "Desliza tu foto hacia arriba o hacia abajo para cambiar de cuenta sin entrar a los ajustes.",
    "banner_swipe_avatar_title": "Desliza tu foto para cambiar de cuenta",
    "screen_home_tab_search": "Buscar",
    "screen_multi_account_announcement_action": "Agregar cuenta",
    "screen_multi_account_announcement_description": "Entra a %1$s para iniciar sesión en otra cuenta. Después podrás cambiar de una a otra fácilmente.",
    "screen_multi_account_announcement_title": "Ya puedes agregar varias cuentas",
    "screen_roomlist_filter_mention": "Menciones",
    "screen_roomlist_filter_mention_empty_state_title": "No tienes menciones sin leer",
    "screen_onboarding_add_account_subtitle": "Puedes agregar varias cuentas a la app y cambiar de una a otra fácilmente.",
    "screen_onboarding_add_account_title": "Iniciar sesión en otra cuenta",
    "screen_signout_other_verified_device_dialog_content": "Asegúrate de tener siempre acceso a otro dispositivo verificado o a tu clave de recuperación para no perder tu historial de chats cifrados.",
    "screen_signout_other_verified_device_dialog_title": "¿Seguro que quieres quitar este dispositivo?",
    "screen_signout_save_recovery_key_action_continue": "Continuar y quitar este dispositivo",
    "error_sending_failure_invalid_mime_type": "Tipo de archivo no válido: %s",
    "error_sending_failure_missing_media_content": "Falta el contenido del archivo",
    "error_sending_failure_other": "Detalles del error: %s",
    "error_sending_failure_sending_from_unverified_device": "Envío desde un dispositivo sin verificar",
    "error_sending_failure_unknown": "Error desconocido",
    "screen_room_confirm_removal_reason_label": "Motivo (opcional)",
    "screen_room_confirm_removal_reason_placeholder": "Escribe el motivo…",
    "screen_room_confirm_removal_reason_supporting_text": "(Opcional)",
    "screen_room_confirm_removal_title": "¿Borrar el mensaje?",
    "screen_labs_enable_gallery": "Mensajes de galería",
    "screen_labs_enable_gallery_description": "Manda varias fotos o videos de una sola vez. Funciona bien en el celular, pero la versión web todavía no lo soporta.",
    "screen_labs_enable_knocking": "Pedir entrar",
    "screen_labs_enable_knocking_description": "Las personas pueden pedir acceso a una sala que no está abierta para todos. Funciona bien en el celular; en la web es experimental.",
    "screen_moderation_and_safety_invites_heading": "Invitaciones",
    "screen_moderation_and_safety_other_users_heading": "Otras personas",
    "screen_moderation_and_safety_share_presence_heading": "Tu aparición",
    "screen_notification_settings_activity_section_title": "Actividad",
    "screen_notification_settings_show_all_activity_subtitle": "Puntos o número de mensajes sin leer",
    "screen_notification_settings_show_all_activity_title": "Mostrar toda la actividad en la lista de chats",
    "screen_settings_add_account": "Agregar cuenta",
    "screen_room_details_audio_push_to_talk": "Pulsar para hablar",
    "screen_room_details_audio_title": "Audio de la sala",
    "screen_room_details_audio_video_calls": "Videollamadas",
    "screen_room_notification_settings_mode_mentions_and_keywords": "Solo menciones y respuestas",
    "screen_room_notification_settings_mode_mute": "Silenciar",
    "screen_encryption_reset_bullet_4": "Tendrás que volver a confirmar todos los dispositivos donde tengas la sesión abierta",
    "screen_encryption_reset_no_chats_message": "Parece que todavía no tienes chats. Si es así, puedes restablecer tu identidad digital sin problema",
    "screen_encryption_reset_no_options_subtitle": "No tienes acceso a otro dispositivo verificado ni a una clave de recuperación, así que tendrás que restablecer tu identidad digital para seguir usando la app.",
    "screen_encryption_reset_no_options_title": "Tienes que restablecer tu identidad digital",
    "screen_encryption_reset_subtitle": "Si no tienes acceso a otro dispositivo verificado ni tu clave de recuperación, tendrás que restablecer tu identidad digital para seguir usando la app.",
    "screen_signed_out_subtitle_v2": "Este dispositivo fue removido por una de las razones de abajo. Inicia sesión de nuevo para conectarlo a tu cuenta.",
    "screen_session_verification_unavailable_subtitle": "Usa otro dispositivo o tu clave de recuperación.",
    "screen_session_verification_unavailable_title": "La verificación con este dispositivo no está disponible por ahora",
    "screen_session_verification_user_requesting_verification": "Persona que pide la verificación:",
    "notification_knock_request_body": "Pidió entrar",
    "troubleshoot_notifications_test_push_loop_back_failure_rate_limit": "Error: el servidor de avisos está limitando este dispositivo y las notificaciones no serán confiables. Configura otro servidor de avisos.",
    "screen_room_select_error_cannot_load_rooms": "No se pudo cargar la lista de chats de esta cuenta.",
    "action_clear_all": "Borrar todo",
    "action_copy_to_clipboard": "Copiar",
    "common_account_settings": "Ajustes de la cuenta",
    "common_app_settings": "Ajustes de la app",
    "common_chats": "Chats",
    "common_code_block": "Bloque de código",
    "common_forward_to": "Reenviar a",
    "common_header_rooms": "Salas",
    "common_location_sharing": "Compartir ubicación",
    "common_media_upload_quality": "Calidad de subida de fotos y videos",
    "common_moderation_and_safety": "Moderación y seguridad",
    "common_no_results_for": "No hay resultados para “%1$s”. Prueba con otra búsqueda.",
    "common_results_for": "Resultados para “%1$s”",
    "common_signed_in": "Sesión iniciada",
    "common_switch_accounts": "Cambiar de cuenta",
    "common_your_account": "Tu cuenta",
    "error_account_already_logged_in_title": "Sesión duplicada",
    "screen_room_maximum_messages_selected": "Máximo de mensajes seleccionados",
    "screen_search_empty_state_message": "Busca chats y mensajes",
    "screen_search_empty_state_title": "Empieza a buscar",
    "screen_search_no_results_message": "No hay resultados para “%1$@”. Prueba con otra búsqueda.",
    "screen_search_recent_searches": "Búsquedas recientes",
    "screen_search_tab_chats": "Chats",
    "screen_search_tab_messages": "Mensajes",
    "test_untranslated_default_language_identifier": "es",
}
PLURALES = {
    "action_forward_messages": ("Reenviar mensaje", "Reenviar mensajes"),
    "screen_room_messages_forwarded": ("Mensaje reenviado", "Mensajes reenviados"),
    "screen_room_selection_count": ("%1$d seleccionado", "%1$d seleccionados"),
}
# Palabras de aquí, en venezolano
VENEZOLANO = {
    "screen_onboarding_welcome_title": "Aquí hablamos como somos",
}

def esc(v):
    return v.replace("'", "\\'").replace("&", "&amp;") if "&amp;" not in v else v.replace("'", "\\'")

def cuerpo(v):
    return '"' + v + '"'

n_str = n_pl = n_falta = 0
for d in glob.glob(os.path.join(root, "**/src/main/res/values/localazy.xml"), recursive=True):
    res = os.path.dirname(os.path.dirname(d))
    t = open(d, encoding="utf8").read()
    es_path = os.path.join(res, "values-es", "translations.xml")
    es = open(es_path, encoding="utf8").read() if os.path.exists(es_path) else ""
    es_str = {m.group(1): m.group(2) for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', es, re.S)}
    es_pl = {m.group(1): m.group(0) for m in re.finditer(r'<plurals name="([^"]+)">.*?</plurals>', es, re.S)}

    def rs(m):
        global n_str, n_falta
        nombre = m.group(1)
        if nombre in es_str:
            n_str += 1
            return '<string name="%s">%s</string>' % (nombre, es_str[nombre])
        if nombre in FALTAN:
            n_falta += 1
            return '<string name="%s">%s</string>' % (nombre, cuerpo(esc(FALTAN[nombre])))
        return m.group(0)
    t = re.sub(r'<string name="([^"]+)">(.*?)</string>', rs, t, flags=re.S)

    def rp(m):
        global n_pl
        nombre = m.group(1)
        if nombre in es_pl:
            n_pl += 1
            return es_pl[nombre]
        if nombre in PLURALES:
            n_pl += 1
            u, o = PLURALES[nombre]
            return '<plurals name="%s">\n    <item quantity="one">"%s"</item>\n    <item quantity="other">"%s"</item>\n  </plurals>' % (nombre, esc(u), esc(o))
        return m.group(0)
    t = re.sub(r'<plurals name="([^"]+)">.*?</plurals>', rp, t, flags=re.S)
    open(d, "w", encoding="utf8").write(t)

    # Las demás lenguas se quitan: si el teléfono está en otro idioma, igual se ve en español.
    for carpeta in glob.glob(os.path.join(res, "values-*")):
        nombre = os.path.basename(carpeta)
        if nombre.startswith("values-es"):
            continue
        for f in glob.glob(os.path.join(carpeta, "translations.xml")):
            os.remove(f)

print("español: cadenas", n_str, "completadas", n_falta, "plurales", n_pl)
