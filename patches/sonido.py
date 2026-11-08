# Sonido en las notificaciones de mensajes. Uso: python3 sonido.py RAIZ_DEL_REPO
# Problema: Element deja en el canal "silencioso" los mensajes que las reglas de Matrix no marcan con sonido
# (por ejemplo los de grupos), y el tono propio puede no sonar en algunos teléfonos.
# Arreglo: todos los mensajes van por un canal nuevo de importancia alta, con el tono de notificación del teléfono.
import sys, os, re
root = sys.argv[1]
f = os.path.join(root, "libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/channels/NotificationChannels.kt")
s = open(f, encoding="utf-8").read()

# 1) canal nuevo (los canales de Android no se pueden cambiar una vez creados)
old = 'internal const val NOISY_NOTIFICATION_CHANNEL_ID_BASE = "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_V2"'
assert s.count(old) == 1, "sonido: no se encontro NOISY_NOTIFICATION_CHANNEL_ID_BASE"
s = s.replace(old, 'internal const val NOISY_NOTIFICATION_CHANNEL_ID_BASE = "CHAMOS_MENSAJES_V1"')

# 2) importancia alta: suena y aparece arriba
old = "val builder = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_DEFAULT)\n            .setName(stringProvider.getString(R.string.notification_channel_noisy)"
assert s.count(old) == 1, "sonido: no se encontro buildNoisyChannel"
s = s.replace(old, old.replace("IMPORTANCE_DEFAULT", "IMPORTANCE_HIGH"))

# 3) tono del sistema en vez del archivo propio
old = """        NotificationSound.SystemDefault -> Settings.System.DEFAULT_NOTIFICATION_URI
        NotificationSound.ElementDefault -> bundledMessageSoundUri()
        NotificationSound.ElementFade -> bundledFadeSoundUri()"""
assert s.count(old) == 1, "sonido: no se encontro resolveNoisySoundUri"
s = s.replace(old, """        NotificationSound.SystemDefault -> Settings.System.DEFAULT_NOTIFICATION_URI
        NotificationSound.ElementDefault -> Settings.System.DEFAULT_NOTIFICATION_URI
        NotificationSound.ElementFade -> Settings.System.DEFAULT_NOTIFICATION_URI""")

# 4) todos los mensajes por el canal con sonido (el parametro noisy se sigue usando)
old = """        } else {
            SILENT_NOTIFICATION_CHANNEL_ID
        }
    }

    override fun getChannelIdForTest"""
assert s.count(old) == 1, "sonido: no se encontro getChannelIdForMessage"
s = s.replace(old, """        } else {
            // Chamos: tambien los mensajes que Matrix marca sin sonido (grupos) suenan.
            enterpriseService.getNoisyNotificationChannelId(sessionId)
                ?: currentNoisyChannelId
        }
    }

    override fun getChannelIdForTest""")
open(f, "w", encoding="utf-8").write(s)
print("sonido OK")
