import sys, os
root = sys.argv[1]

AVISO = '''
/** Si tras pedir el permiso del micrófono el teléfono lo rechaza en silencio (Vivo/Xiaomi), avisa y abre los ajustes. */
internal fun chamosVerificarMicrofono() {
    try {
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val concedido = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (concedido) return@postDelayed
                val visible = try {
                    val owner = Class.forName("androidx.lifecycle.ProcessLifecycleOwner").getMethod("get").invoke(null)
                    val estado = owner.javaClass.getMethod("getLifecycle").invoke(owner)
                    estado.javaClass.getMethod("getCurrentState").invoke(estado).toString() == "RESUMED"
                } catch (_: Throwable) { true }
                if (!visible) return@postDelayed
                android.widget.Toast.makeText(app, "Chamos no tiene permiso de micrófono. Actívalo en Permisos > Micrófono.", android.widget.Toast.LENGTH_LONG).show()
                val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + app.packageName))
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
            } catch (_: Throwable) {
            }
        }, 2500)
    } catch (_: Throwable) {
    }
}

/** Muestra un aviso en pantalla (para saber por qué falla la grabación de notas de voz). */
internal fun chamosAviso(texto: String) {
    try {
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, texto, android.widget.Toast.LENGTH_LONG).show()
        }
    } catch (_: Throwable) {
    }
}
'''

def parchear(ruta, cambios, extra=""):
    t = open(ruta, encoding="utf-8").read()
    for old, new in cambios:
        assert t.count(old) == 1, (ruta, old[:70])
        t = t.replace(old, new)
    t += extra
    open(ruta, "w", encoding="utf-8").write(t)

# 1) Grabadora: avisar cuando algo falla en vez de quedarse callada
base = os.path.join(root, "libraries/voicerecorder/impl/src/main/kotlin/io/element/android/libraries/voicerecorder/impl")
parchear(os.path.join(base, "DefaultVoiceRecorder.kt"), [
    ("            audioRecorder.record { audio ->\n", "            try {\n            audioRecorder.record { audio ->\n"),
    ('''                        _state.emit(VoiceRecorderState.Recording(elapsedTime, listOf()))
                    }
                }
            }
        }
    }
''', '''                        _state.emit(VoiceRecorderState.Recording(elapsedTime, listOf()))
                    }
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "Voice recorder failed")
                chamosAviso("No se pudo grabar: ${e.message ?: e.javaClass.simpleName}")
                recordingJob = null
                runCatching { audioReader?.stop() }
                audioReader = null
                runCatching { encoder.release() }
                _state.emit(VoiceRecorderState.Idle)
            }
        }
    }
'''),
], AVISO)

parchear(os.path.join(base, "audio/AndroidAudioReader.kt"), [
    ('''        audioRecord.startRecording()
        withContext(dispatchers.io) {''', '''        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("El micrófono no está disponible (permiso bloqueado por el teléfono o en uso por otra app)")
        }
        withContext(dispatchers.io) {'''),
])

# 2) Presentador: avisar si la grabación no puede empezar
pres = os.path.join(root, "features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/voicemessages/composer/DefaultVoiceMessageComposerPresenter.kt")
parchear(pres, [
    ('''                            permissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                    }
                }
                VoiceMessageRecorderEvent.Stop''', '''                            permissionState.eventSink(PermissionsEvent.RequestPermissions)
                            chamosVerificarMicrofono()
                        }
                    }
                }
                VoiceMessageRecorderEvent.Stop'''),
    ('''            analyticsService.trackError(VoiceMessageException.PermissionMissing("Expected permission to record but none", e))
        }
    }''', '''            analyticsService.trackError(VoiceMessageException.PermissionMissing("Expected permission to record but none", e))
            chamosAviso("Falta el permiso del micrófono. Actívalo en Ajustes > Aplicaciones > Chamos > Permisos.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            audioFocus.releaseAudioFocus()
            Timber.e(e, "Voice message error")
            chamosAviso("No se pudo iniciar la grabación: ${e.message ?: e.javaClass.simpleName}")
        }
    }'''),
], AVISO)

# 3) Al tocar el botón de parar mientras se graba, el audio se envía directo (sin pasar por la vista previa).
parchear(pres, [
    ('''                VoiceMessageRecorderEvent.Stop -> {
                    Timber.v("Voice message stop button pressed")
                    localCoroutineScope.finishRecording()
                }''', '''                VoiceMessageRecorderEvent.Stop -> {
                    Timber.v("Voice message stop button pressed: se envía directo")
                    val chamosRespuesta = (messageComposerContext.composerMode as? MessageComposerMode.Reply)?.eventId
                    localCoroutineScope.launch {
                        voiceRecorder.stopRecord()
                        audioFocus.releaseAudioFocus()
                        val terminado = kotlinx.coroutines.withTimeoutOrNull(4000) {
                            voiceRecorder.state.first { it !is VoiceRecorderState.Recording }
                        } as? VoiceRecorderState.Finished
                        if (terminado != null && !isSending) {
                            isSending = true
                            player.pause()
                            analyticsService.captureComposerEvent()
                            sessionCoroutineScope.launch {
                                val resultado = sendMessage(
                                    file = terminado.file,
                                    mimeType = terminado.mimeType,
                                    waveform = terminado.waveform,
                                    inReplyToEventId = chamosRespuesta,
                                )
                                if (resultado.isFailure) {
                                    showSendFailureDialog = true
                                }
                            }.invokeOnCompletion {
                                isSending = false
                            }
                        }
                    }
                }'''),
    ("import kotlinx.coroutines.launch\n", "import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.launch\n"),
])
print("voz OK")
