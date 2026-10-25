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
    private val playedAcks = AtomicInteger()
    @Volatile private var pendingLedger = false
    @Volatile private var missingTasks = false
    @Volatile private var offlineControl = false
    private val sawDeferredExclusion = AtomicBoolean(false)
    private val blockInbox = AtomicBoolean(false)
    private val inboxStarted = java.util.concurrent.CountDownLatch(1)
    private val inboxRelease = java.util.concurrent.CountDownLatch(1)
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var sessionState = "active"
    @Volatile private var revision = 1
    @Volatile private var mode = "listen"
    @Volatile private var startRequest: JSONObject? = null
    private val validSource = AtomicBoolean(false)
    private val expireDuringDownload = AtomicBoolean(false)
    @Volatile private var showLedger = false
    @Volatile private var ledgerStatus = "responded"
    @Volatile private var ledgerCommand: JSONObject? = null
    private lateinit var scope: String
    private val saved = mutableMapOf<String, Map<String, *>>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun wrap(data: JSONObject) = MockResponse().setBody(data.put("server_time", Instant.now()).toString())
    private fun profile() = json("user_id" to "isolated", "revision" to 1, "mode" to mode,
        "assist_enabled" to true, "focus" to "路况", "presence" to json("source" to "android", "place_id" to "road"))
    private fun session(): JSONObject = json("id" to "isolated-session", "revision" to revision,
        "state" to sessionState, "place_id" to "road", "interaction_mode" to "voice_first", "remote" to false,
        "auto_publish" to false, "auto_play" to true, "voice_policy" to json("continuous_share" to false),
        "expires_at" to Instant.now().plusSeconds(600))
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
                        path == "/v1/tasks" -> if (missingTasks) MockResponse().setResponseCode(404) else wrap(json("items" to JSONArray(if (showLedger) listOf(
                            json("id" to "ledger-test", "revision" to if (ledgerStatus == "resolved") 2 else 1,
                                "status" to ledgerStatus, "original_text" to "停车场还有位吗？",
                                "conversation_id" to "scene", "deliveries" to JSONArray(listOf(json("id" to "original", "state" to if (pendingLedger) "pending" else "played", "post_id" to "scene"))))
                        ) else emptyList<JSONObject>())))
                        path == "/v1/tasks/ledger-test/actions" -> {
                            ledgerCommand = JSONObject(request.body.readUtf8())
                            ledgerStatus = "resolved"
                            wrap(json("id" to "ledger-test", "status" to ledgerStatus))
                        }
                        path.startsWith("/v1/requests/") -> wrap(json("request_id" to path.substringAfterLast('/'),
                            "turn" to json("turn_id" to "recovered-turn", "status" to "published", "post_id" to "recovered-post", "action" to "question")))
                        path == "/v1/information/validate" -> wrap(json("valid" to validSource.get(), "valid_message_ids" to JSONArray(if (validSource.get()) listOf("scene") else emptyList<String>())))
                        path == "/v1/messages/scene" -> wrap(json("message" to json("schema_version" to "1", "message_id" to "scene", "kind" to "scene",
                            "time" to json(), "place" to json(), "subject" to json(), "event" to json(),
                            "body" to json("content_origin" to "human_original", "audio_ref" to "/v1/audio/scene", "transcript" to "隔离原声"),
                            "state" to json("publication" to "published", "applicability" to "valid"), "visibility" to "public")))
                        path == "/v1/conversations/scene" -> wrap(json("items" to JSONArray(), "can_reply" to false))
                        path == "/v1/exchange/sessions" -> {
                            startRequest = JSONObject(request.body.readUtf8())
                            wrap(json("session" to session()))
                        }
                        path.endsWith("/isolated-session") && offlineControl -> MockResponse().setResponseCode(503)
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
                            if (expireDuringDownload.get()) validSource.set(false)
                            val pcm = ByteArray(16000 * 2 * 6) // Explicit synthetic silence, no human speech.
                            MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(Wav.header(pcm.size) + pcm))
                        }
                        path == "/v1/voices/previous-scene/withdraw" -> wrap(json("post_id" to "previous-scene", "status" to "withdrawn"))
                        path.endsWith("/ack") -> {
                            if (JSONObject(request.body.readUtf8()).text("event") == "played") playedAcks.incrementAndGet()
                            wrap(json("delivery" to delivery()))
                        }
                        path.endsWith("/utterances") -> { publishes.incrementAndGet(); MockResponse().setResponseCode(500) }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val config = DeviceConfig(baseUrl = server.url("/").toString(), token = "isolated-device-test",
                linxToken = "isolated-stt-only", linxUrl = server.url("/unused-stt").toString().replaceFirst("http://", "ws://"))
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

    @Test fun parkingTaskLedgerReconcilesUnknownResultWithoutResendingAndKeepsResolutionExplicit() {
        showLedger = true
        val id = java.util.UUID.randomUUID().toString()
        val savedStore = TaskStore(context, scope)
        savedStore.beginTurn(id, "lost-session", json("client_request_id" to id, "transcript" to "停车场还有位吗？"))
        assertEquals(id, TaskStore(context, scope).pendingRequests().single().getString("request_id"))
        val commandId = savedStore.taskActionId("ledger-test", 1, "resolve")
        assertEquals(commandId, TaskStore(context, scope).taskActionId("ledger-test", 1, "resolve"))
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected }
        compose.runOnIdle { vm.refreshTasks() }
        compose.waitUntil(10000) { vm.state.value.personalTasks.isNotEmpty() && savedStore.pendingRequests().isEmpty() }
        compose.onNodeWithText("我的任务").assertDoesNotExist()
        assertEquals("responded", vm.state.value.personalTasks.single().status)
        compose.runOnIdle { vm.changeTask(vm.state.value.personalTasks.single(), "resolve") }
        compose.waitUntil(10000) { vm.state.value.personalTasks.single().status == "resolved" }
        assertEquals(commandId, ledgerCommand!!.getString("client_request_id"))
        assertEquals("resolve", ledgerCommand!!.getString("action"))
        assertTrue(requests.contains("GET /v1/requests/$id"))
        assertFalse(requests.any { it.endsWith("/utterances") || it.endsWith("/drafts") })
        screenshot("tasks-resolved")
    }

    @Test fun invalidatedInformationCannotStartSyntheticAgentPlayback() {
        lateinit var service: ExchangeService
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service = ExchangeService()
            android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            service.onCreate()
            ExchangeService::class.java.getDeclaredField("api").apply { isAccessible = true }
                .set(service, HttpSonarApi(server.url("/").toString(), "isolated-test", ServerClock { 0 }, true))
            ExchangeRuntime.mutable.value = ExchangeState(owned = true)
            ExchangeService::class.java.getDeclaredMethod("playSourcedAgent", String::class.java, Int::class.javaPrimitiveType,
                ByteArray::class.java, List::class.java, String::class.java).apply { isAccessible = true }
                .invoke(service, "已经撤回的停车公告", 0, Wav.synthetic(), listOf(json("post_id" to "withdrawn", "retrieval_purpose" to "parking")), null)
        }
        try {
            compose.waitUntil(5000) { requests.contains("POST /v1/information/validate") }
            Thread.sleep(200)
            assertFalse(ExchangeRuntime.state.value.playing)
            assertFalse(requests.any { it.endsWith("/ack") || it.endsWith("/context") })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onDestroy() }
        }
    }

    @Test fun voiceFirstStartHasNoModeSelectionOrIndiscriminatePublicGrants() {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected }
        compose.onNodeWithTag("voice-start").assertIsEnabled().performClick()
        compose.waitUntil(30000) { ExchangeRuntime.state.value.phase == "ready" }
        val request = startRequest!!
        assertEquals("voice_first", request.getString("interaction_mode"))
        assertFalse(request.has("capabilities") || request.has("auto_publish") || request.has("network_enabled"))
        compose.onNodeWithTag("exchange-active").assertExists()
        compose.onNodeWithTag("exchange-setup").assertDoesNotExist()
        assertTrue(ExchangeRuntime.state.value.owned)
        compose.onNodeWithTag("exchange-stop").performClick()
        compose.waitUntil(10000) { !ExchangeRuntime.state.value.owned }
        assertEquals(0, publishes.get())
    }

    @Test fun agentAudioHasOwnSourceYieldsToQuietAndNeverBindsHumanContext() {
        lateinit var service: ExchangeService
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service = ExchangeService()
            android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            service.onCreate()
            ExchangeService::class.java.getDeclaredField("api").apply { isAccessible = true }
                .set(service, HttpSonarApi(server.url("/").toString(), "isolated-test", ServerClock { 0 }, true))
            ExchangeRuntime.mutable.value = ExchangeState(owned = true, place = "语音网络隔离测试")
            val pcm = ByteArray(16000 * 2 * 6) // Synthetic output fixture; does not test TTS synthesis.
            ExchangeService::class.java.getDeclaredMethod("playAgent", String::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
                .apply { isAccessible = true }.invoke(service, "高德当前规划，预计25分钟。", 0, Wav.header(pcm.size) + pcm)
        }
        try {
            compose.waitUntil(8000) { ExchangeRuntime.state.value.playing }
            assertEquals("agent", ExchangeRuntime.state.value.speaker)
            assertEquals("高德当前规划，预计25分钟。", ExchangeRuntime.state.value.responseText)
            screenshot("agent-response")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ExchangeService::class.java.getDeclaredMethod("quiet").apply { isAccessible = true }.invoke(service)
            }
            compose.waitUntil(5000) { !ExchangeRuntime.state.value.playing && ExchangeRuntime.state.value.quiet }
            assertFalse(requests.any { it.endsWith("/context") })
            assertFalse(requests.any { it.endsWith("/ack") || it.endsWith("/utterances") })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onDestroy() }
        }
    }

    @Test fun chineseAgentSpeechProducesItsOwnAudioWhenLanguageIsAvailable() {
        val output = File(context.cacheDir, "agent-native-synthesis.wav")
        val report = File(context.filesDir, "test-agent-synthesis.txt")
        try {
            try {
                kotlinx.coroutines.runBlocking { AgentSpeech(context).synthesize("这是声呐的工具查询回应。", output) }
            } catch (e: Exception) {
                report.writeText("Chinese TTS unavailable: ${e.message}; speech synthesis acceptance skipped.")
                org.junit.Assume.assumeNoException("The device needs an available Chinese TTS voice", e)
            }
            assertTrue(output.length() > 44)
            val started = AtomicBoolean(false)
            val audio = AudioCoordinator(context)
            try {
                kotlinx.coroutines.runBlocking { audio.play(output, AudioMode.PROMPT, ownSpeaker = true, onStarted = { started.set(true) }) }
                assertTrue(started.get())
            } finally { audio.close() }
            report.writeText("Chinese TTS produced ${output.length()} bytes and MediaPlayer completed playback. No peer audio was synthesized.")
        } finally { output.delete() }
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
        compose.onNodeWithTag("voice-start").performClick()
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
    @Test fun preparationContainsNoTasksAssistSwitchOrFiveModes() {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected }
        compose.onNodeWithTag("settings").performClick()
        listOf("我的任务", "现场互助", "私人查询", "问问现场", "找车友聊聊", "只收听").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("developer-connection").performScrollTo().assertExists()
        assertEquals(0, publishes.get())
    }

    @Test fun taskHistoryReplaysOnlyByExplicitActionAndKeepsPublicAndDeliveryStateUntouched() {
        showLedger = true
        validSource.set(true)
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected && vm.state.value.personalTasks.isNotEmpty() }
        assertEquals(0, downloads.get())
        compose.runOnIdle { vm.hearTaskOriginal("scene") }
        compose.waitUntil(10000) { downloads.get() == 1 }
        compose.waitUntil(5000) { vm.state.value.taskNotice.startsWith("正在播放原声") }
        compose.runOnIdle { vm.stopPlayback() }
        compose.waitUntil(5000) { vm.state.value.playing == null }
        Thread.sleep(300)
        assertEquals("responded", ledgerStatus)
        assertFalse(requests.any { it.endsWith("/ack") || it.endsWith("/utterances") || it.endsWith("/queue") })
        assertTrue(requests.contains("GET /v1/messages/scene"))
        assertTrue(requests.count { it == "POST /v1/information/validate" } >= 2)
        screenshot("task-relisten")
    }

    @Test fun pendingOriginalManualListenAcksPlaybackWithoutResolvingTaskOrRepeatingIt() {
        showLedger = true
        pendingLedger = true
        validSource.set(true)
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected && vm.state.value.personalTasks.isNotEmpty() }
        compose.runOnIdle { vm.hearTaskOriginal("scene", if (pendingLedger) "original" else null) }
        compose.waitUntil(12000) { vm.state.value.taskNotice.startsWith("原声已听完") }
        compose.waitUntil(5000) { playedAcks.get() > 0 }
        assertEquals("responded", ledgerStatus)
        assertEquals(1, downloads.get())
        assertTrue(TaskStore(context, scope).attempted("original:request-original"))
        assertFalse(requests.any { it.endsWith("/utterances") || it.endsWith("/queue") || it.endsWith("/actions") })
    }

    @Test fun anotherTaskCannotRetargetAnExistingPrivateDraft() {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        val file = File(context.cacheDir, "snr005-private-draft.wav").apply { writeBytes(Wav.synthetic()) }
        try {
            val draft = LocalDraft(java.util.UUID.randomUUID().toString(), file.absolutePath, targetPlaceId = "original-place", targetLabel = "原草稿地点")
            TaskStore(context, scope).draft = draft
            compose.runOnIdle { vm.configure(ConfigStore(context).load(), false) }
            compose.waitUntil(5000) { vm.state.value.draft != null }
            val task = PersonalTask.parse(json("id" to "other", "revision" to 1, "status" to "responded", "original_text" to "另一任务", "place_id" to "other-place"))
            compose.runOnIdle { assertFalse(vm.prepareTaskQuery(task)) }
            assertEquals("original-place", vm.state.value.draft!!.targetPlaceId)
            assertEquals(draft.requestId, TaskStore(context, scope).draft!!.requestId)
            assertEquals(0, publishes.get())
        } finally { file.delete() }
    }

    @Test fun taskServiceFailureDoesNotCreateTaskOrManualFallbackMenus() {
        missingTasks = true
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected && !vm.state.value.taskServiceAvailable }
        compose.onNodeWithTag("voice-start").assertExists()
        compose.onNodeWithText("我的任务").assertDoesNotExist()
        assertTrue(vm.state.value.connected)
    }

    @Test fun quietAndMicrophoneStopTakeEffectBeforeFailedNetworkControl() {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected }
        compose.runOnIdle { vm.selectPlace(PlaceChoice("road", "断网控制隔离测试")); vm.startVoiceSession() }
        compose.waitUntil(10000) { ExchangeRuntime.state.value.phase == "ready" }
        offlineControl = true
        compose.onNodeWithTag("exchange-quiet").performClick()
        compose.waitUntil(1000) { ExchangeRuntime.state.value.quiet && !ExchangeRuntime.state.value.playing }
        compose.onNodeWithTag("exchange-stop").performClick()
        compose.waitUntil(5000) { !ExchangeRuntime.state.value.owned }
        assertEquals(0, publishes.get())
    }

    @Test fun historyAudioFocusLossKeepsNormalClientQuietAfterOtherAudioEnds() {
        showLedger = true
        validSource.set(true)
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val competitor = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { }.build()
        compose.waitUntil(10000) { vm.state.value.connected && vm.state.value.personalTasks.isNotEmpty() }
        compose.runOnIdle { vm.hearTaskOriginal("scene", if (pendingLedger) "original" else null) }
        compose.waitUntil(5000) { vm.state.value.taskNotice.startsWith("正在播放原声") }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.requestAudioFocus(competitor) }
            compose.waitUntil(5000) { vm.state.value.quiet && vm.state.value.playing == null }
            manager.abandonAudioFocusRequest(competitor)
            Thread.sleep(1000)
            assertTrue(vm.state.value.quiet)
            assertEquals("responded", ledgerStatus)
            assertEquals(0, playedAcks.get())
        } finally { manager.abandonAudioFocusRequest(competitor) }
    }

    @Test fun taskContinuationUsesRealRootAndNeverCreatesAnotherPublicQuestion() {
        showLedger = true
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected && vm.state.value.personalTasks.isNotEmpty() }
        compose.runOnIdle { vm.openConversation("scene") }
        compose.waitUntil(5000) { vm.state.value.conversationId == "scene" }
        compose.onNodeWithText("我的任务").assertDoesNotExist()
        assertTrue(requests.contains("GET /v1/conversations/scene"))
        assertFalse(requests.any { it.endsWith("/utterances") || it.endsWith("/drafts") })
    }

    @Test fun taskHistoryRejectsSourceInvalidatedDuringDownload() {
        showLedger = true
        validSource.set(true)
        expireDuringDownload.set(true)
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(10000) { vm.state.value.connected && vm.state.value.personalTasks.isNotEmpty() }
        compose.runOnIdle { vm.hearTaskOriginal("scene", if (pendingLedger) "original" else null) }
        compose.waitUntil(10000) { vm.state.value.taskNotice.contains("失效") && vm.state.value.playing == null }
        assertEquals(1, downloads.get())
        assertFalse(requests.any { it.endsWith("/ack") || it.endsWith("/utterances") })
    }

    @Test fun activeViewShowsRealRemoteProvenanceWithoutTaskOrModeUI() {
        compose.runOnIdle { ExchangeRuntime.mutable.value = ExchangeState(owned = true, phase = "agent_response", speaker = "agent",
            playing = true, remote = true, place = "模力社区") }
        try {
            compose.onNodeWithText("声呐回应 · 合成语音").assertExists()
            compose.onNodeWithText("远程地点 · 非手机实到").assertExists()
            compose.onNodeWithText("我的任务").assertDoesNotExist()
            compose.onNodeWithTag("exchange-setup").assertDoesNotExist()
            screenshot("source-mirror")
        } finally { compose.runOnIdle { ExchangeRuntime.mutable.value = ExchangeState() } }
    }

    @Test fun audioFocusLossStopsAgentAudioAndDoesNotResumeAutomatically() {
        lateinit var service: ExchangeService
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val competitor = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { }.build()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service = ExchangeService()
            android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            service.onCreate()
            ExchangeService::class.java.getDeclaredField("api").apply { isAccessible = true }
                .set(service, HttpSonarApi(server.url("/").toString(), "isolated-test", ServerClock { 0 }, true))
            ExchangeRuntime.mutable.value = ExchangeState(owned = true)
            val pcm = ByteArray(16000 * 2 * 6)
            ExchangeService::class.java.getDeclaredMethod("playAgent", String::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
                .apply { isAccessible = true }.invoke(service, "隔离音频焦点测试", 0, Wav.header(pcm.size) + pcm)
        }
        try {
            compose.waitUntil(8000) { ExchangeRuntime.state.value.playing }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competitor))
            }
            compose.waitUntil(5000) { ExchangeRuntime.state.value.quiet && !ExchangeRuntime.state.value.playing }
            manager.abandonAudioFocusRequest(competitor)
            Thread.sleep(1000)
            assertTrue("Regaining focus must not restore capture or playback authorization", ExchangeRuntime.state.value.quiet)
            assertFalse(ExchangeRuntime.state.value.playing)
            screenshot("focus-loss-quiet")
        } finally {
            manager.abandonAudioFocusRequest(competitor)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onDestroy() }
        }
    }

}
