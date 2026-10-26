package com.sonar.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Native Chinese synthesis only; leaves accounts, network and public messages untouched. */
class PublicationSpeechDeviceTest {
    @Test fun priorVisitCannotExcludeANewArrivalWithTheSameDeliveryId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "publication-exclusion-test"
        context.deleteSharedPreferences("tasks-$name")
        try {
            val journal = TaskStore(context, name)
            journal.markAttempt("notice:visit:old")
            journal.defer("notice:visit:old", true)
            journal.markAttempt("answer:request")
            assertEquals(listOf("answer"), journal.excludedDeliveryIds())
            assertTrue(journal.attempted("notice:visit:old"))
            assertFalse(journal.attempted("notice:visit:new"))
        } finally { context.deleteSharedPreferences("tasks-$name") }
    }

    @Test fun publicationTimeProducesPlayableChineseAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "publication-time-device-test.wav")
        try {
            val text = publicationIntro(json("kind" to "announcement", "published_at" to "2026-10-25T15:45:22Z"), Instant.parse("2026-10-25T15:48:22Z"))
            AgentSpeech(context).synthesize(text, output)
            val data = output.readBytes()
            assertEquals("RIFF", String(data, 0, 4))
            assertTrue(data.size > 16000)
            assertTrue(data.drop(44).any { it != 0.toByte() })
        } finally { output.delete() }
    }
}
