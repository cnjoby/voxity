package com.sonar.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Only an on-device recognizer, fed from the same captured PCM. Never opens a second mic. */
class LocalSpeechCommands(private val context: Context) {
    fun available() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun recognize(file: File): String? {
        if (Build.VERSION.SDK_INT < 33 || !available()) return null
        return withTimeoutOrNull(3500) {
            withContext(Dispatchers.Main.immediate) {
                coroutineScope {
                    val pipe = ParcelFileDescriptor.createPipe()
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    try {
                        launch(Dispatchers.IO) {
                            runCatching {
                                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                                    file.inputStream().use { input -> input.skip(44); input.copyTo(out) }
                                }
                            }
                        }
                        suspendCancellableCoroutine<String?> { continuation ->
                            fun finish(value: String?) { if (continuation.isActive) continuation.resume(value) }
                            recognizer.setRecognitionListener(object : RecognitionListener {
                                override fun onResults(results: Bundle?) = finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                                override fun onError(error: Int) = finish(null)
                                override fun onReadyForSpeech(params: Bundle?) {}
                                override fun onBeginningOfSpeech() {}
                                override fun onRmsChanged(rmsdB: Float) {}
                                override fun onBufferReceived(buffer: ByteArray?) {}
                                override fun onEndOfSpeech() {}
                                override fun onPartialResults(partialResults: Bundle?) {}
                                override fun onEvent(eventType: Int, params: Bundle?) {}
                            })
                            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                            })
                        }
                    } finally {
                        recognizer.destroy()
                        pipe.forEach { runCatching { it.close() } }
                    }
                }
            }
        }
    }
}
