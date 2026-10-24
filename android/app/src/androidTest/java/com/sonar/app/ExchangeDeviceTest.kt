package com.sonar.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Real Android foreground service, AudioRecord, MediaPlayer and UI; isolated HTTP fixtures.
 * This does not certify human ASR, acoustic echo cancellation or road use.
 */
class ExchangeDeviceTest {
    private val server = MockWebServer()
    private val offer = AtomicBoolean(false)
    private val downloads = AtomicInteger()
    private val publishes = AtomicInteger()
    private val sawDeferredExclusion = AtomicBoolean(false)
    private val blockInbox = AtomicBoolean(false)
    private val inboxStarted = java.util.concurrent.CountDownLatch(1)
    private val inboxRelease = java.util.concurrent.CountDownLatch(1)
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var sessionState = "active"
    @Volatile private var revision = 1
    @Volatile private var mode = "listen"
    private lateinit var scope: String
    private val saved = mutableMapOf<String, Map<String, *>>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun wrap(data: JSONObject) = MockResponse().setBody(data.put("server_time", Instant.now()).toString())
    private fun profile() = json("user_id" to "isolated", "revision" to 1, "mode" to mode,
        "assist_enabled" to true, "focus" to "路况", "presence" to json("source" to "android", "place_id" to "road"))
    private fun session() = json("id" to "isolated-session", "revision" to revision, "state" to sessionState,
        "place_id" to "road", "auto_publish" to false, "auto_play" to true, "expires_at" to Instant.now().plusSeconds(600))
    private fun delivery() = json("id" to "original", "type" to "audio", "question_id" to "scene",
        "request_id" to "request-original", "profile_revision" to 1, "state" to "pending", "actionable" to true,
        "expires_at" to Instant.now().plusSeconds(600), "payload" to json("post_id" to "scene", "kind" to "scene",
            "audio_url" to "/v1/audio/scene", "transcript" to "隔离测试原声"))

