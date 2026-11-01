import sys, os
root = sys.argv[1]

AVISO = '''
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
print("voz OK")
