package com.sonar.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveFlowTest {
    @get:Rule(order = 0)
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun recordedQuestionWaitsForLiveHttpReplyAndCompletesPlayback() {
        assumeTrue(
            "Opt in with instrumentation argument live=true",
            InstrumentationRegistry.getArguments().getString("live") == "true",
        )
        val config = ConfigStore(compose.activity).load()
        assertFalse(config.mock)
        assertTrue(config.debugTranscript)
        val nearby = InstrumentationRegistry.getArguments().getString("nearby") == "true"
        if (nearby) {
            assertFalse(config.simulatedLocation)
            val vm =
                androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
            compose.waitUntil(45000) {
                vm.state.value.selectedPlace != null && !vm.state.value.locating
            }
            assertNotEquals("zhonghuan_jinke", vm.state.value.selectedPlace!!.id)
            assertNotEquals("simulated", vm.state.value.profile!!.presence.text("source"))
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("已连接，可以录音提问").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("恢复收听").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("quiet").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("安静一下").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithTag("record").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("结束录音").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(1200) // Record a real emulator AudioRecord buffer; no claim of human speech.
        compose.onNodeWithTag("record").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("transcript")
            .performScrollTo()
            .performTextInput(
                if (nearby) "[Android定位联调手动转写] 这里现在堵不堵？" else "[Android联调手动转写] 现在中环路金科路口堵不堵？"
            )
        if (nearby) {
            val vm =
                androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
            val draft = vm.state.value.draft!!
            assertNotNull(draft.recordedLocation)
            assertEquals(
                "wgs84",
                org.json.JSONObject(draft.recordedLocation!!).text("coordinate_system"),
            )
            assertEquals("unknown", draft.metadata().text("direction"))
        }
        compose.onNodeWithTag("publish").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("正在替你等回复").fetchSemanticsNodes().isNotEmpty()
        }
        if (InstrumentationRegistry.getArguments().getString("two_clients") == "true") {
            val vm =
                androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
            val bobConfig =
                DeviceConfig.parse(
                    org.json.JSONObject(
                        File(compose.activity.filesDir, "nearby-peer.json").readText()
                    )
                )
            compose.runOnIdle { vm.configure(bobConfig) }
            compose.waitUntil(45000) { vm.state.value.deliveries.any { it.type == "invitation" } }
            if (InstrumentationRegistry.getArguments().getString("decisions") == "true") {
                val invitation = vm.state.value.deliveries.first { it.type == "invitation" }
                val attention = invitation.payload.getJSONObject("attention")
                assertEquals("bamboo", attention.text("provider"))
                assertEquals("notify", attention.text("action"))
            }
            // Wait until the system prompt and ACK finish; the reply entry must remain visible.
            compose.waitUntil(15000) {
                vm.state.value.deliveries.any {
                    it.type == "invitation" && it.state == "prompted"
                } && vm.state.value.playing == null
            }
            compose.onNodeWithText("说一句回应").performScrollTo().assertIsEnabled()
            val inviteImage = compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.filesDir, "test-nearby-invite.png").outputStream().use {
                inviteImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("说一句回应").performClick()
            compose.waitUntil(5000) { vm.state.value.recording }
            Thread.sleep(1200)
            compose.onNodeWithTag("record").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.state.value.draft != null && !vm.state.value.recording }
            compose
                .onNodeWithTag("transcript")
                .performScrollTo()
                .performTextInput("[Android双用户手动转写] 这里现在有点堵。")
            compose.onNodeWithTag("publish").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.state.value.draft == null && !vm.state.value.busy }
            compose.runOnIdle { vm.configure(config) }
        }
        compose.waitUntil(30000) {
            compose
                .onAllNodes(hasTestTag("audio-outcome") and hasText("原声已播放"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("audio-outcome").performScrollTo().assertTextEquals("原声已播放")
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "test-live-answer.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        val savedFocus = vm.state.value.profile!!.focus
        compose.onNodeWithTag("quiet").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("恢复收听").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10000) { vm.state.value.profile?.mode == "quiet" }
        assertEquals(savedFocus, vm.state.value.profile!!.focus)
    }

    @Test
    fun taskRecoveryDoesNotReplayUncertainAttemptsOrMixUsers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = UUID.randomUUID().toString()
        val first = TaskStore(context, scope)
        val draft =
            LocalDraft(
                "stable-clip-id",
                "private.wav",
                "核对文字",
                postId = "private-draft",
                frozen = true,
            )
        first.draft = draft
        first.questionId = "q1"
        first.markAttempt("delivery1:request1")
        first.pendingMode = "quiet"
        val restored = TaskStore(context, scope)
        assertEquals(draft, restored.draft)
        assertEquals("q1", restored.questionId)
        assertTrue(restored.attempted("delivery1:request1"))
        assertTrue(restored.attempted("delivery1:rotated-request"))
        assertEquals("quiet", restored.pendingMode)
        assertFalse(TaskStore(context, scope + "-bob").attempted("delivery1:request1"))
    }
}
