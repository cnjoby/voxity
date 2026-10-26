package com.sonar.app

import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual hearing diagnostic. Supply an existing cache fixture; never publishes or acknowledges it. */
class AudioPlaybackDiagnosticDeviceTest {
    @Test fun replayLocalFixtureThroughTheOriginalSpeakerPath() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val fixture = arguments.getString("audio_fixture")
        assumeTrue("Manual diagnostic requires audio_fixture", !fixture.isNullOrBlank())
        require(File(fixture!!).name == fixture)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context, ExchangeService::class.java))
        delay(1000)
        val source = File(context.cacheDir, fixture)
        assertTrue(source.isFile)
        val copy = File(context.cacheDir, "diagnostic-playback-copy.wav")
        val audio = AudioCoordinator(context)
        val manager = context.getSystemService(AudioManager::class.java)
        try {
            copy.writeBytes(originalPlaybackLevel(source.readBytes()))
            Log.i("SonarAudioDiagnostic", "volume=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} muted=${manager.isStreamMute(AudioManager.STREAM_MUSIC)}")
            repeat(2) {
                delay(3000)
                audio.play(copy, ownSpeaker = true, onStarted = {
                    Log.i("SonarAudioDiagnostic", "fixture_started pass=${it + 1}")
                })
                Log.i("SonarAudioDiagnostic", "fixture_completed pass=${it + 1}")
            }
        } finally {
            audio.close()
            copy.delete()
        }
    }
}
