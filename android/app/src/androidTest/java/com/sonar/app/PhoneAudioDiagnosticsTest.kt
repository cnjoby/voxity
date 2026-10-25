package com.sonar.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Explicit phone diagnosis: reuse private audio for STT only, never publish it. */
class PhoneAudioDiagnosticsTest {
    @Test fun playRetainedEspOriginalWithoutPublishing() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_phone") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "esp32-speaker-probe.wav")
        assumeTrue(original.exists())
        run {
            val output = File(context.cacheDir, "esp32-playback-level.wav")
            val audio = AudioCoordinator(context)
            val report = json("public_upload" to false, "original_bytes" to original.length())
            try {
                context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                kotlinx.coroutines.delay(1000)
                output.writeBytes(originalPlaybackLevel(original.readBytes()))
                File(context.filesDir, "esp32-speaker-diagnosis.json").writeText(report.put("stage", "prepared").toString())
                kotlinx.coroutines.withTimeout(15000) {
                    audio.play(output, ownSpeaker = true, onStarted = { report.put("started", true) })
                }
                report.put("completed", true)
            } catch (e: Exception) {
                report.put("error_type", e.javaClass.simpleName)
            } finally {
                audio.close(); output.delete()
                File(context.filesDir, "esp32-speaker-diagnosis.json").writeText(report.toString())
            }
        }
    }
    @Test fun checkPhoneTtsAndSpeakerWithoutPublishing() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_phone") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = File(context.cacheDir, "phone-speaker-probe.wav")
            val report = json("public_upload" to false)
            val audio = AudioCoordinator(context)
            try {
                AgentSpeech(context).synthesize("这是声呐的声音测试。", file)
                report.put("tts", "passed").put("bytes", file.length())
                audio.play(file, AudioMode.PROMPT, ownSpeaker = true, onStarted = { report.put("speaker_started", true) })
                report.put("speaker_completed", true)
            } catch (e: Exception) {
                report.put("error_type", e.javaClass.simpleName).put("error", e.message?.take(180))
            } finally {
                audio.close(); file.delete()
                File(context.filesDir, "phone-speaker-diagnosis.json").writeText(report.toString())
            }
        }
    }
    @Test fun transcribeLatestPrivateClipWithoutPublishing() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_phone") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ConfigStore(context).load()
        val latest = File(context.filesDir, "exchange-private/${config.scope}").listFiles()
            ?.filter { it.extension == "wav" }?.maxByOrNull { it.lastModified() }
        val report = json("private_clip_present" to (latest != null), "public_upload" to false)
        if (latest != null) {
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                report.put("transcript", LinxSession(allowLocal = true).transcribe(latest, config))
                report.put("stt", "passed")
            } catch (e: Exception) {
                report.put("stt", "failed").put("error_type", e.javaClass.simpleName)
                    .put("error", e.message?.take(180))
            }
            report.put("duration_ms", android.os.SystemClock.elapsedRealtime() - start)
        }
        File(context.filesDir, "phone-audio-diagnosis.json").writeText(report.toString())
    }
}
