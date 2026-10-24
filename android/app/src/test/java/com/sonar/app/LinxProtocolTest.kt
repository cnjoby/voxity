package com.sonar.app

import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinxProtocolTest {
    private fun config(server: MockWebServer) =
        DeviceConfig(
            linxUrl = server.url("/v1/ws/").toString().replace("http://", "ws://"),
            linxToken = "private-linx-token",
            linxDevice = "02:01:02:03:04:05",
            clientId = UUID.randomUUID().toString(),
        )

    private fun clip() =
        File.createTempFile("linx-test", ".wav").apply { writeBytes(Wav.synthetic()) }

    @Test
    fun otaPendingBindingAndTokenAreDistinctAndSonarTokenIsNeverSent() = runBlocking {
        MockWebServer().use { server ->
            val gateway = LinxGateway(server.url("/v1/ota/").toString(), true)
            val config = config(server).copy(token = "private-sonar-token")
            server.enqueue(
                MockResponse()
                    .setBody(
                        json(
                                "activation" to
                                    json(
                                        "code" to "123456",
                                        "message" to "https://linx.qiniu.com/\n123456",
                                    ),
                                "firmware" to json("url" to "https://untrusted.invalid/firmware"),
                            )
                            .toString()
                    )
            )
            assertEquals(
                "123456",
                (gateway.report(config, "apk-sha") as LinxRegistration.Pending).code,
            )
            val request = server.takeRequest()
            assertEquals(config.linxDevice, request.getHeader("Device-Id"))
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(config.linxDevice, body.text("mac_address"))
            assertEquals("sonar-android", body.getJSONObject("board").text("type"))
            assertFalse(body.toString().contains("private-sonar-token"))
            server.enqueue(MockResponse().setResponseCode(202))
            assertFalse(gateway.activated(config))
            server.enqueue(MockResponse().setBody("{\"code\":500,\"data\":null}"))
            try {
                gateway.activated(config)
                fail("error treated as binding")
            } catch (_: java.io.IOException) {}
            server.enqueue(MockResponse().setBody("success"))
            assertTrue(gateway.activated(config))
            server.enqueue(
                MockResponse()
                    .setBody(
                        json(
                                "websocket" to
                                    json(
                                        "url" to "ws://xrobo-io.qiniuapi.com/v1/ws/",
                                        "token" to "secret",
                                    )
                            )
                            .toString()
                    )
            )
            val bound = gateway.report(config, "hash") as LinxRegistration.Bound
            assertEquals("wss://xrobo-io.qiniuapi.com/v1/ws/", bound.url)
            assertEquals("secret", bound.token)
            assertFalse(bound.toString().contains("secret"))
        }
    }

    @Test
    fun otaRejectsBodyErrorRedirectAndCredentialExfiltration() = runBlocking {
        MockWebServer().use { server ->
            val gateway = LinxGateway(server.url("/v1/ota/").toString(), true)
            for (response in
                listOf(
                    MockResponse().setBody("{\"error\":\"Invalid OTA request\"}"),
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "https://another.invalid/"),
                    MockResponse()
                        .setBody(
                            json(
                                    "websocket" to
                                        json("url" to "wss://another.invalid/", "token" to "secret")
                                )
                                .toString()
                        ),
                )) {
                server.enqueue(response)
                assertTrue(runCatching { gateway.report(config(server), "hash") }.isFailure)
            }
            assertEquals(3, server.requestCount)
        }
    }

    private fun server(
        server: MockWebServer,
        controls: MutableList<JSONObject>,
        frames: MutableList<ByteArray>,
        extraStt: Boolean = false,
        noAudio: Boolean = false,
        aborted: Boolean = false,
        session: String? = "session-1",
        reply: Boolean = true,
    ) {
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(ws: WebSocket, text: String) {
                            val value = JSONObject(text)
                            controls.add(value)
                            when {
                                value.text("type") == "hello" ->
                                    ws.send(
                                        json(
                                                "type" to "hello",
                                                "transport" to "websocket",
                                                "session_id" to session,
                                                "audio_params" to LinxAudioParams().hello(),
                                            )
                                            .toString()
                                    )
                                value.text("type") == "listen" && value.text("state") == "stop" -> {
                                    ws.send(
                                        json(
                                                "type" to "stt",
                                                "session_id" to session,
                                                "text" to "你好灵矽",
                                            )
                                            .toString()
                                    )
                                    if (extraStt)
                                        ws.send(
                                            json(
                                                    "type" to "stt",
                                                    "session_id" to session,
                                                    "text" to "另一个识别片段",
                                                )
                                                .toString()
                                        )
                                    if (reply) {
                                        ws.send(
                                            json(
                                                    "type" to "tts",
                                                    "state" to "start",
                                                    "session_id" to session,
                                                )
                                                .toString()
                                        )
                                        ws.send(
                                            json(
                                                    "type" to "tts",
                                                    "state" to "sentence_start",
                                                    "text" to "你好，我是灵矽助手",
                                                    "session_id" to session,
                                                )
                                                .toString()
                                        )
                                        if (!noAudio) ws.send(ByteString.of(*ByteArray(3200)))
                                        ws.send(
                                            json(
                                                    "type" to "tts",
                                                    "state" to "stop",
                                                    "is_aborted" to aborted,
                                                    "session_id" to session,
                                                )
                                                .toString()
                                        )
                                    }
                                }
                            }
                        }

                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                            frames.add(bytes.toByteArray())
                        }
                    }
                )
        )
    }

    @Test
    fun privatePcmRoundTripNegotiatesFramesAndKeepsAiTextSeparate() = runBlocking {
        MockWebServer().use { server ->
            val controls = CopyOnWriteArrayList<JSONObject>()
            val frames = CopyOnWriteArrayList<ByteArray>()
            server(server, controls, frames)
            val file = clip()
            try {
                val result = LinxSession(allowLocal = true).exchange(file, config(server))
                assertEquals("你好灵矽", result.transcript)
                assertEquals("你好，我是灵矽助手", result.assistantText)
                assertEquals(3200, result.audio!!.bytes.size)
                assertEquals(16000, result.audio.rate)
                assertEquals(3200, frames.sumOf { it.size })
                assertTrue(frames.all { it.size <= 640 })
                assertFalse(controls.first().getJSONObject("features").getBoolean("mcp"))
                assertEquals(
                    listOf("start", "stop"),
                    controls.filter { it.text("type") == "listen" }.map { it.text("state") },
                )
                val request = server.takeRequest()
                assertEquals("Bearer private-linx-token", request.getHeader("Authorization"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun publicTranscriptDoesNotReturnAssistantAudioAndSupportsOptionalSessionId() = runBlocking {
        MockWebServer().use { server ->
            val controls = CopyOnWriteArrayList<JSONObject>()
            server(server, controls, CopyOnWriteArrayList(), session = null)
            val file = clip()
            try {
                assertEquals(
                    "你好灵矽",
                    LinxSession(allowLocal = true).transcribe(file, config(server)),
                )
                assertTrue(
                    controls.filter { it.text("type") == "listen" }.all { !it.has("session_id") }
                )
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun transcriptionCanReturnCandidateWhenAgentDoesNotSpeak() = runBlocking {
        MockWebServer().use { server ->
            server(server, CopyOnWriteArrayList(), CopyOnWriteArrayList(), reply = false)
            val file = clip()
            try {
                assertEquals(
                    "你好灵矽",
                    LinxSession(allowLocal = true, responseMs = 3000)
                        .transcribe(file, config(server)),
                )
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun ambiguousSttAbortedOrEmptyRepliesDoNotBecomePlayableAudio() = runBlocking {
        for (scenario in 0..2) MockWebServer().use { server ->
            server(
                server,
                CopyOnWriteArrayList(),
                CopyOnWriteArrayList(),
                extraStt = scenario == 0,
                aborted = scenario == 1,
                noAudio = scenario == 2,
            )
            val file = clip()
            try {
                assertTrue(
                    runCatching { LinxSession(allowLocal = true).exchange(file, config(server)) }
                        .isFailure
                )
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun failedAuthHasNoCredentialsAndCancellationClosesSocket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val file = clip()
            try {
                try {
                    LinxSession(allowLocal = true).exchange(file, config(server))
                    fail("missing auth failure")
                } catch (e: LinxAuthFailure) {
                    assertFalse(e.message.orEmpty().contains("private-linx-token"))
                }
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                val call = async { LinxSession(allowLocal = true).exchange(file, config(server)) }
                delay(100)
                call.cancel()
                call.join()
                assertTrue(call.isCancelled)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun opusFrameGroupingPreservesDurationAndResampling() {
        val packet = byteArrayOf(0x78, 1, 2, 3) // One raw Opus frame of 20 ms.
        assertEquals(20000L, OpusPackets.durationUs(packet))
        val grouped = OpusPackets.group(List(3) { packet }, 60).single()
        assertEquals(60000L, OpusPackets.durationUs(grouped))
        assertEquals(3, grouped[0].toInt() and 3)
        assertEquals(9600, AndroidLinxCodec.resample(ByteArray(3200), 16000, 48000).size)
        assertTrue(
            runCatching {
                    LinxAudioParams.parse(
                        json("format" to "pcm", "sample_rate" to 16000, "channels" to 2)
                    )
                }
                .isFailure
        )
    }
}
