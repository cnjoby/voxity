package com.sonar.app

import android.Manifest
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit physical-device acceptance. Does not publish, record or discard a user's draft. */
class PhoneAcceptanceTest {
    @get:Rule(order = 0)
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[SonarViewModel::class.java]

    private val report = JSONObject()

    private fun save() {
        File(compose.activity.filesDir, "test-phone-acceptance.json").writeText(report.toString(2))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "test-phone-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun realLocationAndThreeAudibleClipsPreserveAccountAndDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone_acceptance") == "true")
        assumeTrue(android.os.Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val original = ConfigStore(compose.activity).load()
        val originalStore = TaskStore(compose.activity, original.scope)
        val draft = originalStore.draft?.toJson()?.toString()
        val audio = AudioCoordinator(compose.activity)
        val started = Instant.now()
        report
            .put("started_at", started.toString())
            .put("hardware", android.os.Build.MODEL)
            .put("draft_present_before", draft != null)
            .put("linx_configured", original.linxToken.isNotBlank())
            .put("original_mock", original.mock)
            .put("original_scope", original.scope)
            .put("initial_view_scope", vm.state.value.config.scope)
        save()
        try {
            var locationFailure: Throwable? = null
            try {
                report.put("phase", "native_location")
                save()
                val fix = PhoneLocation(compose.activity).current()
                val age =
                    Duration.between(Instant.parse(fix.getString("sampled_at")), Instant.now())
                        .seconds
                report.put(
                    "native_location",
                    json(
                        "source" to fix.text("source"),
                        "accuracy_m" to fix.getDouble("accuracy_m"),
                        "age_seconds" to age,
                        "coordinate_system" to fix.text("coordinate_system"),
                        "sampled_at" to fix.text("sampled_at"),
                    ),
                )
                save()
                assertEquals(
                    "A real phone must not report a mock fix",
                    "android",
                    fix.text("source"),
                )
                assertTrue("Fix must be fresh", age in -1..55)
                compose.runOnIdle {
                    vm.configure(
                        original.copy(mock = false, simulatedLocation = false, testPlaceId = "", remotePlaceId = "")
                    )
                    vm.refreshLocation()
                }
                compose.waitUntil(30000) { !vm.state.value.locating }
                val s = vm.state.value
                report.put(
                    "app_location",
                    json(
                        "status" to s.locationStatus,
                        "connected" to s.connected,
                        "place" to s.selectedPlace?.label,
                        "server_source" to s.profile?.presence?.text("source"),
                        "nearby_count" to s.nearbyPlaces.size,
                    ),
                )
                save()
                screenshot("real-location")
                assertEquals(draft, originalStore.draft?.toJson()?.toString())
                assertEquals(original.linxToken, s.config.linxToken)
                assertTrue(
                    "Phone location must reach the app UI",
                    s.locationStatus.startsWith("定位已更新") ||
                        s.locationStatus.startsWith("位置较粗略") ||
                        s.locationStatus.startsWith("已取得手机位置"),
                )
                if (s.connected) assertEquals("android", s.profile?.presence?.text("source"))
            } catch (e: Throwable) {
                locationFailure = e
                report
                    .put("location_error", e.javaClass.simpleName)
                    .put("location_error_message", e.message)
                save()
            }
            report.put("phase", "audio_playback")
            save()
            val manager = compose.activity.getSystemService(AudioManager::class.java)
            val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            report
                .put("media_volume", volume)
                .put("media_max_volume", manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            save()
            assertTrue("Media volume is zero", volume > 0)
            // Acceptance audio comes from the authenticated server, never the APK.
            val api = HttpSonarApi(original.baseUrl, original.token, ServerClock(SystemClock::elapsedRealtime), BuildConfig.DEBUG)
            val clips = listOf("B0K2KHYN77", "B0FFJSBSGR", "B0FFHPHNSH").map { id ->
                val item = api.voices(id).objects("items").first { it.optBoolean("synthetic") }
                val file = File(compose.activity.cacheDir, "acceptance-${item.getString("post_id")}.wav")
                file.writeBytes(api.download(item.getString("audio_url")))
                val metadata = android.media.MediaMetadataRetriever()
                val duration = try { metadata.setDataSource(file.absolutePath)
                    metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
                } finally { metadata.release() }
                Triple(item, file, duration)
            }
            val outcomes = JSONArray()
            report.put("playback", outcomes)
            val playerField =
                AudioCoordinator::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }
            for (clip in clips) {
                val time = SystemClock.elapsedRealtime()
                val playing = async {
                    withTimeout(12000) { audio.play(clip.second, AudioMode.PREVIEW) }
                }
                delay(700)
                var active = false
                var deviceType: Int? = null
                compose.runOnIdle {
                    val player = playerField.get(audio) as? MediaPlayer
                    active = player?.isPlaying == true
                    deviceType = player?.routedDevice?.type
                }
                assertTrue("MediaPlayer did not start: ${clip.first.getString("post_id")}", active)
                playing.await()
                clip.second.delete()
                val elapsed = SystemClock.elapsedRealtime() - time
                assertTrue("Playback ended prematurely", elapsed >= clip.third * 1000 - 700)
                outcomes.put(
                    json(
                        "clip" to clip.first.getString("post_id"),
                        "category" to "server_synthetic",
                        "completed" to true,
                        "elapsed_ms" to elapsed,
                        "device_type" to deviceType,
                        "built_in_speaker" to (deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                    )
                )
                save()
                delay(700)
            }
            assertEquals(draft, originalStore.draft?.toJson()?.toString())
            val after = ConfigStore(compose.activity).load()
            assertEquals(original.token, after.token)
            assertEquals(original.linxToken, after.linxToken)
            assertEquals(original.linxDevice, after.linxDevice)
            if (locationFailure != null) throw locationFailure
            report
                .put("phase", "passed")
                .put("credentials_preserved", true)
                .put("draft_preserved", true)
                .put("user_hearing", "awaiting_confirmation")
            save()
            screenshot("finished")
        } catch (e: Throwable) {
            report.put("error", e.javaClass.simpleName).put("error_message", e.message)
            save()
            throw e
        } finally {
            audio.close()
            report.put("finished_at", Instant.now().toString())
            save()
        }
    }
}
