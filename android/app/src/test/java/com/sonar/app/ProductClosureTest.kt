package com.sonar.app

import org.junit.Assert.*
import org.junit.Test

class ProductClosureTest {
    @Test fun realPlaceMigrationKeepsIdentityAndOnlyReplacesLocalSelectionMetadata() {
        val expected = mapOf("demo:modaily" to "B0K2KHYN77", "demo:ai-island-19" to "B0FFJSBSGR", "demo:zhangjiang-jinke" to "B0FFHPL61V")
        expected.forEach { (old, id) ->
            val before = DeviceConfig(token = "private-device", linxToken = "private-voice", linxDevice = "device", clientId = "client", testPlaceId = old)
            val next = before.migrateRemotePlace()
            assertEquals(id, next.remotePlaceId)
            assertEquals(before.token, next.token)
            assertEquals(before.linxToken, next.linxToken)
            assertEquals(before.linxDevice, next.linxDevice)
            assertEquals(before.clientId, next.clientId)
            assertEquals(before.scope, next.scope)
            assertTrue(next.testPlaceId.isEmpty())
            assertEquals(next, next.migrateRemotePlace())
        }
        val explicit = DeviceConfig(remotePlaceId = "B0K2KHYN77", testPlaceId = "demo:ai-island-19", mock = true,
            debugTranscript = true, simulatedLocation = true).migrateRemotePlace()
        assertEquals("B0K2KHYN77", explicit.remotePlaceId)
        assertFalse(explicit.mock || explicit.debugTranscript || explicit.simulatedLocation)
        val oldRoad = DeviceConfig(remotePlaceId = "B0FFHPHNSH", token = "keep", linxToken = "voice", linxDevice = "device")
        assertEquals(oldRoad.copy(remotePlaceId = "B0FFHPL61V"), oldRoad.migrateRemotePlace())
        assertEquals("", oldRoad.copy(remotePlaceId = "").migrateRemotePlace().remotePlaceId)
    }

    @Test fun compactSourcesKeepUnknownObservationSeparateFromRecordingAndDoNotInventScope() {
        val summary = sourceSummary(json("source_type" to "poi_operator", "author_label" to "停车场运营",
            "recorded_at" to "2026-10-25T02:00:00Z", "scope" to "floor", "scope_label" to "B2"))
        assertTrue(summary.contains("运营来源 · 真人原声"))
        assertTrue(summary.contains("录制（观察时间未提供）"))
        assertTrue(summary.contains("B2"))
        val unknown = sourceSummary(json("author_label" to "车友"))
        assertTrue(unknown.contains("时间未提供") && unknown.contains("适用范围未提供"))
        assertFalse(unknown.contains("观察时间："))
    }

    @Test fun toolsAndParticipantAudioHaveTheirOwnScopeLabels() {
        assertTrue(sourceSummary(json("provider" to "amap", "reporttime" to "2026-10-25 10:00:00")).contains("区县天气"))
        assertTrue(sourceSummary(json("provider" to "amap", "distance_m" to 5000, "server_time" to "2026-10-25T02:00:00Z")).contains("未知路段不代表畅通"))
        assertEquals("仅交流参与者可听", visibilityLabel("participants"))
        assertEquals("仅本人可见", visibilityLabel("private"))
        val chat = sourceSummary(json("kind" to "chat", "author_label" to "车友", "published_at" to "2026-10-25T02:00:00Z"))
        assertTrue(chat.contains("仅参与者对话") && chat.contains("发送"))
        assertFalse(chat.contains("观察时间"))
    }

    @Test fun oldTasksRemainReadableAndNewConversationUsesRealRoot() {
        val old = PersonalTask.parse(json("id" to "old", "revision" to 1, "status" to "responded", "original_text" to "问题"))
        assertNull(old.conversationId)
        assertFalse(old.terminal)
        val task = PersonalTask.parse(json("id" to "new", "revision" to 2, "status" to "resolved", "original_text" to "问题",
            "conversation_id" to "actual-scene", "place_id" to "poi", "last_result" to json("agent_response" to json("text" to "私人回应"))))
        assertEquals("actual-scene", task.conversationId)
        assertEquals("私人回应", task.responseText)
        assertTrue(task.terminal)
    }
}
