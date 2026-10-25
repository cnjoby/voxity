package com.sonar.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The old local demo is gone. No credentials means no offline substitute for server content. */
class DemoExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun noCredentialsShowsOneUnavailableStartAndPreservesSavedRealPlace() {
        val context = compose.activity
        val store = ConfigStore(context)
        val original = store.load()
        val vm = ViewModelProvider(context)[SonarViewModel::class.java]
        try {
            compose.runOnIdle { vm.configure(original.copy(token = "", testPlaceId = "demo:modaily", remotePlaceId = "")) }
            compose.onNodeWithTag("voice-start").assertExists().assertIsNotEnabled()
            compose.onNodeWithText("我的").assertDoesNotExist()
            compose.onNodeWithText("我的任务").assertDoesNotExist()
            compose.onNodeWithText("现场互助").assertDoesNotExist()
            compose.onNodeWithTag("exchange-setup").assertDoesNotExist()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("choose-place").performScrollTo().performClick()
            compose.onNodeWithText("连接服务器后读取地点，请稍后重试。").assertExists()
            assertTrue(vm.state.value.placeShortcuts.isEmpty())
            assertTrue(vm.state.value.catalog.isEmpty())
            assertEquals("B0K2KHYN77", store.load().remotePlaceId)
            assertEquals(original.linxToken, store.load().linxToken)
            assertEquals(original.linxDevice, store.load().linxDevice)
            assertNull(vm.state.value.playing)
        } finally { compose.runOnIdle { vm.configure(original) } }
    }
}
