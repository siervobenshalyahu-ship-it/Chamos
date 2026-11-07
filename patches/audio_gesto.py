# Notas de voz: mantén presionado el micrófono para grabar y suelta para enviar; desliza hacia arriba para dejarlo grabando
# (luego toca ■ para enviar) o hacia la izquierda para cancelar. Uso: python3 audio_gesto.py RAIZ_DEL_REPO
# Se ejecuta DESPUÉS de neon.py (que ya cambió la firma de StandardLayout).
import sys, os
root = sys.argv[1]
tc = os.path.join(root, "libraries/textcomposer/impl/src/main/kotlin/io/element/android/libraries/textcomposer/TextComposer.kt")
s = open(tc, encoding="utf-8").read()

def una(old, new):
    global s
    assert s.count(old) == 1, (old[:80], s.count(old))
    s = s.replace(old, new)

una("import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\n"
    "import androidx.compose.foundation.gestures.awaitEachGesture\n"
    "import androidx.compose.foundation.gestures.awaitFirstDown\n"
    "import androidx.compose.ui.input.pointer.PointerEventPass\n"
    "import androidx.compose.ui.input.pointer.pointerInput\n"
    "import kotlinx.coroutines.withTimeoutOrNull\n")

una("    val hapticFeedback = LocalHapticFeedback.current\n", '''    val hapticFeedback = LocalHapticFeedback.current

    // --- Notas de voz estilo WhatsApp: mantener = grabar, soltar = enviar; arriba = dejar grabando; izquierda = cancelar ---
    val gestoContexto = androidx.compose.ui.platform.LocalContext.current
    val eventoVoz = androidx.compose.runtime.rememberUpdatedState(onVoiceRecorderEvent)
    val estadoVoz = androidx.compose.runtime.rememberUpdatedState(voiceMessageState)
    val autoEnviar = remember { mutableStateOf(false) }
    val pararAlEmpezar = remember { mutableStateOf(false) }
    LaunchedEffect(voiceMessageState) {
        if (voiceMessageState is VoiceMessageState.Idle) {
            autoEnviar.value = false
            pararAlEmpezar.value = false
        } else if (voiceMessageState is VoiceMessageState.Recording && pararAlEmpezar.value) {
            pararAlEmpezar.value = false
            eventoVoz.value.invoke(VoiceMessageRecorderEvent.Stop)
        } else if (autoEnviar.value && voiceMessageState is VoiceMessageState.Preview && !voiceMessageState.isSending) {
            autoEnviar.value = false
            onSendVoiceMessage()
        }
    }
    val gestoMic: Modifier = if (voiceMessageState is VoiceMessageState.Idle && !canSendTextMessage && !composerMode.isEditing) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var toque = false
                withTimeoutOrNull(280) {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed) { toque = true; break }
                    }
                }
                if (toque) return@awaitEachGesture // toque corto: el botón funciona como siempre
                down.consume()
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                eventoVoz.value.invoke(VoiceMessageRecorderEvent.Start)
                val subir = 70.dp.toPx()
                val izquierda = 110.dp.toPx()
                var bloqueado = false
                var cancelado = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    if (!bloqueado && !cancelado) {
                        if (c.position.y - down.position.y < -subir) {
                            bloqueado = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            android.widget.Toast.makeText(gestoContexto, "🔒 Grabando. Toca ■ para enviar o 🗑 para borrar.", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (c.position.x - down.position.x < -izquierda) {
                            cancelado = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                }
                when {
                    cancelado -> eventoVoz.value.invoke(VoiceMessageRecorderEvent.Cancel)
                    bloqueado -> Unit
                    else -> {
                        autoEnviar.value = true
                        if (estadoVoz.value is VoiceMessageState.Recording) {
                            eventoVoz.value.invoke(VoiceMessageRecorderEvent.Stop)
                        } else {
                            pararAlEmpezar.value = true
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }
''')

# tocar ■ (grabación fija o iniciada con un toque) = parar y enviar solo
una('''                        endButtonClick = {
                            performHapticFeedback()
                            onVoiceRecorderEvent.invoke(VoiceMessageRecorderEvent.Stop)
                        },''', '''                        endButtonClick = {
                            performHapticFeedback()
                            autoEnviar.value = true
                            onVoiceRecorderEvent.invoke(VoiceMessageRecorderEvent.Stop)
                        },''')

una('''            onDictar = if (io.element.android.compound.theme.ChamosEstilo.dictado) onDictar else null,
''', '''            onDictar = if (io.element.android.compound.theme.ChamosEstilo.dictado) onDictar else null,
            gestoMic = gestoMic,
''')
una("    onDictar: (() -> Unit)? = null,\n", "    onDictar: (() -> Unit)? = null,\n    gestoMic: Modifier = Modifier,\n")

# el botón de la derecha de StandardLayout recibe el gesto
a = s.index("private fun StandardLayout(")
viejo = "                    .padding(bottom = 5.dp, top = 5.dp, end = 6.dp, start = 6.dp)\n                    .size(48.dp)\n"
i = s.index(viejo, a)
s = s[:i] + viejo + "                    .then(gestoMic)\n" + s[i + len(viejo):]
open(tc, "w", encoding="utf-8").write(s)
print("audio_gesto OK")
