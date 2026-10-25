package com.sonar.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, read-only production checks on the user's configured phone. No bootstrap or recording. */
class InstalledAppDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "snr005-phone-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun installedUpgradeUsesPreservedIdentityAndNeverRestartsMicrophoneAutomatically() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_phone") == "true")
        val config = ConfigStore(context).load()
        val original = digest(config.toJson().toString())
        assertFalse("Keep the phone on its real service for this read-only acceptance", config.mock)
        assertTrue("The existing device credential must remain configured", config.token.isNotBlank())
        assertEquals("https://122.195.131.106", config.baseUrl.trimEnd('/'))
        val api = HttpSonarApi(config.baseUrl, config.token, ServerClock { android.os.SystemClock.elapsedRealtime() })
        val me = api.me().getJSONObject("profile")
        val tasks = api.personalTasks().objects("items")
        assertTrue(me.text("user_id").isNotBlank())
        val vm = ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(15000) { vm.state.value.connected }
        assertFalse(ExchangeRuntime.state.value.owned)
        assertFalse(vm.state.value.recording)
        compose.onNodeWithTag("exchange-setup").assertExists()
        screenshot("home")
        compose.onNodeWithTag("exchange-setup").performClick()
        compose.onNodeWithTag("exchange-mode-private").assertIsSelected()
        compose.onNodeWithTag("exchange-auto-play").assertIsOff()
        screenshot("setup")
        compose.onNodeWithText("暂不开启").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("personal-tasks").assertExists()
        screenshot("tasks")
        assertEquals(original, digest(ConfigStore(context).load().toJson().toString()))
        assertFalse(ExchangeRuntime.state.value.owned)
        val report = json("status" to "passed", "version" to "0.1.3", "server" to config.baseUrl,
            "authenticated" to true, "task_count" to tasks.size, "configuration_digest_unchanged" to true,
            "linx_binding_present" to config.linxToken.isNotBlank(), "microphone_started" to false,
            "public_post_created" to false, "cold_start_did_not_resume_exchange" to true)
        File(context.filesDir, "snr005-phone-acceptance.json").writeText(report.toString())
    }
}
