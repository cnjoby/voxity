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
    val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
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
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("现场互助 · 已连接").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("恢复收听").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("quiet").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("现在安静").fetchSemanticsNodes().isNotEmpty()
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
            .performTextInput("[Android联调手动转写] 现在中环路金科路口堵不堵？")
        compose.onNodeWithTag("publish").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("正在替你等回复").fetchSemanticsNodes().isNotEmpty()
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
        compose.onNodeWithTag("quiet").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("恢复收听").fetchSemanticsNodes().isNotEmpty()
        }
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
