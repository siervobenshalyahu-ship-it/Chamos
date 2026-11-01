import sys, os, shutil
root, pdir = sys.argv[1], sys.argv[2]

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)

def copiar_panel(destino, paquete):
    t = open(os.path.join(pdir, "ChamosFondoPanel.kt"), encoding="utf-8").read().replace("package PKG", "package " + paquete)
    open(destino, "w", encoding="utf-8").write(t)

# 1) panel de fondos (lo usan Estilos y el botón dentro de cada chat)
copiar_panel(os.path.join(root, "features/home/impl/src/main/kotlin/io/element/android/features/home/impl/ChamosFondoPanel.kt"),
             "io.element.android.features.home.impl")
copiar_panel(os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/ChamosFondoPanel.kt"),
             "io.element.android.features.messages.impl.timeline")

# 2) botón de fondo dentro del chat
edit(os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesView.kt"), [
    ("""                                        onThreadsListClick = onThreadsListClick
                                    )
                                }""", """                                        onThreadsListClick = onThreadsListClick
                                    )
                                    io.element.android.features.messages.impl.timeline.ChamosFondoBoton(state.roomId.value)
                                }"""),
])

# 3) marco de luz de neón alrededor de toda la app
appdir = os.path.join(root, "app/src/main/kotlin/io/element/android/x")
shutil.copy(os.path.join(pdir, "ChamosMarco.kt"), os.path.join(appdir, "ChamosMarco.kt"))
edit(os.path.join(appdir, "MainActivity.kt"), [
    ("""                            modifier = Modifier,
                        )
                    }
                }""", """                            modifier = Modifier,
                        )
                    }
                    ChamosMarcoNeon()
                }"""),
])

# 4) dictado por voz en el compositor del chat
tc = os.path.join(root, "libraries/textcomposer/impl/src/main/kotlin/io/element/android/libraries/textcomposer/TextComposer.kt")
edit(tc, [
    ("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n"),
    ("    val markdown = when (state) {\n", """    // Dictado por voz (para quien no quiere escribir): abre el reconocimiento de voz del teléfono.
    val dictarContexto = androidx.compose.ui.platform.LocalContext.current
    val dictarScope = rememberCoroutineScope()
    val dictarLanzador = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == android.app.Activity.RESULT_OK) {
            val dicho = resultado.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!dicho.isNullOrBlank()) {
                val actual = when (state) {
                    is TextEditorState.Markdown -> state.state.text.value().toString()
                    is TextEditorState.Rich -> state.richTextEditorState.messageMarkdown
                }
                dictarScope.launch { state.setMarkdown(if (actual.isBlank()) dicho else actual.trimEnd() + " " + dicho) }
            }
        }
    }
    val onDictar: () -> Unit = {
        val intento = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "es-VE")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Habla y se escribe solo")
        try {
            dictarLanzador.launch(intento)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                dictarContexto,
                "Tu teléfono no tiene dictado por voz. Instala o activa la app Google o 'Servicios de voz de Google'.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    val markdown = when (state) {
"""),
    ("""            onResetComposerMode = onResetComposerMode,
        )
    }

    SoftKeyboardEffect""", """            onResetComposerMode = onResetComposerMode,
            onDictar = if (io.element.android.compound.theme.ChamosEstilo.dictado) onDictar else null,
        )
    }

    SoftKeyboardEffect"""),
    ("""    onResetComposerMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (isRoomEncrypted == false) {""", """    onResetComposerMode: () -> Unit,
    modifier: Modifier = Modifier,
    onDictar: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        if (isRoomEncrypted == false) {"""),
    ("""                    movableVoiceRecording()
                }
            }
            // To avoid loosing keyboard focus, the IconButton has to be defined here and has to be always enabled.
            val endButtonContentDescription = stringResource(endButtonParams.endButtonContentDescriptionResId)
""", """                    movableVoiceRecording()
                }
            }
            if (onDictar != null && voiceMessageState is VoiceMessageState.Idle) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 9.dp, bottom = 9.dp)
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(ElementTheme.colors.bgActionPrimaryRest)
                        .clickable { onDictar() }
                        .padding(horizontal = 12.dp),
                ) {
                    androidx.compose.material3.Text(
                        "\\uD83C\\uDFA4 Dictar",
                        color = ElementTheme.colors.textOnSolidPrimary,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            }
            // To avoid loosing keyboard focus, the IconButton has to be defined here and has to be always enabled.
            val endButtonContentDescription = stringResource(endButtonParams.endButtonContentDescriptionResId)
"""),
])
print("neon OK")
