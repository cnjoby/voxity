package com.sonar.app

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientRulesTest {
    @Test
    fun cachedCoordinateWithRefreshedElapsedTimeCannotBecomeFresh() {
        val wall = 1_790_000_000_000L
        val elapsed = 300_000_000_000L
        assertTrue(freshLocationSample(wall - 1_000, elapsed - 1_000_000_000L, wall, elapsed))
        assertFalse(freshLocationSample(wall - 254_000, elapsed - 1_000_000_000L, wall, elapsed))
        assertFalse(freshLocationSample(wall - 1_000, elapsed - 56_000_000_000L, wall, elapsed))
        assertFalse(freshLocationSample(wall + 11_000, elapsed, wall, elapsed))
        assertFalse(freshLocationSample(wall, elapsed + 1, wall, elapsed))
    }

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

    @Test
    fun locationSnapshotAndTargetRemainIndependentAcrossRecovery() {
        val point =
            json(
                    "latitude" to 31.2,
                    "longitude" to 121.5,
                    "coordinate_system" to "wgs84",
                    "accuracy_m" to 10,
                    "sampled_at" to "2026-10-24T00:00:00Z",
                    "source" to "android",
                )
                .toString()
        val draft =
            LocalDraft(
                "clip",
                "private.wav",
                "那里堵不堵",
                frozen = true,
                targetPlaceId = "amap:remote",
                targetLabel = "远处的路口",
                recordedLocation = point,
            )
        val restored = LocalDraft.parse(draft.toJson())
        assertEquals(draft, restored)
        assertEquals("amap:remote", restored.metadata().text("target_place_id"))
        assertEquals("unknown", restored.metadata().text("direction"))
        assertEquals(
            31.2,
            restored.metadata().getJSONObject("recorded_location").getDouble("latitude"),
            0.0,
        )
        val reply = draft.copy(replyTo = "q", invitationId = "invite")
        assertFalse(reply.metadata().has("target_place_id"))
        assertTrue(reply.metadata().has("recorded_location"))
    }

    @Test
    fun publicVoiceAndDirectedReplyMetadataSurviveDraftRecovery() {
        val voice =
            LocalDraft(
                "clip",
                "private.wav",
                "看落日",
                targetPlaceId = "scenic",
                voiceMetadata =
                    json(
                            "kind" to "scene",
                            "target_place_id" to "scenic",
                            "purpose" to "reflection",
                            "visibility" to "public",
                        )
                        .toString(),
            )
        assertEquals(voice, LocalDraft.parse(voice.toJson()))
        assertEquals("scene", voice.metadata().text("kind"))
        assertFalse(voice.metadata().has("direction"))
        val reply =
            voice.copy(
                replyTo = "question",
                voiceMetadata =
                    json(
                            "kind" to "reply",
                            "root_question_id" to "question",
                            "reply_to" to "parent",
                            "recipient_id" to "bob",
                            "visibility" to "public",
                        )
                        .toString(),
                replyQuote = "上一句话",
            )
        assertEquals(reply, LocalDraft.parse(reply.toJson()))
        assertEquals("parent", reply.metadata().text("reply_to"))
        assertEquals("bob", reply.metadata().text("recipient_id"))
        assertFalse(reply.metadata().has("invitation_id"))
        assertFalse(reply.metadata().has("target_place_id"))
    }

    @Test
    fun receiptRetryDoesNotMarkANewVisitAsHeard() = runBlocking {
        MockWebServer().use { server ->
            val delivery = fixture("audio-delivery.json").put("request_id", "new-request")
            delivery.getJSONObject("payload").put("visit_id", "new-visit")
            server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            server.enqueue(MockResponse().setBody(json("delivery" to delivery).toString()))
            val api = HttpSonarApi(server.url("/").toString(), "secret", ServerClock { 0 }, true)
            sendStoredAck(
                api,
                json(
                    "id" to delivery.getString("id"),
                    "request_id" to "old-request",
                    "post_id" to "a_demo_001",
                    "visit_id" to "old-visit",
                    "event" to "played",
                ),
            )
            assertEquals(2, server.requestCount)
            val newVisit = Delivery.parse(delivery)
            val oldVisit =
                Delivery.parse(
                    JSONObject(delivery.toString()).apply {
                        getJSONObject("payload").put("visit_id", "old-visit")
                    }
                )
            assertNotEquals(newVisit.key, oldVisit.key)
            assertEquals(newVisit.key, newVisit.copy(requestId = "rotated-for-mute").key)
        }
    }
}
