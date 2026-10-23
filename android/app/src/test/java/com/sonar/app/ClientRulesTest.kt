package com.sonar.app

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientRulesTest {
    private fun fixture(name: String) =
        JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test
    fun sharedContractParsesWithoutChangingServerFields() {
        val me = Me.parse(fixture("me.json"))
        assertEquals("bob", me.profile.userId)
        val invitation = Delivery.parse(fixture("invitation.json"))
        assertEquals("invitation", invitation.type)
        assertEquals("q_demo_001", invitation.questionId)
        val original = Delivery.parse(fixture("audio-delivery.json"))
        assertEquals("/v1/audio/a_demo_001", original.payload.getString("audio_url"))
        assertEquals(0, fixture("empty-inbox.json").objects("items").size)
    }

    @Test
    fun audioGatePreventsRecordingAndPlaybackOverlap() {
        val gate = AudioGate()
        assertTrue(gate.enter(AudioMode.RECORDING))
        assertFalse(gate.enter(AudioMode.ORIGINAL))
        assertFalse(gate.enter(AudioMode.TRANSCRIBING))
        gate.leave()
        assertTrue(gate.enter(AudioMode.ORIGINAL))
        assertFalse(gate.enter(AudioMode.RECORDING))
    }

    @Test
    fun deadlineUsesMonotonicElapsedAndServerClock() {
        var elapsed = 1000L
        val clock = ServerClock { elapsed }
        val d = Delivery.parse(fixture("invitation.json"))
        assertFalse(clock.valid(d))
        clock.sync("2026-10-23T02:00:00Z")
        assertTrue(clock.valid(d))
        elapsed += 30000
        assertFalse(clock.valid(d))
        assertFalse(clock.valid(d.copy(actionable = false)))
    }

    @Test
    fun wavHeaderMatchesBackendFormat() {
        val bytes = Wav.synthetic()
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals(16000, b.getInt(24))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(bytes.size - 44, b.getInt(40))
    }

    @Test
    fun questionMetadataSeparatesTargetFromPresenceAndAnswerBinding() {
        val d = LocalDraft("recording-id", "private.wav", "堵不堵？")
        assertEquals("zhonghuan_jinke", d.metadata().getString("target_place_id"))
        assertFalse(d.metadata().has("user_id"))
        val answer = d.copy(replyTo = "question1", invitationId = "invitation1")
        assertEquals("answer", answer.metadata().getString("kind"))
        assertEquals("question1", answer.metadata().getString("reply_to"))
        assertFalse(answer.metadata().has("target_place_id"))
        assertEquals(d, LocalDraft.parse(d.toJson()))
    }

    @Test
    fun authMultipartAndIdempotentRecordingIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"post_id\":\"p1\",\"status\":\"draft\"}"))
            server.enqueue(MockResponse().setBody("{\"post_id\":\"p1\",\"status\":\"draft\"}"))
            val api =
                HttpSonarApi(
                    server.url("/").toString(),
                    "private-device-token",
                    ServerClock { 0 },
                    true,
                )
            val file = File.createTempFile("sonar", ".wav").apply { writeBytes(Wav.synthetic()) }
            try {
                val draft = LocalDraft("same-request-id", file.absolutePath, "堵不堵？")
                repeat(2) { assertEquals("p1", api.upload(draft).getString("post_id")) }
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("Bearer private-device-token", request.getHeader("Authorization"))
                    assertEquals("/v1/posts/drafts", request.path)
                    val body = request.body.readUtf8()
                    assertTrue(body.contains("same-request-id"))
                    assertTrue(body.contains("name=\"audio\"; filename=\"voice.wav\""))
                    assertFalse(body.contains("private-device-token"))
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun refusesCrossOriginCredentialForwardingAndRedirects() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpSonarApi(server.url("/").toString(), "secret", ServerClock { 0 }, true)
            try {
                api.download("https://another.invalid/private.wav")
                fail("cross origin allowed")
            } catch (_: IllegalArgumentException) {}
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://another.invalid/")
            )
            try {
                api.me()
                fail("redirect followed")
            } catch (e: ApiFailure) {
                assertEquals(302, e.status)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun unauthorizedErrorIsTypedAndDoesNotLeakCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody(
                        "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"设备凭证无效\",\"retryable\":false}}"
                    )
            )
            val api = HttpSonarApi(server.url("/").toString(), "secret", ServerClock { 0 }, true)
            try {
                api.me()
                fail("missing error")
            } catch (e: ApiFailure) {
                assertEquals(401, e.status)
                assertFalse(e.retryable)
                assertFalse(e.message.contains("secret"))
            }
        }
    }

    @Test
    fun completedAudioAckSurvivesModeRevisionWithoutReplaying() = runBlocking {
        MockWebServer().use { server ->
            val delivery = fixture("audio-delivery.json").put("request_id", "new-request")
            server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            server.enqueue(MockResponse().setBody(json("delivery" to delivery).toString()))
            server.enqueue(MockResponse().setBody("{}"))
            val api = HttpSonarApi(server.url("/").toString(), "secret", ServerClock { 0 }, true)
            sendStoredAck(
                api,
                json(
                    "id" to delivery.getString("id"),
                    "request_id" to "old-request",
                    "event" to "played",
                    "post_id" to "a_demo_001",
                ),
            )
            server.takeRequest()
            assertEquals("GET", server.takeRequest().method)
            val rebased = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("new-request", rebased.getString("request_id"))
            assertEquals("played", rebased.getString("event"))
        }
    }

    @Test
    fun ackDoesNotRebaseOntoDifferentContent() = runBlocking {
        MockWebServer().use { server ->
            val delivery = fixture("audio-delivery.json")
            server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            server.enqueue(MockResponse().setBody(json("delivery" to delivery).toString()))
            val api = HttpSonarApi(server.url("/").toString(), "secret", ServerClock { 0 }, true)
            sendStoredAck(
                api,
                json(
                    "id" to delivery.getString("id"),
                    "request_id" to "old",
                    "event" to "played",
                    "post_id" to "different-post",
                ),
            )
            assertEquals(2, server.requestCount)
        }
    }
}
