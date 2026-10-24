package com.sonar.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExchangeRulesTest {
    private fun frame(value: Short) = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(320) { putShort(value) } }.array()

    @Test fun voiceStartsLocallyAndKeepsPrerollThenEndsOnSilence() {
        var starts = 0
        val chunks = mutableListOf<Pair<ByteArray, Boolean>>()
        val vad = SpeechSegments({ starts++ }, { bytes, cut -> chunks.add(bytes to cut) })
        repeat(10) { vad.accept(frame(0)) }
        repeat(20) { vad.accept(frame(3000)) }
        assertEquals(1, starts)
        assertTrue(vad.speaking)
        repeat(59) { vad.accept(frame(0)) }
        assertTrue(chunks.isEmpty())
        vad.accept(frame(0))
        assertEquals(1, chunks.size)
        assertFalse(chunks.single().second)
        assertTrue(chunks.single().first.copyOfRange(0, 640).all { it == 0.toByte() })
        assertFalse(vad.speaking)
    }

    @Test fun longSpeechIsFlaggedAndNeverSilentlySplitIntoNewPublicUtterances() {
        val chunks = mutableListOf<Pair<ByteArray, Boolean>>()
        val vad = SpeechSegments({}, { bytes, cut -> chunks.add(bytes to cut) })
        repeat(900) { vad.accept(frame(3000)) }
        assertEquals(1, chunks.size)
        assertEquals(256000, chunks.single().first.size)
        assertTrue(chunks.single().second)
        assertTrue(vad.active) // Still speaking after the bounded clip; playback must keep yielding.
        repeat(60) { vad.accept(frame(0)) }
        assertFalse(vad.active)
        repeat(20) { vad.accept(frame(3000)) }
        repeat(60) { vad.accept(frame(0)) }
        assertEquals(2, chunks.size)
        assertFalse(chunks.last().second)
    }

    @Test fun impulseAndSilenceDoNotCreatePublishableAudio() {
        var chunks = 0
        val vad = SpeechSegments({}, { _, _ -> chunks++ })
        repeat(600) { vad.accept(frame(0)) }
        repeat(4) { vad.accept(frame(3000)) }
        repeat(60) { vad.accept(frame(0)) }
        assertEquals(0, chunks)
    }

    @Test fun localCommandsAreWholeUtterancesAndNegationIsNotAReport() {
        assertEquals("quiet", localExchangeCommand("安静一下。"))
        assertEquals("resume", localExchangeCommand("恢复交流"))
        assertEquals("undo", localExchangeCommand("刚才那句别发"))
        assertEquals("stop", localExchangeCommand("停一下"))
        assertEquals("end", localExchangeCommand("关闭麦克风"))
        assertNull(localExchangeCommand("那条安静的小路堵死了"))
    }

    @Test fun speakerOriginalAndFragmentsCannotBeRebroadcastAsNewSpeech() {
        val original = "金科路中环路下面堵死了。"
        assertTrue(looksLikePlaybackEcho("金科路中环路下面堵死了", original))
        assertTrue(looksLikePlaybackEcho("下面堵死了", original))
        assertTrue(looksLikePlaybackEcho("金科路中环路下面堵住了", original))
        assertFalse(looksLikePlaybackEcho("我刚过那儿，右边已经能走了", original))
        assertFalse(looksLikePlaybackEcho("安静", original))
        assertTrue(looksLikePlaybackEcho("安静", "有人说安静一点", minLength = 2))
        assertFalse(looksLikePlaybackEcho("下面堵死了", null))
    }

    @Test fun naturalProtocolUploadsOriginalOnceWithoutCallingManualConfirm() = runBlocking {
        val server = MockWebServer()
        server.start()
        val audio = File.createTempFile("exchange-test", ".wav")
        try {
            val clock = ServerClock { 0 }
            val api = HttpSonarApi(server.url("/").toString(), "test-device-token", clock, true)
            server.enqueue(MockResponse().setBody("""{"session":{"id":"s","revision":1,"auto_publish":true},"server_time":"2026-10-25T00:00:00Z"}"""))
            val start = json("client_request_id" to "11111111-1111-4111-8111-111111111111", "expected_revision" to 1,
                "place_id" to "road", "auto_publish" to true, "auto_play" to true)
            assertEquals("s", api.startExchange(start).getJSONObject("session").getString("id"))
            val request = server.takeRequest()
            assertEquals("/v1/exchange/sessions", request.path)
            assertFalse(JSONObject(request.body.readUtf8()).has("user_id"))
            audio.writeBytes(Wav.synthetic())
            server.enqueue(MockResponse().setBody("""{"turn_id":"t","status":"published","post_id":"p","action":"scene","server_time":"2026-10-25T00:00:00Z"}"""))
            val result = api.exchangeUpload("s", audio, json("client_request_id" to "22222222-2222-4222-8222-222222222222",
                "expected_session_revision" to 1, "transcript" to "下面堵死了", "recorded_at" to clock.now().toString()))
            assertEquals("published", result.text("status"))
            val upload = server.takeRequest()
            assertEquals("/v1/exchange/sessions/s/utterances", upload.path)
            assertEquals("Bearer test-device-token", upload.getHeader("Authorization"))
            val bytes = upload.body.readByteArray()
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains("RIFF"))
            assertEquals(2, server.requestCount) // No per-utterance confirm request or assistant TTS.
        } finally { audio.delete(); server.shutdown() }
    }
}
