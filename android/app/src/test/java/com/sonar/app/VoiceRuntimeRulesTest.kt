package com.sonar.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class VoiceRuntimeRulesTest {
    private fun frame(value: Short) = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(320) { putShort(value) }
    }.array()

    @Test fun twentySecondSpeechRemainsOneCompleteUtterance() {
        val clips = mutableListOf<Pair<ByteArray, Boolean>>()
        val vad = SpeechSegments({}, { pcm, cut -> clips.add(pcm to cut) })
        repeat(1000) { vad.accept(frame(3000)) }
        assertTrue(clips.isEmpty())
        repeat(60) { vad.accept(frame(0)) }
        assertEquals(1, clips.size)
        assertFalse(clips.single().second)
        assertTrue(clips.single().first.size > 640000)
    }

    @Test fun shortNaturalControlsDoNotRequireOldModeWords() {
        assertEquals("resume", localExchangeCommand("继续"))
        assertEquals("end", localExchangeCommand("结束。"))
        assertEquals("cancel", localExchangeCommand("不用等了"))
        assertNull(localExchangeCommand("这个问题已经结束了，另一个地方呢"))
        assertNull(localExchangeCommand("她刚才说继续往前走"))
    }

    @Test fun correlatedSpeakerFramesAreIgnoredButDifferentSpeechCanInterrupt() {
        var now = 0L
        val guard = PlaybackEchoGuard { now }
        val random = java.util.Random(79)
        val pcm = ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(16000) { putShort((random.nextInt(12000) - 6000).toShort()) }
        }.array()
        guard.begin(Wav.header(pcm.size) + pcm)
        now = 300
        assertTrue(guard.isEcho(pcm.copyOfRange(3200, 3840)))
        assertFalse(guard.isEcho(ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(320) { putShort((sin(it * 0.11) * 6000).toInt().toShort()) }
        }.array()))
        guard.stop(); now += 700
        assertFalse(guard.isEcho(pcm.copyOfRange(3200, 3840)))
    }

    @Test fun newProtocolAcceptsThirtySecondOriginalAndPreservesAuthorization() = runBlocking {
        val server = MockWebServer()
        server.start()
        val file = File.createTempFile("sonar-long", ".wav")
        try {
            val api = HttpSonarApi(server.url("/").toString(), "private-device-token", ServerClock { 0 }, true)
            val original = Wav.header(960000) + ByteArray(960000)
            file.writeBytes(original)
            server.enqueue(MockResponse().setBody("""{"status":"published","action":"answer"}"""))
            api.exchangeUpload("session", file, json("transcript" to "完整的一句长话"))
            val request = server.takeRequest()
            assertEquals("Bearer private-device-token", request.getHeader("Authorization"))
            assertTrue(request.body.size > 960000)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(original)))
            assertArrayEquals(original, api.download("/v1/audio/original"))
            assertEquals("Bearer private-device-token", server.takeRequest().getHeader("Authorization"))
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun cancellingAnInFlightTurnClosesHttpWithoutWaitingForCloudTimeout() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = HttpSonarApi(server.url("/").toString(), "control-test", ServerClock { 0 }, true)
            val pending = launch { api.agentTurn("s", json("transcript" to "一个正在处理的问题")) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest() }
            pending.cancel()
            withTimeout(1000) { pending.join() }
            assertEquals(1, server.requestCount)
            assertTrue(pending.isCancelled)
        } finally { server.shutdown() }
    }

}
