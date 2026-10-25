package com.sonar.app

import android.graphics.Bitmap
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real HTTP/Compose configuration flow, isolated from the user's production account. */
class RemotePlaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val locationPermission = GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @Test fun threeServerAMapPlacesRemainRemoteAndNoAudioPlaysBeforeStart() = runBlocking<Unit> {
        val context = compose.activity
        val original = ConfigStore(context).load()
        val vm = ViewModelProvider(context)[SonarViewModel::class.java]
        val points = listOf(PlaceChoice("B0FFJSBSGR", "上海七牛信息技术有限公司"),
            PlaceChoice("B0FFHPL61V", "金科路与高科中路交叉口"), PlaceChoice("B0K2KHYN77", "模力社区"))
        val selected = AtomicReference("")
        val calls = AtomicInteger()
        val downloads = AtomicInteger()
        val revision = AtomicInteger(1)
        fun profile() = json("user_id" to "isolated", "revision" to revision.get(), "mode" to "listen",
            "assist_enabled" to true, "focus" to "停车", "presence" to json("place_id" to selected.get(),
                "source" to if (selected.get().isBlank()) "unavailable" else "remote_place", "remote" to selected.get().isNotBlank()), "listening" to json())
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer remote-place-test") return MockResponse().setResponseCode(401)
                    val data = when (request.requestUrl!!.encodedPath) {
                        "/v1/me" -> json("profile" to profile(), "recent_question_ids" to JSONArray(),
                            "place_shortcuts" to JSONArray(points.map { json("id" to it.id, "label" to it.label) }))
                        "/v1/me/remote-place" -> {
                            val body = JSONObject(request.body.readUtf8())
                            val id = body.getString("place_id")
                            if (id !in points.map { it.id }) return MockResponse().setResponseCode(422)
                            if (body.getInt("expected_revision") != revision.get()) return MockResponse().setResponseCode(409)
                            selected.set(id); revision.incrementAndGet(); calls.incrementAndGet()
                            json("profile" to profile())
                        }
                        "/v1/me/location" -> { selected.set(""); revision.incrementAndGet(); json("profile" to profile(), "places" to JSONArray()) }
                        "/v1/audio/sample" -> { downloads.incrementAndGet(); return MockResponse().setBody("unexpected") }
                        else -> json("items" to JSONArray())
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(data.put("server_time", Instant.now().toString()).toString())
                }
            }
            server.start()
            val base = server.url("/").toString()
            try {
                compose.runOnIdle { vm.configure(original.copy(baseUrl = base, token = "remote-place-test", remotePlaceId = "", testPlaceId = "")) }
                compose.waitUntil(15000) { vm.state.value.connected && vm.state.value.placeShortcuts.size == 3 }
                compose.onNodeWithTag("voice-start").assertExists()
                compose.onNodeWithText("我的任务").assertDoesNotExist()
                compose.onNodeWithText("现场互助").assertDoesNotExist()
                compose.onNodeWithTag("settings").performClick()
                for (point in points) {
                    compose.onNodeWithTag("choose-place").performScrollTo().performClick()
                    compose.onNodeWithTag("remote-place-${point.id}").performScrollTo().performClick()
                    compose.waitUntil(15000) { vm.state.value.profile?.presence?.text("place_id") == point.id }
                    assertEquals("remote_place", vm.state.value.profile!!.presence.text("source"))
                    assertEquals(point.id, ConfigStore(context).load().remotePlaceId)
                    assertEquals("remote-place-test", ConfigStore(context).load().token)
                    assertEquals(original.linxToken, ConfigStore(context).load().linxToken)
                    assertEquals(original.linxDevice, ConfigStore(context).load().linxDevice)
                    assertTrue(vm.state.value.config.testPlaceId.isBlank())
                }
                compose.onNodeWithTag("preparation-back").performScrollTo().performClick()
                compose.onNodeWithTag("location-status").assertTextContains("远程地点", substring = true)
                // The homepage itself opens the two-source selector, with all real POIs.
                compose.onNodeWithTag("location-status").performClick()
                compose.onNodeWithText("远程地点").assertExists()
                compose.onNodeWithText("真实地点").assertExists()
                compose.onNodeWithText("七牛").assertExists()
                compose.onNodeWithText("金科路与高科中路交叉口").assertExists()
                screenshot(context, "location-selector.png")
                compose.onNodeWithTag("real-location").performScrollTo().performClick()
                compose.waitUntil(15000) {
                    vm.state.value.config.remotePlaceId.isBlank() && vm.state.value.profile?.presence?.text("source") == "unavailable"
                }
                assertEquals("", ConfigStore(context).load().remotePlaceId)
                compose.onNodeWithTag("location-status").assertTextContains("真实地点 · 当前设备位置", substring = true)
                // No fabricated fix is sent when the device has not yet acquired one.
                screenshot(context, "location-device.png")
                compose.onNodeWithTag("location-status").performClick()
                compose.onNodeWithTag("remote-place-B0K2KHYN77").performScrollTo().performClick()
                compose.waitUntil(15000) { vm.state.value.profile?.presence?.text("place_id") == "B0K2KHYN77" }
                compose.onNodeWithTag("location-status").assertTextContains("远程地点 · 模力社区", substring = true)
                assertTrue(calls.get() >= 3)
                assertEquals(0, downloads.get())
                assertNull(vm.state.value.playing)
                screenshot(context, "voice-first-start.png")
            } finally { compose.runOnIdle { vm.stopPlayback(); vm.configure(original) } }
        }
    }
    private fun screenshot(context: android.content.Context, name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
