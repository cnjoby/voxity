package com.sonar.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContractsTest {
    private fun fixtures() = JSONObject(javaClass.getResource("/contracts-v1.json")!!.readText())

    @Test fun answeredAndPlayedTasksRemainOpenUntilExplicitResolution() {
        val raw = json("id" to "task", "revision" to 2, "status" to "responded", "original_text" to "还有车位吗？",
            "deliveries" to org.json.JSONArray(listOf(json("state" to "played"))))
        val task = PersonalTask.parse(raw)
        assertFalse(task.terminal)
        assertEquals("已有回应", task.statusLabel)
        assertTrue(PersonalTask.parse(raw.put("status", "resolved")).terminal)
    }

    @Test fun recoveryReadsOriginalIdAndTaskCommandsKeepTheirIdempotencyKey() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = HttpSonarApi(server.url("/").toString(), "device", ServerClock { 0 }, true)
            server.enqueue(MockResponse().setBody("""{"turn":{"status":"published","post_id":"p"}}"""))
            assertEquals("p", api.requestResult("original-id").getJSONObject("turn").getString("post_id"))
            val read = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("/v1/requests/original-id", read.path)
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"status":"resolved"}"""))
                api.taskAction("task", 2, "same-command-id", "resolve")
                assertEquals("same-command-id", JSONObject(server.takeRequest().body.readUtf8()).getString("client_request_id"))
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun privateSessionDoesNotAcquirePublicOrPlaybackGrants() {
        val fixture = fixtures()
        val grants = SessionCapabilities.parse(fixture.getJSONObject("capabilities"))
        assertTrue(grants.privateQuery)
        assertTrue(grants.acceptsInput)
        assertFalse(grants.needsLocation)
        assertFalse(grants.requestHumans || grants.publishScene || grants.replyThread || grants.matchChat || grants.autoPlay)
        val request = grants.sessionRequest("r", 1, "road")
        assertFalse(request.has("auto_publish") || request.has("network_enabled") || request.has("user_id"))
        assertEquals(grants, SessionCapabilities.parse(request.getJSONObject("capabilities")))
    }

    @Test fun legacySessionsKeepTheirExactNetworkBoundary() {
        val legacy = SessionCapabilities.fromSession(fixtures().getJSONObject("legacy_session"))
        assertTrue(legacy.publishScene && legacy.replyThread && legacy.privateQuery && legacy.autoPlay)
        assertFalse(legacy.requestHumans || legacy.matchChat)
        val current = json("auto_publish" to true, "network_enabled" to true,
            "capabilities" to SessionCapabilities(privateQuery = true).toJson())
        assertEquals(SessionCapabilities(privateQuery = true), SessionCapabilities.fromSession(current))
    }

    @Test fun unknownOrMalformedGrantsFailClosed() {
        for (invalid in listOf(json("schema_version" to "2", "private_query" to true),
            json("schema_version" to "1", "private_query" to "true"))) {
            assertThrows(IllegalArgumentException::class.java) { SessionCapabilities.parse(invalid) }
        }
        assertFalse(SessionCapabilities.parse(json("schema_version" to "1")).acceptsInput)
    }

    @Test fun sourceAndAudioOriginRemainIndependentAndChatCannotBecomePublic() {
        val message = fixtures().getJSONObject("operator_message")
        val view = VoiceMessageView.parse(message)
        assertEquals("poi_operator", view.subject.getString("source_type"))
        assertEquals("human_original", view.body.getString("content_origin"))
        assertTrue(view.time.isNull("observed_at"))
        assertThrows(IllegalArgumentException::class.java) {
            VoiceMessageView.parse(JSONObject(message.toString()).put("kind", "chat"))
        }
    }

    @Test fun newAndLegacyResultsDistinguishAnsweredAndPlayedFromResolution() {
        assertEquals("information", AgentTurnResult.parse(fixtures().getJSONObject("private_result")).outcome)
        val old = json("turn_id" to "t", "status" to "published", "action" to "question", "post_id" to "p", "root_id" to "p")
        val result = AgentTurnResult.parse(old)
        assertEquals("human_request", result.outcome)
        assertEquals("p", result.conversationId)
        assertEquals("unknown", AgentTurnResult.parse(json("status" to "unrecognized")).outcome)
    }

    @Test fun privateQueryUsesUnifiedEndpointWithoutLegacyPublishFlags() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = HttpSonarApi(server.url("/").toString(), "test-device-token", ServerClock { 0 }, true)
            server.enqueue(MockResponse().setBody("""{"session":{"id":"s","revision":1,"authorization_format":"capabilities_v1","capabilities":{"schema_version":"1","private_query":true}},"server_time":"2026-10-25T02:00:00Z"}"""))
            val response = api.startExchange(SessionCapabilities(privateQuery = true).sessionRequest("r", 1, "road"))
            assertEquals(SessionCapabilities(privateQuery = true), SessionCapabilities.fromSession(response.getJSONObject("session")))
            val start = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(start.has("auto_publish") || start.has("network_enabled"))
            server.enqueue(MockResponse().setBody(fixtures().getJSONObject("private_result").toString()))
            val result = api.agentTurn("s", json("client_request_id" to "r", "expected_session_revision" to 1,
                "transcript" to "去公司路况怎样", "recorded_at" to "2026-10-25T02:00:00Z"))
            assertEquals("information", AgentTurnResult.parse(result).outcome)
            val turn = server.takeRequest()
            assertEquals("/v1/agent/sessions/s/turns", turn.path)
            assertEquals("Bearer test-device-token", turn.getHeader("Authorization"))
            assertFalse(JSONObject(turn.body.readUtf8()).has("user_id"))
        } finally { server.shutdown() }
    }
}
