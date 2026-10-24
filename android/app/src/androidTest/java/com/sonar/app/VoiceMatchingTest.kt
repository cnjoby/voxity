package com.sonar.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class VoiceMatchingTest {
    @get:Rule(order = 0)
    val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val vm
        get() = ViewModelProvider(compose.activity)[SonarViewModel::class.java]

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(200) // Let the Android compositor finish dialog/keyboard surfaces.
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "test-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun finishAndPublish(text: String) {
        compose.waitUntil(8000) { vm.state.value.recording }
        Thread.sleep(800)
        compose.onNodeWithTag("record").performScrollTo().performClick()
        compose.waitUntil(8000) { vm.state.value.draft != null && !vm.state.value.recording }
        compose.onNodeWithTag("transcript").performScrollTo().performTextInput(text)
        compose.onNodeWithTag("publish").performScrollTo().performClick()
        compose.waitUntil(15000) { vm.state.value.draft == null && !vm.state.value.busy }
    }

    @Test
    fun placeVoicesVersionChecksRecordingAndDirectedConversation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("voice_matching") == "true")
        compose.waitUntil(15000) { vm.state.value.connected }
        val config = ConfigStore(compose.activity).load()
        val bob =
            DeviceConfig.parse(
                JSONObject(File(compose.activity.filesDir, "voice-peer.json").readText())
            )
        val clock = ServerClock(android.os.SystemClock::elapsedRealtime)
        val api = HttpSonarApi(config.baseUrl, config.token, clock, true)
        val peer =
            HttpSonarApi(
                bob.baseUrl,
                bob.token,
                ServerClock(android.os.SystemClock::elapsedRealtime),
                true,
            )
        val file =
            File(compose.activity.cacheDir, "synthetic-peer.wav").apply {
                writeBytes(Wav.synthetic())
            }
        suspend fun publishVoice(
            kind: String,
            text: String,
            extra: JSONObject = JSONObject(),
        ): String {
            val metadata =
                json(
                    "kind" to kind,
                    "target_place_id" to "voice_test",
                    "purpose" to if (kind == "knowledge") "explanation" else "notice",
                )
            extra.keys().forEach { metadata.put(it, extra.get(it)) }
            if (kind == "announcement")
                metadata
                    .put("channel_id", "parking")
                    .put("valid_from", Instant.now().minusSeconds(1))
                    .put("valid_until", Instant.now().plusSeconds(300))
            val local =
                LocalDraft(
                    UUID.randomUUID().toString(),
                    file.absolutePath,
                    text,
                    targetPlaceId = "voice_test",
                    voiceMetadata = metadata.toString(),
                )
            val id = peer.upload(local).getString("post_id")
            peer.confirm(id)
            return id
        }
        val old = publishVoice("announcement", "B2 已满，请前往 B3")
        val knowledge =
            (0 until 10).map {
                publishVoice(
                    "knowledge",
                    "停车知识 $it",
                    json("source_note" to "演示指示牌核对", "channel_id" to "parking"),
                )
            }
        compose.runOnIdle {
            vm.selectPlace(PlaceChoice("voice_test", "演示观景点停车场"))
            vm.browseVoices()
        }
        compose.waitUntil(15000) {
            vm.state.value.catalog.size == 11 &&
                vm.state.value.deliveries.any { it.payload.text("post_id") == old }
        }
        assertTrue(vm.state.value.quiet)
        assertFalse(
            vm.state.value.deliveries.first { it.payload.text("post_id") == old }.actionable
        )
        compose.onNodeWithTag("browse-voices").performScrollTo()
        screenshot("voice-catalog")
        compose.onNodeWithTag("share-voice").performScrollTo().performClick()
        compose.onNodeWithText("确认声音的地点与用途").assertExists()
        screenshot("voice-composer")
        compose.onNodeWithText("确认地点，开始录音").performClick()
        compose.waitUntil(8000) { vm.state.value.recording }
        // Incoming notices cannot occupy audio while the microphone is active.
        publishVoice("announcement", "B2 已恢复", json("replaces" to old))
        assertTrue(vm.state.value.recording)
        assertNull(vm.state.value.playing)
        Thread.sleep(800)
        compose.onNodeWithTag("record").performScrollTo().performClick()
        compose.waitUntil(8000) { vm.state.value.draft != null && !vm.state.value.recording }
        compose.onNodeWithTag("transcript").performScrollTo().performTextInput("[模拟器手动转写] 这里看落日很美")
        assertEquals("scene", vm.state.value.draft!!.metadata().text("kind"))
        assertEquals("public", vm.state.value.draft!!.metadata().text("visibility"))
        screenshot("voice-draft")
        compose.onNodeWithTag("publish").performScrollTo().performClick()
        compose.waitUntil(15000) { vm.state.value.draft == null && !vm.state.value.busy }
        compose.runOnIdle { vm.browseVoices() }
        compose.waitUntil(15000) { vm.state.value.catalog.any { it.text("kind") == "scene" } }
        assertFalse(vm.state.value.catalog.any { it.text("post_id") == old })
        assertFalse(vm.state.value.deliveries.any { it.payload.text("kind") == "knowledge" })
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.onNodeWithText("我想听到的声音").performScrollTo()
        screenshot("voice-settings")
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        // Create a channel question from a genuine emulator AudioRecord buffer.
        compose.onNodeWithText("向停车场演示频道提问").performScrollTo().performClick()
        finishAndPublish("[模拟器手动转写] 入口现在排队吗？")
        compose.waitUntil(10000) { vm.state.value.question?.status == "waiting" }
        val qid = vm.state.value.question!!.id
        val invitation =
            peer.inbox().objects("items").first {
                it.text("type") == "invitation" && it.text("question_id") == qid
            }
        val answerDraft =
            LocalDraft(
                UUID.randomUUID().toString(),
                file.absolutePath,
                "[合成测试回复] 入口暂时不排队",
                replyTo = qid,
                invitationId = invitation.getString("id"),
                voiceMetadata = json("kind" to "answer", "channel_id" to "parking").toString(),
            )
        assertEquals("parking", invitation.getJSONObject("payload").text("operator_reply_channel"))
        val aid = peer.upload(answerDraft).getString("post_id")
        peer.confirm(aid)
        compose.waitUntil(15000) { vm.state.value.question?.status == "answered" }
        compose.runOnIdle { vm.openConversation(qid) }
        compose.waitUntil(10000) { vm.state.value.conversation.size == 2 }
        screenshot("voice-conversation")
        compose.onNodeWithText("回复 停车场运营方 的这句话").performScrollTo().performClick()
        finishAndPublish("[模拟器手动转写] 哪个入口？")
        val followup =
            peer.inbox().objects("items").first {
                it.getJSONObject("payload").text("kind") == "reply"
            }
        assertEquals(qid, followup.text("question_id"))
        assertEquals("bob", followup.getJSONObject("payload").text("recipient_id"))
        assertEquals(aid, followup.getJSONObject("payload").text("reply_to"))
        // Own answer remains pending during quiet; changing location must not remove its access.
        assertTrue(
            api.inbox().objects("items").any { it.getJSONObject("payload").text("post_id") == aid }
        )
        // Resume foreground audio and verify both automatic and explicit on-demand playback.
        compose.onNodeWithTag("quiet").performScrollTo().performClick()
        val tasks = TaskStore(compose.activity, config.scope)
        compose.waitUntil(15000) {
            tasks.outcome(qid) == "played" && vm.state.value.playing == null
        }
        compose
            .onNode(hasText("听原声") and hasAnyAncestor(hasTestTag("catalog-${knowledge.first()}")))
            .performScrollTo()
            .performClick()
        compose.waitUntil(15000) { tasks.outcome(knowledge.first()) == "played" }
        file.delete()
        Unit
    }
}
