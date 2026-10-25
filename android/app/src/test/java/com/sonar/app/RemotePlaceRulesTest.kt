package com.sonar.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RemotePlaceRulesTest {
    @Test fun selectedPointUsesAuthenticatedDedicatedEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val api = HttpSonarApi(server.url("/").toString(), "private-test-token", ServerClock { 0 }, true)
            api.remotePlace(7, "B0K2KHYN77")
            val r = server.takeRequest()
            assertEquals("PUT", r.method)
            assertEquals("/v1/me/remote-place", r.path)
            assertEquals("Bearer private-test-token", r.getHeader("Authorization"))
            val body = org.json.JSONObject(r.body.readUtf8())
            assertEquals(7, body.getInt("expected_revision"))
            assertEquals("B0K2KHYN77", body.getString("place_id"))
            assertFalse(body.has("latitude"))
        }
    }

    @Test fun syntheticSourceIsNeverLabelledHumanOriginal() {
        val summary = sourceSummary(json("synthetic" to true, "content_origin" to "agent_synthesized", "place_name" to "模力社区"))
        assertTrue(summary.contains("合成测试"))
        assertFalse(summary.contains("真人原声"))
    }
}
