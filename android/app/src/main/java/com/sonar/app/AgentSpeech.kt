package com.sonar.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Only the personal Agent's text uses TTS. Peer voices always use their original WAV. */
class AgentSpeech(private val context: Context) {
    suspend fun synthesize(text: String, output: File): File = withContext(Dispatchers.Main.immediate) {
        val ready = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }
        try {
            withTimeout(15000) {
                check(ready.await()) { "本机语音暂不可用，请停车后检查语音" }
                check(engine.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) {
                    "本机中文语音尚未就绪，请停车后检查语音"
                }
                val id = UUID.randomUUID().toString()
                suspendCancellableCoroutine<Unit> { continuation ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                        }
                        @Deprecated("Android compatibility callback")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == id && continuation.isActive)
                                continuation.resumeWithException(IllegalStateException("语音生成失败，请停车后检查语音"))
                        }
                    })
                    continuation.invokeOnCancellation { engine.stop() }
                    if (engine.synthesizeToFile(text.take(1000), null, output, id) != TextToSpeech.SUCCESS && continuation.isActive)
                        continuation.resumeWithException(IllegalStateException("本机语音暂不可用，请停车后检查语音"))
                }
            }
            check(output.length() > 44) { "本机语音暂不可用，请停车后检查语音" }
            output
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }
}