    @get:Rule(order = 0) val environment = object : ExternalResource() {
        override fun before() {
            for (name in listOf("connection", "linx-identity"))
                saved[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    return when {
                        path == "/v1/me" -> wrap(json("profile" to profile(), "recent_question_ids" to JSONArray()))
                        path == "/v1/me/context" -> {
                            mode = JSONObject(request.body.readUtf8()).getString("mode")
                            wrap(json("profile" to profile()))
                        }
                        path == "/v1/me/location" -> wrap(json("profile" to profile(), "places" to JSONArray()))
                        path == "/v1/inbox" -> wrap(json("items" to JSONArray()))
                        path == "/v1/exchange/sessions" -> wrap(json("session" to session()))
                        path.endsWith("/isolated-session") && request.method == "PUT" -> {
                            sessionState = JSONObject(request.body.readUtf8()).getString("state"); revision++
                            wrap(json("session" to session()))
                        }
                        path.endsWith("/isolated-session") || path.endsWith("/context") -> wrap(json("session" to session()))
                        path.endsWith("/isolated-session/inbox") -> {
                            if (request.requestUrl!!.queryParameterValues("exclude").contains("original")) sawDeferredExclusion.set(true)
                            if (blockInbox.compareAndSet(true, false)) {
                                inboxStarted.countDown()
                                inboxRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                MockResponse().setResponseCode(410).setBody("""{"error":{"code":"SESSION_INACTIVE","message":"paused"}}""")
                            } else wrap(json("items" to JSONArray(if (offer.get()) listOf(delivery()) else emptyList<JSONObject>())))
                        }
                        path == "/v1/inbox/original" -> wrap(json("delivery" to delivery()))
                        path == "/v1/audio/scene" -> {
                            downloads.incrementAndGet()
                            val pcm = ByteArray(16000 * 2 * 6) // Explicit synthetic silence, no human speech.
                            MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(Wav.header(pcm.size) + pcm))
                        }
                        path == "/v1/voices/previous-scene/withdraw" -> wrap(json("post_id" to "previous-scene", "status" to "withdrawn"))
                        path.endsWith("/ack") -> wrap(json("delivery" to delivery()))
                        path.endsWith("/utterances") -> { publishes.incrementAndGet(); MockResponse().setResponseCode(500) }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val config = DeviceConfig(baseUrl = server.url("/").toString(), token = "isolated-device-test")
            ConfigStore(context).save(config)
            scope = config.scope
        }
        override fun after() {
            inboxRelease.countDown()
            context.stopService(android.content.Intent(context, ExchangeService::class.java))
            server.shutdown()
            for ((name, values) in saved) {
                val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for ((key, value) in values) when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                }
                check(edit.commit())
            }
            context.deleteSharedPreferences("tasks-$scope")
            File(context.filesDir, "exchange-private/$scope").deleteRecursively()
        }
    }
    @get:Rule(order = 1) val permissions = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "test-exchange-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun cloudCommandCanWithdrawPreviousSceneWhileItsRecognitionJobIsActive() {
        lateinit var service: ExchangeService
        val recognition = kotlinx.coroutines.Job()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service = ExchangeService()
            android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            service.onCreate()
            ExchangeService::class.java.getDeclaredField("api").apply { isAccessible = true }
                .set(service, HttpSonarApi(server.url("/").toString(), "isolated-test", ServerClock { 0 }, true))
            ExchangeService::class.java.getDeclaredField("submitJob").apply { isAccessible = true }.set(service, recognition)
            ExchangeRuntime.mutable.value = ExchangeState(owned = true, lastKind = "scene", lastPostId = "previous-scene")
            ExchangeService::class.java.getDeclaredMethod("handleCommand", String::class.java)
                .apply { isAccessible = true }.invoke(service, "undo")
        }
        try {
            compose.waitUntil(5000) { requests.contains("POST /v1/voices/previous-scene/withdraw") }
            assertTrue(ExchangeRuntime.state.value.quiet)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onDestroy() }
            recognition.cancel()
        }
    }

    @Test fun preStartCancellationNeverBindsAnUnheardOriginal() {
        val file = File(context.cacheDir, "exchange-before-start.wav").apply { writeBytes(Wav.synthetic()) }
        val starts = AtomicInteger()
        val audio = AudioCoordinator(context)
        try {
            kotlinx.coroutines.runBlocking {
                try {
                    audio.play(file, ownSpeaker = true, mayStart = { false }, onStarted = { starts.incrementAndGet() })
                    fail("A cancelled start must not complete as played")
                } catch (_: kotlinx.coroutines.CancellationException) { }
            }
            assertEquals("Only a real MediaPlayer start may bind heard context", 0, starts.get())
        } finally { audio.close(); file.delete() }
    }

    @Test fun explicitSessionSurvivesBackgroundAndQuietDefersOriginalWithoutReplay() {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected }
        compose.runOnIdle { vm.selectPlace(PlaceChoice("road", "金科路中环路口 · 隔离测试")) }
        compose.onNodeWithTag("exchange-setup").performClick()
        compose.onNodeWithTag("exchange-auto-publish").assertIsOff()
        compose.onNodeWithTag("exchange-auto-play").assertIsOff()
        compose.onNodeWithTag("exchange-start").assertIsNotEnabled()
        screenshot("setup")
        compose.onNodeWithTag("exchange-auto-play").performClick()
        compose.onNodeWithTag("exchange-start").performClick()
        compose.waitUntil(10000) { ExchangeRuntime.state.value.phase == "ready" }
        compose.onNodeWithTag("exchange-active").assertExists()
        compose.onNodeWithTag("record").assertDoesNotExist()
        screenshot("active")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        assertTrue(ExchangeRuntime.state.value.owned)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        offer.set(true)
        try {
            compose.waitUntil(10000) { ExchangeRuntime.state.value.playing }
        } catch (e: Throwable) {
            File(context.filesDir, "test-exchange-failure.txt").writeText("${ExchangeRuntime.state.value}\ndownloads=${downloads.get()}\n${requests.joinToString("\n")}")
            screenshot("failure")
            throw e
        }
        screenshot("playing")
        blockInbox.set(true)
        assertTrue(inboxStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
        compose.onNodeWithTag("exchange-quiet").performClick()
        compose.waitUntil(5000) { ExchangeRuntime.state.value.quiet && !ExchangeRuntime.state.value.playing }
        compose.waitUntil(10000) { sessionState == "paused" }
        inboxRelease.countDown()
        Thread.sleep(300)
        assertTrue("A stale inbox 410 during quiet must not end the microphone session", ExchangeRuntime.state.value.owned)
        assertTrue(TaskStore(context, scope).automaticAudioSuspended)
        screenshot("quiet")
        assertEquals(1, downloads.get())
        compose.onNodeWithTag("exchange-quiet").performClick()
        compose.waitUntil(10000) { sessionState == "active" && !ExchangeRuntime.state.value.quiet }
        Thread.sleep(1800)
        assertEquals("Interrupted original must stay deferred", 1, downloads.get())
        assertTrue("Deferred originals must also be excluded on the server", sawDeferredExclusion.get())
        compose.onNodeWithTag("exchange-stop").performClick()
        compose.waitUntil(10000) { !ExchangeRuntime.state.value.owned }
        assertEquals("ended", sessionState)
        assertEquals("Listen-only consent must not publish", 0, publishes.get())
        assertEquals(1, downloads.get())
    }
}
