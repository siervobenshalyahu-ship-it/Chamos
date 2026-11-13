/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl

import android.Manifest
import androidx.annotation.RequiresPermission
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.VoiceMessageConfig
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.impl.audio.Audio
import io.element.android.libraries.voicerecorder.impl.audio.AudioConfig
import io.element.android.libraries.voicerecorder.impl.audio.AudioLevelCalculator
import io.element.android.libraries.voicerecorder.impl.audio.AudioReader
import io.element.android.libraries.voicerecorder.impl.audio.Encoder
import io.element.android.libraries.voicerecorder.impl.audio.resample
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileConfig
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class)
class DefaultVoiceRecorder(
    private val dispatchers: CoroutineDispatchers,
    private val timeSource: TimeSource,
    private val audioReaderFactory: AudioReader.Factory,
    private val encoder: Encoder,
    private val fileManager: VoiceFileManager,
    private val config: AudioConfig,
    private val fileConfig: VoiceFileConfig,
    private val audioLevelCalculator: AudioLevelCalculator,
    @SessionCoroutineScope
    sessionCoroutineScope: CoroutineScope,
) : VoiceRecorder {
    private val voiceCoroutineScope by lazy {
        sessionCoroutineScope.childScope(dispatchers.io, "VoiceRecorder-${UUID.randomUUID()}")
    }

    private var outputFile: File? = null
    private var audioReader: AudioReader? = null
    private var recordingJob: Job? = null

    // List of Float between 0 and 1 representing the audio levels
    private val levels: MutableList<Float> = mutableListOf()
    private val lock = Mutex()

    private val _state = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)
    override val state: StateFlow<VoiceRecorderState> = _state

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startRecord() = lock.withLock {
        if (recordingJob != null) {
            Timber.w("Voice recorder is already recording, ignoring this start")
            return@withLock
        }

        Timber.i("Voice recorder started recording")
        outputFile = fileManager.createFile()
            .also(encoder::init)

        levels.clear()

        val audioRecorder = audioReaderFactory.create(config, dispatchers).also { audioReader = it }

        recordingJob = voiceCoroutineScope.launch {
            val startedAt = timeSource.markNow()
            try {
            audioRecorder.record { audio ->
                yield()

                val elapsedTime = startedAt.elapsedNow()

                if (elapsedTime > VoiceMessageConfig.maxVoiceMessageDuration) {
                    Timber.w("Voice message time limit reached")
                    stopRecord(false)
                    return@record
                }

                when (audio) {
                    is Audio.Data -> {
                        val audioLevel = audioLevelCalculator.calculateAudioLevel(audio.buffer)

                        lock.withLock {
                            levels.add(audioLevel)
                            _state.emit(VoiceRecorderState.Recording(elapsedTime, levels.toList()))
                        }
                        encoder.encode(audio.buffer, audio.readSize)
                    }
                    is Audio.Error -> {
                        Timber.e("Voice message error: code=${audio.audioRecordErrorCode}")
                        _state.emit(VoiceRecorderState.Recording(elapsedTime, listOf()))
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

    /**
     * Stop the current recording.
     *
     * Call [deleteRecording] to delete any recorded audio.
     */
    override suspend fun stopRecord(
        cancelled: Boolean
    ) {
        recordingJob?.cancel()?.also {
            Timber.i("Voice recorder stopped recording")
        }
        recordingJob = null

        audioReader?.stop()
        audioReader = null
        encoder.release()

        lock.withLock {
            if (cancelled) {
                deleteRecording()
                levels.clear()
            }

            _state.emit(
                when (val file = outputFile) {
                    null -> VoiceRecorderState.Idle
                    else -> {
                        val duration = (state.value as? VoiceRecorderState.Recording)?.elapsedTime
                        VoiceRecorderState.Finished(
                            file = file,
                            mimeType = fileConfig.mimeType,
                            waveform = levels.resample(100),
                            duration = duration ?: 0.milliseconds
                        )
                    }
                }
            )
        }
    }

    /**
     * Stop the current recording and delete the output file.
     */
    override suspend fun deleteRecording() {
        outputFile?.let(fileManager::deleteFile)?.also {
            Timber.i("Voice recorder deleted recording")
        }
        outputFile = null
        _state.emit(VoiceRecorderState.Idle)
    }
}

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
