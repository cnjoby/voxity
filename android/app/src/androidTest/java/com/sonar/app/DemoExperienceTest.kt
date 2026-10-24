package com.sonar.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real MediaPlayer and UI checks, with no recording, publishing or delivery acknowledgements. */
class DemoExperienceTest {
    @get:Rule(order = 0)
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[SonarViewModel::class.java]

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "test-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun offlinePlacesFiltersPlaybackAndReturnToPhoneLocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demo_experience") == "true")
        val original = ConfigStore(compose.activity).load()
        assumeTrue(vm.state.value.draft == null && !vm.state.value.recording)
        val library = DemoLibrary(compose.activity)
        assertEquals(3, library.places.size)
        assertEquals(36, library.clips.size)
        try {
            // Isolate this check from the user's server/tasks while preserving Linx binding.
            compose.runOnIdle {
                vm.configure(original.copy(token = "", mock = false, simulatedLocation = false))
            }
            library.places.forEachIndexed { index, place ->
                compose.onNodeWithTag("test-location").performScrollTo().performClick()
                compose.onNodeWithTag("test-place-${place.id}").performScrollTo().performClick()
                compose.waitUntil(3000) { vm.state.value.config.testPlaceId == place.id }
                assertEquals(12, vm.state.value.testClips.size)
                assertTrue(vm.state.value.locationStatus.contains("模拟"))
                assertEquals(place.id, ConfigStore(compose.activity).load().testPlaceId)
                if (index == 0) screenshot("demo-home")
                // Exercise category UI and play a real WAV through the Android MediaPlayer.
                compose
                    .onNodeWithText("闲聊", useUnmergedTree = true)
                    .performScrollTo()
                    .performClick()
                val clip = vm.state.value.testClips.first { it.category == "闲聊" }
                compose.onNodeWithTag("test-clip-${clip.id}").performScrollTo().performClick()
                compose.waitUntil(3000) { vm.state.value.playing == "test:${clip.id}" }
                if (index == 0) screenshot("demo-playing")
                compose.waitUntil(10000) { vm.state.value.playing == null }
                assertFalse(vm.state.value.message.contains("播放失败"))
            }
            // Rapid replacement must release the audio gate before the next sample.
            compose.runOnIdle { vm.playTestClip(vm.state.value.testClips[0]) }
            compose.waitUntil(3000) { vm.state.value.playing != null }
            compose.runOnIdle { vm.playTestClip(vm.state.value.testClips[1]) }
            compose.waitUntil(3000) {
                vm.state.value.playing == "test:${vm.state.value.testClips[1].id}"
            }
            compose.runOnIdle { vm.stopPlayback() }
            compose.waitUntil(3000) { vm.state.value.playing == null }
            compose.onNodeWithTag("real-location").performScrollTo().performClick()
            compose.waitUntil(3000) { vm.state.value.config.testPlaceId.isEmpty() }
            assertTrue(vm.state.value.testClips.isEmpty())
            assertFalse(vm.state.value.config.simulatedLocation)
            compose.waitUntil(25000) { !vm.state.value.locating }
            val status = vm.state.value.locationStatus
            File(compose.activity.filesDir, "test-phone-location.txt").writeText(status)
            screenshot("phone-location")
            // The mode transition passes even when an indoor GPS fix is unavailable.
            assertFalse(status.contains("模拟位置"))
            assertEquals(original.linxToken, vm.state.value.config.linxToken)
        } finally {
            compose.runOnIdle {
                vm.stopPlayback()
                vm.configure(original)
            }
        }
    }
}
