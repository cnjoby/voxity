package com.sonar.app

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
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

/** Native model + real service/AudioRecord/MediaPlayer, isolated HTTP. No real user publications. */
class VoiceRuntimeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val server = MockWebServer()
    private val requests = ConcurrentLinkedQueue<String>()
    private val downloaded = AtomicInteger()
    private val offline = AtomicBoolean()
    private val delayedStart = AtomicBoolean()
    private val startReceived = java.util.concurrent.CountDownLatch(1)
    private val startRelease = java.util.concurrent.CountDownLatch(1)
    private val showTask = AtomicBoolean()
    private val cancelledTask = AtomicBoolean()
    private val withdrawn = AtomicBoolean()
    private val invalidSyntheticContext = AtomicBoolean()
    private val timedOut = AtomicBoolean()
    private val sessionError = AtomicInteger()
    private val questionError = AtomicInteger(404)
    private val questionLookups = AtomicInteger()
    private val saved = mutableMapOf<String, Map<String, *>>()
    private var taskScope = ""
    @Volatile private var mode = "listen"
    @Volatile private var sessionState = "active"
    @Volatile private var sessionRevision = 1
    @Volatile private var lastAudioId: String? = null
    private fun profile() = json("user_id" to "runtime-test", "revision" to 1, "mode" to mode,
        "assist_enabled" to true, "focus" to "停车", "presence" to json("place_id" to "B0K2KHYN77", "place_name" to "模力社区", "source" to "remote_place", "remote" to true))
    private fun session() = json("id" to "runtime", "revision" to sessionRevision, "state" to sessionState,
        "place_id" to "B0K2KHYN77", "interaction_mode" to "voice_first", "client_managed_voice" to true, "remote" to true,
        "context_post_id" to null, "quiet" to (mode == "quiet"), "voice_policy" to json("private_default" to true,
            "continuous_share" to false, "last_audio_post_id" to lastAudioId, "heard_post_id" to null),
        "task_updates" to JSONArray(if (timedOut.get()) listOf(json("id" to "current-timeout", "revision" to 2,
            "status" to "timed_out", "place_name" to "模力社区", "question_id" to "current-question")) else emptyList<JSONObject>()))
    private fun response(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(value.put("server_time", Instant.now().toString()).toString())

    @get:Rule(order = 0) val environment = object : ExternalResource() {
        override fun before() {
            for (name in listOf("connection", "linx-identity")) saved[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    if (request.getHeader("Authorization") != "Bearer voice-runtime-test") return MockResponse().setResponseCode(401)
                    return when {
                        path == "/v1/me" -> response(json("profile" to profile(), "place_shortcuts" to JSONArray(listOf(json("id" to "B0K2KHYN77", "label" to "模力社区"))), "recent_question_ids" to JSONArray()))
                        path == "/v1/me/remote-place" -> response(json("profile" to profile()))
                        path == "/v1/questions/deleted-question" -> {
                            questionLookups.incrementAndGet()
                            response(json("error" to json("code" to "NOT_FOUND", "message" to "内容不存在",
                                "retryable" to (questionError.get() >= 500)))).setResponseCode(questionError.get())
                        }
                        path == "/v1/me/context" -> {
                            if (offline.get()) return MockResponse().setResponseCode(503)
                            mode = JSONObject(request.body.readUtf8()).text("mode")
                            response(json("profile" to profile()))
                        }
                        path == "/v1/exchange/sessions" -> {
                            startReceived.countDown()
                            if (delayedStart.get()) startRelease.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            response(json("session" to session()))
                        }
                        path == "/v1/tasks" -> response(json("items" to JSONArray(if (showTask.get()) listOf(json(
                            "id" to "waiting-task", "revision" to 1, "status" to if (cancelledTask.get()) "cancelled" else "waiting", "request_id" to "previous-question")) else emptyList<JSONObject>())))
                        path == "/v1/tasks/waiting-task/actions" -> {
                            cancelledTask.set(true)
                            response(json("id" to "waiting-task", "revision" to 2, "status" to "cancelled"))
                        }
                        path.startsWith("/v1/requests/") -> response(json("turn" to json("status" to "published", "action" to "scene", "post_id" to "last-scene")))
                        path == "/v1/voices/last-scene/withdraw" -> { withdrawn.set(true); response(json("status" to "withdrawn")) }
                        path == "/v1/exchange/sessions/runtime" -> {
                            if (sessionError.get() != 0) return MockResponse().setResponseCode(sessionError.get())
                            if (offline.get()) return MockResponse().setResponseCode(503)
                            if (request.method == "PUT") {
                                sessionState = JSONObject(request.body.readUtf8()).text("state"); sessionRevision++
                            }
                            response(json("session" to session()))
                        }
                        path == "/v1/exchange/sessions/runtime/inbox" -> response(json("items" to JSONArray()))
                        path == "/v1/exchange/sessions/runtime/context" -> {
                            lastAudioId = JSONObject(request.body.readUtf8()).getString("post_id")
                            val synced = session()
                            if (invalidSyntheticContext.get()) {
                                synced.put("context_post_id", lastAudioId)
                                synced.getJSONObject("voice_policy").put("heard_post_id", lastAudioId)
                            }
                            response(json("session" to synced))
                        }
                        path == "/v1/audio/sample" -> {
                            downloaded.incrementAndGet()
                            val pcm = ByteArray(16000 * 2)
                            MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(Wav.header(pcm.size) + pcm))
                        }
                        path == "/v1/messages/sample" -> response(json("message" to json("message_id" to "sample", "time" to json(),
                            "state" to json("publication" to "published", "applicability" to "unknown"), "body" to json("content_origin" to "agent_synthesized"))))
                        else -> response(json("items" to JSONArray()))
                    }
                }
            }
            server.start()
            val old = ConfigStore(context).load()
            val test = old.copy(baseUrl = server.url("/").toString(), token = "voice-runtime-test", linxToken = "isolated-never-used",
                linxUrl = server.url("/linx-not-used").toString().replaceFirst("http://", "ws://"), remotePlaceId = "B0K2KHYN77")
            ConfigStore(context).save(test)
            taskScope = ConfigStore(context).load().scope
        }
        override fun after() {
            startRelease.countDown()
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
            context.deleteSharedPreferences("tasks-$taskScope")
            File(context.filesDir, "exchange-private/$taskScope").deleteRecursively()
        }
    }
    @get:Rule(order = 1) val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun begin(): ExchangeService {
        val vm = ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(15000) { vm.state.value.connected }
        compose.runOnIdle { vm.startVoiceSession() }
        compose.waitUntil(20000) { ExchangeRuntime.state.value.phase == "ready" && ExchangeRuntime.activeService != null }
        assertTrue(ExchangeRuntime.state.value.localCommands)
        assertTrue(ExchangeRuntime.state.value.remote)
        return ExchangeRuntime.activeService!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(service: ExchangeService, asset: String, localFailure: Boolean = false) {
        val bytes = testContext.assets.open("control/$asset.wav").use { it.readBytes() }
        val type = Class.forName("com.sonar.app.ExchangeService\$Captured")
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val epoch = ExchangeService::class.java.getDeclaredField("epoch").apply { isAccessible = true }.getInt(service)
        val runtimeScope = ExchangeService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as kotlinx.coroutines.CoroutineScope
        val commands = ExchangeService::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(service) as LocalSpeechCommands
        val config = ExchangeService::class.java.getDeclaredField("config").apply { isAccessible = true }.get(service) as DeviceConfig
        val turn = CapturingTurn(runtimeScope, commands, config, cloud = false, contextPostId = null,
            recognizeLocal = if (localFailure) { _ -> throw java.io.IOException("isolated local ASR failure") } else commands::recognizeFrames)
        val pcm = bytes.copyOfRange(44, bytes.size)
        for (offset in pcm.indices step 640) turn.accept(pcm.copyOfRange(offset, minOf(offset + 640, pcm.size)))
        turn.finish()
        val captured = constructor.newInstance(pcm, false, false, epoch, Instant.now().toString(), null, turn)
        val pending = ExchangeService::class.java.getDeclaredField("localPending").apply { isAccessible = true }.get(service) as AtomicInteger
        val channel = ExchangeService::class.java.getDeclaredField("segments").apply { isAccessible = true }.get(service) as Channel<Any>
        pending.incrementAndGet()
        check(channel.trySend(captured).isSuccess)
    }

    private fun cacheDeletedQuestion(status: Int): SonarViewModel {
        val vm = ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        questionError.set(status)
        compose.runOnIdle {
            TaskStore(context, taskScope).questionId = "deleted-question"
            vm.configure(ConfigStore(context).load(), false)
        }
        return vm
    }

    @Test fun deletedCachedQuestionDoesNotBlockStartingVoiceSession() {
        val vm = cacheDeletedQuestion(404)
        compose.waitUntil(15000) { questionLookups.get() > 0 && vm.state.value.connected &&
            TaskStore(context, taskScope).questionId == null }
        assertNull(vm.state.value.question)
        compose.onNodeWithTag("voice-start").assertIsEnabled()
        begin()
        assertTrue(requests.contains("POST /v1/exchange/sessions"))
    }

    @Test fun goneCachedQuestionRecoversTheConnection() {
        val vm = cacheDeletedQuestion(410)
        compose.waitUntil(15000) { questionLookups.get() > 0 && vm.state.value.connected &&
            TaskStore(context, taskScope).questionId == null }
        compose.onNodeWithTag("voice-start").assertIsEnabled()
    }

    @Test fun temporaryQuestionFailureKeepsTheCachedIdForRetry() {
        val vm = cacheDeletedQuestion(503)
        compose.waitUntil(15000) { questionLookups.get() > 0 && !vm.state.value.connected }
        assertEquals("deleted-question", TaskStore(context, taskScope).questionId)
        questionError.set(404)
        compose.waitUntil(15000) { vm.state.value.connected && TaskStore(context, taskScope).questionId == null }
    }

    @Test fun bundledChineseControlsStopAndResumeServiceWithoutLinxOrSuccessfulNetwork() {
        val service = begin()
        offline.set(true)
        inject(service, "quiet")
        compose.waitUntil(8000) { ExchangeRuntime.state.value.quiet }
        assertFalse(ExchangeRuntime.state.value.playing)
        offline.set(false)
        inject(service, "resume")
        compose.waitUntil(8000) { !ExchangeRuntime.state.value.quiet && ExchangeRuntime.state.value.phase == "ready" }
        inject(service, "end")
        compose.waitUntil(8000) { !ExchangeRuntime.state.value.owned }
        assertFalse(requests.any { it.contains("utterances") || it.contains("linx-not-used") })
    }

    @Test fun localRecognizerFailureDoesNotKillLaterOfflineControls() {
        val service = begin()
        inject(service, "quiet", localFailure = true)
        compose.waitUntil(8000) { ExchangeRuntime.state.value.message.contains("本地控制识别暂不可用") }
        inject(service, "quiet")
        compose.waitUntil(8000) { ExchangeRuntime.state.value.quiet }
        assertFalse(requests.any { it.contains("utterances") })
    }

    @Test fun retryFeedbackWaitsForInFlightReplyInsteadOfTakingItsAudioGate() = runBlocking {
        val service = begin()
        val runtimeScope = ExchangeService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as kotlinx.coroutines.CoroutineScope
        val audio = ExchangeService::class.java.getDeclaredField("audio").apply { isAccessible = true }.get(service) as AudioCoordinator
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val streamed = AtomicBoolean()
        lateinit var submit: Job
        lateinit var feedback: Job
        compose.runOnIdle {
            submit = runtimeScope.launch {
                audio.stream(mayStart = { true }, onStarted = { streamed.set(true) }, onReference = {}) { sink ->
                    ready.complete(Unit)
                    release.await()
                    sink(PcmAudio(ByteArray(32000), 16000))
                }
            }
            ExchangeService::class.java.getDeclaredField("submitJob").apply { isAccessible = true }.set(service, submit)
        }
        withTimeout(5000) { ready.await() }
        try {
            compose.runOnIdle {
                ExchangeService::class.java.getDeclaredMethod("failureVoice", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, R.raw.voice_retry, false)
                feedback = ExchangeService::class.java.getDeclaredField("playerJob").apply { isAccessible = true }.get(service) as Job
                assertEquals(AudioMode.IDLE, audio.gate.mode)
                assertFalse(ExchangeRuntime.state.value.playing)
            }
            release.complete(Unit)
            withTimeout(15000) { submit.join(); feedback.join() }
            assertFalse(submit.isCancelled)
            assertTrue(streamed.get())
            assertFalse(ExchangeRuntime.state.value.playing)
        } finally { release.complete(Unit); submit.cancel(); feedback.cancel() }
    }

    @Test fun bundledFullVocabularyModelRecognizesAllOfflineControlFixtures() = runBlocking {
        val commands = LocalSpeechCommands(context)
        commands.prepare()
        for ((asset, expected) in listOf("quiet" to "quiet", "resume" to "resume", "end" to "end", "mic_off" to "end", "undo" to "undo")) {
            val file = File(context.cacheDir, "native-control-$asset.wav")
            try {
                testContext.assets.open("control/$asset.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
                assertEquals(asset, expected, localExchangeCommand(commands.recognize(file).orEmpty()))
            } finally { file.delete() }
        }
    }

    private suspend fun action(service: ExchangeService, value: JSONObject) = suspendCoroutine<Unit> { continuation ->
        val method = ExchangeService::class.java.getDeclaredMethod("handleVoiceAction", JSONObject::class.java, JSONObject::class.java,
            Int::class.javaPrimitiveType, Continuation::class.java).apply { isAccessible = true }
        val result = method.invoke(service, value, json("text" to ""), 0, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    }

    @Test fun emptyAgentTextStillExecutesServerAudioActionAndRechecksAccess() = runBlocking {
        val service = begin()
        action(service, json("type" to "play_audio", "items" to JSONArray(listOf(json("post_id" to "sample",
            "audio_url" to "/v1/audio/sample", "transcript" to "明确试听的合成音频", "synthetic" to true)))))
        val job = ExchangeService::class.java.getDeclaredField("playerJob").apply { isAccessible = true }.get(service) as Job
        job.join()
        assertEquals(1, downloaded.get())
        assertTrue(requests.contains("GET /v1/messages/sample"))
        val contextJob = ExchangeService::class.java.getDeclaredField("contextJob").apply { isAccessible = true }.get(service) as Job
        contextJob.join()
        assertTrue(requests.contains("PUT /v1/exchange/sessions/runtime/context"))
        val snapshot = ExchangeService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as JSONObject
        assertEquals("sample", snapshot.getJSONObject("voice_policy").text("last_audio_post_id"))
        assertTrue(snapshot.getJSONObject("voice_policy").isNull("heard_post_id"))
        assertTrue(snapshot.isNull("context_post_id"))
        assertEquals("ready", ExchangeRuntime.state.value.phase)
        assertFalse(ExchangeRuntime.state.value.playing)
    }

    @Test fun voiceLocationActionUpdatesOnlySelectedPlaceAndPreservesCredentials() = runBlocking {
        val service = begin()
        val before = ConfigStore(context).load()
        action(service, json("type" to "location", "place_id" to "B0FFJSBSGR", "use_real_location" to false))
        val after = ConfigStore(context).load()
        assertEquals("B0FFJSBSGR", after.remotePlaceId)
        assertEquals(before.token, after.token)
        assertEquals(before.linxToken, after.linxToken)
        assertEquals(before.linxDevice, after.linxDevice)
        assertEquals(before.clientId, after.clientId)
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun syntheticPlaybackRejectsAContextResponseThatBindsAHumanReply() = runBlocking {
        val service = begin()
        invalidSyntheticContext.set(true)
        action(service, json("type" to "play_audio", "items" to JSONArray(listOf(json("post_id" to "sample",
            "audio_url" to "/v1/audio/sample", "synthetic" to true)))))
        (ExchangeService::class.java.getDeclaredField("playerJob").apply { isAccessible = true }.get(service) as Job).join()
        (ExchangeService::class.java.getDeclaredField("contextJob").apply { isAccessible = true }.get(service) as Job).join()
        assertFalse(ExchangeService::class.java.getDeclaredField("heardContextReady").apply { isAccessible = true }.getBoolean(service))
        val snapshot = ExchangeService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as JSONObject
        assertTrue(snapshot.isNull("context_post_id"))
    }

    @Test fun timedOutTaskWaitsThroughQuietAndSpeaksOnlyOnceWithoutHumanContext() {
        val service = begin()
        val synthesized = ConcurrentLinkedQueue<String>()
        val synthesis: suspend (String, File) -> Unit = { text, file ->
            synthesized.add(text)
            val pcm = ByteArray(16000 * 2)
            file.writeBytes(Wav.header(pcm.size) + pcm)
        }
        ExchangeService::class.java.getDeclaredField("noticeSynthesis").apply { isAccessible = true }.set(service, synthesis)
        control(service, "quiet")
        compose.waitUntil(5000) { mode == "quiet" && sessionState == "paused" }
        timedOut.set(true)
        compose.waitUntil(5000) {
            val queued = ExchangeService::class.java.getDeclaredField("pendingTaskNotices").apply { isAccessible = true }.get(service) as Map<*, *>
            queued.isNotEmpty()
        }
        assertTrue(synthesized.isEmpty())
        assertFalse(ExchangeRuntime.state.value.playing)
        control(service, "resume")
        compose.waitUntil(8000) {
            val notified = ExchangeService::class.java.getDeclaredField("notifiedTasks").apply { isAccessible = true }.get(service) as Set<*>
            "current-timeout" in notified && !ExchangeRuntime.state.value.playing
        }
        val pollsBefore = requests.count { it == "GET /v1/exchange/sessions/runtime" }
        compose.waitUntil(5000) { requests.count { it == "GET /v1/exchange/sessions/runtime" } >= pollsBefore + 2 }
        assertEquals(listOf("模力社区还没有收到现场回应，这次先结束等待。"), synthesized.toList())
        assertFalse(requests.contains("PUT /v1/exchange/sessions/runtime/context"))
    }

    @Test fun terminalSessionFailurePlaysBundledSpeechBeforeClosingMicSession() {
        begin()
        sessionError.set(401)
        compose.waitUntil(5000) { ExchangeRuntime.state.value.playing && ExchangeRuntime.state.value.phase == "unavailable" }
        compose.waitUntil(15000) { !ExchangeRuntime.state.value.owned }
        assertEquals("unavailable", ExchangeRuntime.state.value.phase)
    }
    private fun control(service: ExchangeService, command: String) {
        compose.runOnIdle {
            ExchangeService::class.java.getDeclaredMethod("handleCommand", String::class.java).apply { isAccessible = true }.invoke(service, command)
        }
    }

    @Test fun quietDuringDelayedStartRemainsPausedAfterStartResponse() {
        delayedStart.set(true)
        val vm = ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(15000) { vm.state.value.connected }
        compose.runOnIdle { vm.startVoiceSession() }
        assertTrue(startReceived.await(20, java.util.concurrent.TimeUnit.SECONDS))
        val service = ExchangeRuntime.activeService!!
        control(service, "quiet")
        assertTrue(ExchangeRuntime.state.value.quiet)
        startRelease.countDown()
        compose.waitUntil(10000) { sessionState == "paused" && ExchangeRuntime.state.value.phase == "quiet" }
        assertTrue(ExchangeRuntime.state.value.quiet)
        assertFalse(ExchangeRuntime.state.value.playing)
        assertFalse(requests.any { it.contains("utterances") })
    }

    @Test fun endingDuringDelayedStartClosesTheReturnedSessionWithoutOpeningMic() {
        delayedStart.set(true)
        val vm = ViewModelProvider(compose.activity)[SonarViewModel::class.java]
        compose.waitUntil(15000) { vm.state.value.connected }
        compose.runOnIdle { vm.startVoiceSession() }
        assertTrue(startReceived.await(20, java.util.concurrent.TimeUnit.SECONDS))
        val service = ExchangeRuntime.activeService!!
        control(service, "end")
        startRelease.countDown()
        compose.waitUntil(10000) { !ExchangeRuntime.state.value.owned }
        assertEquals("ended", sessionState)
        assertNull(ExchangeService::class.java.getDeclaredField("capture").apply { isAccessible = true }.get(service))
    }

    @Test fun cancellingWhileQuietDoesNotResumeSoundOrThePublicSession() {
        val service = begin()
        showTask.set(true)
        control(service, "quiet")
        compose.waitUntil(5000) { sessionState == "paused" && mode == "quiet" }
        control(service, "cancel")
        compose.waitUntil(5000) { cancelledTask.get() }
        assertTrue(ExchangeRuntime.state.value.quiet)
        assertFalse(ExchangeRuntime.state.value.playing)
        assertEquals("paused", sessionState)
        assertEquals("quiet", mode)
    }

    @Test fun unknownCompletedShareIsReconciledThenWithdrawnWithoutCancellingAnotherNeed() {
        val service = begin()
        ExchangeService::class.java.getDeclaredField("uploadInFlight").apply { isAccessible = true }.setBoolean(service, true)
        ExchangeService::class.java.getDeclaredField("lastRequestId").apply { isAccessible = true }
            .set(service, "11111111-1111-4111-8111-111111111111")
        control(service, "undo")
        compose.waitUntil(5000) { withdrawn.get() }
        assertTrue(ExchangeRuntime.state.value.quiet)
        assertTrue(requests.any { it.startsWith("GET /v1/requests/") })
        assertFalse(cancelledTask.get())
    }

    @Test fun lateSessionSnapshotsCannotReplaceANewerRevisionOrAnotherSession() {
        val service = begin()
        val remember = ExchangeService::class.java.getDeclaredMethod("rememberSession", JSONObject::class.java).apply { isAccessible = true }
        compose.runOnIdle {
            val newer = session().put("revision", 9).put("place_id", "B0FFJSBSGR")
            remember.invoke(service, newer)
            remember.invoke(service, session().put("revision", 2))
            remember.invoke(service, session().put("id", "other-session").put("revision", 99))
        }
        val kept = ExchangeService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as JSONObject
        assertEquals(9, kept.getInt("revision"))
        assertEquals("runtime", kept.text("id"))
        assertEquals("B0FFJSBSGR", kept.text("place_id"))
    }

}
