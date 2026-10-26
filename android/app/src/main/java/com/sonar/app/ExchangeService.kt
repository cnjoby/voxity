package com.sonar.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ExchangeState(
    val owned: Boolean = false,
    val phase: String = "off",
    val message: String = "",
    val place: String = "",
    val remote: Boolean = false,
    val quiet: Boolean = false,
    val publishing: Boolean = false,
    val playing: Boolean = false,
    val lastPostId: String? = null,
    val lastKind: String? = null,
    val privateCount: Int = 0,
    val localCommands: Boolean = false,
    val echoProtected: Boolean = false,
    val speaker: String = "human",
    val responseText: String = "",
    val privateOnly: Boolean = false,
    val sources: List<JSONObject> = emptyList(),
    val visibility: String = "",
    val grants: SessionCapabilities? = null,
)

object ExchangeRuntime {
    internal val mutable = MutableStateFlow(ExchangeState())
    val state = mutable.asStateFlow()
    internal var activeService: ExchangeService? = null
}

/** Starts only from a visible user action. No boot receiver, sticky restart or implicit re-consent. */
class ExchangeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = ServerClock(SystemClock::elapsedRealtime)
    private lateinit var config: DeviceConfig
    private lateinit var api: SonarApi
    private lateinit var audio: AudioCoordinator
    private lateinit var journal: TaskStore
    private lateinit var privateDir: File
    private var capture: ExchangeCapture? = null
    private var session: JSONObject? = null
    private var playerJob: Job? = null
    private var submitJob: Job? = null
    private var contextJob: Job? = null
    private var heardContextReady = true
    private var uploadInFlight = false
    private var runJob: Job? = null
    private var currentDelivery: Delivery? = null
    private var interrupted: Delivery? = null
    @Volatile private var playing = false
    @Volatile private var sourceBlocked = false
    @Volatile private var epoch = 0
    @Volatile private var segmentEpoch = 0
    @Volatile private var segmentTime = ""
    @Volatile private var segmentEcho: String? = null
    @Volatile private var lastPlaybackText: String? = null
    @Volatile private var lastAudioEnd = 0L
    @Volatile private var localQuiet = false
    @Volatile private var closing = false
    private val localPending = java.util.concurrent.atomic.AtomicInteger()
    private var controlJob: Job? = null
    private val locationMutex = Mutex()
    private val echoGuard = PlaybackEchoGuard(SystemClock::elapsedRealtime)
    private var lastRequestId: String? = null
    private val startResolved = CompletableDeferred<JSONObject?>()
    private val sessionLock = Any()
    @Volatile private var requestedQuiet: Boolean? = null
    @Volatile private var controlTransition = false
    private var recognitionFailures = 0
    private var receiptCursor = 0
    private var receiptJob: Job? = null
    private var lastReceiptCheck = 0L
    private var commands: LocalSpeechCommands? = null
    private val pendingTaskNotices = linkedMapOf<String, String>()
    private val notifiedTasks = mutableSetOf<String>()
    private var noticeSynthesis: suspend (String, File) -> Unit = { text, file -> AgentSpeech(this).synthesize(text, file); Unit }
    @Volatile private var captureTurn: CapturingTurn? = null
    private val segments = Channel<Captured>(1)
    private data class Captured(val pcm: ByteArray, val cut: Boolean, val blocked: Boolean, val epoch: Int, val time: String, val echo: String?, val turn: CapturingTurn)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ExchangeRuntime.activeService = this
        audio = AudioCoordinator(this, onFocusLost = ::interruptedBySystem)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("exchange", "声呐语音", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                if (runJob != null) return START_NOT_STICKY
                ExchangeRuntime.mutable.value = ExchangeState(owned = true, phase = "starting", message = "正在准备语音", place = intent.getStringExtra("place_label").orEmpty())
                val notification = NotificationCompat.Builder(this, "exchange")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("声呐语音会话")
                    .setContentText("麦克风用于本次交流；可随时安静或结束")
                    .setSilent(true).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(0, "安静", actionIntent(QUIET, 1))
                    .addAction(0, "结束", actionIntent(STOP, 2)).build()
                try {
                    val locationType = if (PhoneLocation(this).permitted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                    if (Build.VERSION.SDK_INT >= 30)
                        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or locationType)
                    else if (Build.VERSION.SDK_INT >= 29)
                        startForeground(41, notification, locationType)
                    else startForeground(41, notification)
                    runJob = scope.launch { runExchange(intent) }
                } catch (_: Exception) {
                    ExchangeRuntime.mutable.update { it.copy(owned = false, phase = "unavailable", message = "系统未允许后台麦克风，请停车后检查权限") }
                    stopSelf()
                }
            }
            QUIET -> quiet()
            RESUME -> resume()
            STOP -> end()
        }
        return START_NOT_STICKY
    }

    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ExchangeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun interruptedBySystem() {
        if (!closing && !localQuiet && ExchangeRuntime.state.value.owned) {
            quiet()
            update("quiet", "其他音频或通话中断了交流，已保持安静；停车后可主动恢复")
        }
    }

    private fun audioAvailable(): Boolean {
        val available = getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL
        if (!available && !closing && !localQuiet) scope.launch { interruptedBySystem() }
        return available
    }

    private fun update(phase: String, message: String) {
        android.util.Log.i("SonarVoice", "phase=$phase playing=$playing quiet=$localQuiet")
        ExchangeRuntime.mutable.update { it.copy(phase = phase, message = message, quiet = localQuiet,
            playing = playing, echoProtected = capture?.echoProtected == true,
            privateCount = if (::privateDir.isInitialized) privateDir.listFiles()?.count { f -> f.extension == "wav" } ?: 0 else 0) }
    }

    private suspend fun runExchange(intent: Intent) {
        try {
            check(Build.VERSION.SDK_INT >= 28) { "语音会话需要 Android 9 或以上" }
            config = ConfigStore(this).load()
            check(config.token.isNotBlank()) { "请先配置声呐连接" }
            check(config.linxToken.isNotBlank()) { "请先绑定灵矽语音" }
            api = HttpSonarApi(config.baseUrl, config.token, clock, BuildConfig.DEBUG)
            journal = TaskStore(this, config.scope)
            privateDir = File(filesDir, "exchange-private/${config.scope}").apply { mkdirs() }
            commands = LocalSpeechCommands(this).also { it.prepare() }
            ExchangeRuntime.mutable.update { it.copy(localCommands = true) }
            val me = api.me()
            var profile = Profile.parse(me.getJSONObject("profile"))
            if (config.remotePlaceId.isNotBlank()) {
                profile = Profile.parse(api.remotePlace(profile.revision, config.remotePlaceId).getJSONObject("profile"))
            }
            val request = json("client_request_id" to UUID.randomUUID().toString(),
                "interaction_mode" to "voice_first", "client_managed_voice" to true,
                "expected_revision" to profile.revision,
                "place_id" to intent.getStringExtra("place_id").orEmpty(),
                "timezone" to ZoneId.systemDefault().id)
            if (closing) return
            rememberSession(api.startExchange(request).getJSONObject("session"))
            check(session!!.optBoolean("client_managed_voice")) { "请连接最新版声呐服务" }
            startResolved.complete(session)
            if (closing) return
            check(session!!.text("interaction_mode") == "voice_first") { "请连接新版声呐服务" }
            localQuiet = requestedQuiet ?: (session!!.text("state") == "paused" || session!!.optBoolean("quiet"))
            while (!closing) {
                val desired = requestedQuiet ?: break
                val latestProfile = Profile.parse(api.me().getJSONObject("profile"))
                api.context(json("expected_revision" to latestProfile.revision, "assist_enabled" to latestProfile.assist,
                    "focus" to latestProfile.focus, "mode" to if (desired) "quiet" else "listen"))
                val latest = rememberSession(api.exchangeSession(session!!.getString("id")).getJSONObject("session"))
                if (closing) return
                rememberSession(api.changeExchange(latest.getString("id"), latest.getInt("revision"), if (desired) "paused" else "active").getJSONObject("session"))
                if (requestedQuiet == desired) { localQuiet = desired; break }
            }
            if (closing) return
            applyProfile(profile.presence)
            ExchangeRuntime.mutable.update { it.copy(remote = session!!.optBoolean("remote"), privateOnly = false) }
            scope.launch { reconcilePending() }
            capture = ExchangeCapture()
            launchChildren(commands!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update("unavailable", e.message ?: "语音暂不可用")
            if (!closing) failureVoice(R.raw.voice_failure, stopAfter = true)
        } finally {
            if (!startResolved.isCompleted) startResolved.complete(null)
        }
    }

    private fun rememberSession(value: JSONObject): JSONObject = synchronized(sessionLock) {
        val old = session
        if (old == null || old.text("id") == value.text("id") && value.optInt("revision") >= old.optInt("revision")) session = value
        session ?: value
    }

    private fun applyProfile(presence: JSONObject) {
        ExchangeRuntime.mutable.update { it.copy(
            place = presence.text("place_name").ifBlank { it.place },
            remote = presence.text("source") == "remote_place") }
    }

    private suspend fun reconcilePending() {
        val receipts = journal.pendingRequests()
        if (receipts.isEmpty()) { receiptCursor = 0; return }
        val batch = (0 until minOf(5, receipts.size)).map { receipts[(receiptCursor + it) % receipts.size] }
        receiptCursor = (receiptCursor + batch.size) % receipts.size
        for (pending in batch) {
            try {
                val requestId = pending.getString("request_id")
                val known = api.requestResult(requestId)
                known.optJSONObject("turn")?.let { journal.recordTurnResult(requestId, it) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the unknown receipt. Never upload it again. */ }
        }
    }

    private suspend fun launchChildren(commands: LocalSpeechCommands) = kotlinx.coroutines.coroutineScope {
        launch {
            while (isActive) {
                try {
                    locationMutex.withLock {
                        val me = Profile.parse(api.me().getJSONObject("profile"))
                        val remote = config.remotePlaceId
                        val response = if (remote.isNotBlank()) api.remotePlace(me.revision, remote)
                        else if (PhoneLocation(this@ExchangeService).permitted())
                            api.location(me.revision, PhoneLocation(this@ExchangeService).current()) else null
                        response?.getJSONObject("profile")?.getJSONObject("presence")?.let(::applyProfile)
                        val current = session
                        if (current != null) rememberSession(api.exchangeSession(current.getString("id")).getJSONObject("session"))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Stale location cannot become an on-site claim. */ }
                delay(15000)
            }
        }
        launch {
            for (clip in segments) {
                if (clip.epoch != epoch || closing) { clip.turn.cancel(); localPending.decrementAndGet(); continue }
                val file = File(cacheDir, "exchange-${clip.turn.id}.wav")
                android.util.Log.i("SonarVoice", "segment bytes=${clip.pcm.size} cut=${clip.cut} blocked=${clip.blocked}")
                if (!localQuiet && !closing) update("recognizing", "已收音，正在识别这句话")
                file.writeBytes(Wav.header(clip.pcm.size) + clip.pcm)
                // Local controls must not wait for either cloud recognition or a pending upload.
                val local = try {
                    clip.turn.local.await()
                } catch (e: CancellationException) {
                    clip.turn.cancel(); file.delete()
                    if (!isActive) throw e
                    continue
                } catch (_: Exception) {
                    clip.turn.cancel()
                    retain(file); file.delete()
                    update("private", "本地控制识别暂不可用，这句话保持私有")
                    failureVoice(R.raw.voice_retry)
                    continue
                } finally { localPending.decrementAndGet() }
                // Readiness came from loading the bundled model, not recognizing arbitrary speech.
                val localCommand = local?.let(::localExchangeCommand)
                if (localCommand != null) {
                    clip.turn.cancel(); file.delete()
                    if (!looksLikePlaybackEcho(local, clip.echo, minLength = 2)) handleCommand(localCommand)
                    continue
                }
                if (clip.epoch != epoch || localQuiet || closing || !audioAvailable()) { clip.turn.cancel(); file.delete(); continue }
                if (clip.cut || clip.blocked || submitJob?.isActive == true) {
                    clip.turn.cancel()
                    retain(file); file.delete()
                    if (clip.cut) failureVoice(R.raw.voice_too_long)
                    else if (clip.blocked) failureVoice(R.raw.voice_retry)
                    else failureVoice(R.raw.voice_retry)
                    continue
                }
                submitJob = launch {
                    var uploadStarted = false
                    var submissionCompleted = false
                    val turnStarted = SystemClock.elapsedRealtime()
                    try {
                        ExchangeRuntime.mutable.update { it.copy(publishing = true) }
                        update("recognizing", "正在处理本次发言")
                        val requestId = file.nameWithoutExtension.removePrefix("exchange-")
                        var routedResult: JSONObject? = null
                        var referenceStarted = false
                        var playbackPosition: () -> Int = { 0 }
                        val reply = audio.stream(
                            mayStart = { canPlay(clip.epoch) },
                            onStarted = {
                                playing = true
                                android.util.Log.i("SonarVoice", "first_playback turn=$requestId at_ms=${SystemClock.elapsedRealtime()} elapsed_ms=${SystemClock.elapsedRealtime() - turnStarted}")
                                update("agent_response", "声呐正在回应")
                            },
                            onReference = { frame ->
                                if (!referenceStarted) { echoGuard.beginStream(playbackPosition); referenceStarted = true }
                                echoGuard.appendStream(frame)
                            },
                            onPlaybackClock = { playbackPosition = it },
                        ) { sink ->
                            val hooks = LinxTurnHooks(
                                pcm = sink,
                                spoken = { text ->
                                    if (routedResult?.text("action") == "private_chat") {
                                        lastPlaybackText = text
                                        ExchangeRuntime.mutable.update { it.copy(responseText = text, speaker = "agent", sources = emptyList(), visibility = "private") }
                                    }
                                },
                                route = route@{ transcript ->
                                    android.util.Log.i("SonarVoice", "stt_complete chars=${transcript.length} elapsed_ms=${SystemClock.elapsedRealtime() - turnStarted}")
                                    val command = localExchangeCommand(transcript)
                                    if (looksLikePlaybackEcho(transcript, clip.echo, minLength = if (command == null) 4 else 2)) {
                                        retain(file); update("private", "这段可能含扬声器回声，保持私有"); return@route false
                                    }
                                    if (command != null) { handleCommand(command); return@route false }
                                    ExchangeRuntime.mutable.update { it.copy(lastPostId = null, lastKind = null, responseText = "", sources = emptyList(), visibility = "") }
                                    contextJob?.join()
                                    if (!heardContextReady) { retain(file); update("private", "原声上下文尚未同步，这段保持私有"); failureVoice(R.raw.voice_retry); return@route false }
                                    val live = locationMutex.withLock {
                                        if (clip.epoch != epoch || localQuiet || closing || !audioAvailable()) throw CancellationException("会话已改变")
                                        rememberSession(api.exchangeSession(session!!.getString("id")).getJSONObject("session"))
                                    }
                                    val result = run {
                                        val metadata = json("client_request_id" to requestId,
                                            "expected_session_revision" to live.getInt("revision"), "transcript" to transcript, "recorded_at" to clip.time,
                                            "captured_context_post_id" to clip.turn.contextPostId)
                                        OpusFiles.forUpload(file) // Fail encoding before recording an uncertain network submission.
                                        journal.beginTurn(requestId, live.getString("id"), metadata)
                                        lastRequestId = requestId
                                        update("sending", "识别完成，正在发送到声呐服务器")
                                        uploadStarted = true; uploadInFlight = true
                                        val uploadAt = SystemClock.elapsedRealtime()
                                        api.exchangeUpload(live.getString("id"), file, metadata) {
                                            android.util.Log.i("SonarVoice", "upload_body_complete elapsed_ms=${SystemClock.elapsedRealtime() - uploadAt}")
                                            scope.launch {
                                                if (!submissionCompleted && clip.epoch == epoch && !localQuiet && !closing)
                                                    update("processing", "录音已发出，等待声呐处理")
                                            }
                                        }.also { response ->
                                            submissionCompleted = true
                                            uploadInFlight = false
                                            android.util.Log.i("SonarVoice", "server_complete elapsed_ms=${SystemClock.elapsedRealtime() - uploadAt}")
                                            response.optJSONObject("session")?.let(::rememberSession)
                                            val action = response.optJSONObject("voice_action")
                                            if (action?.text("type") == "location") {
                                                val store = ConfigStore(this@ExchangeService)
                                                store.save(store.load().copy(remotePlaceId = if (action.optBoolean("use_real_location")) "" else action.getString("place_id")))
                                                config = store.load()
                                                response.optJSONObject("profile")?.optJSONObject("presence")?.let(::applyProfile)
                                            }
                                        }
                                    }
                                    journal.recordTurnResult(requestId, result)
                                    routedResult = result
                                    if (result.text("action") == "private_chat") {
                                        update("assistant_wait", "正在接收私人助手回复")
                                        true
                                    } else false
                                })
                            clip.turn.bind(hooks)
                            checkNotNull(clip.turn.reply) { "本轮未启动云识别，录音保持私有" }.await()
                        }
                        val result = routedResult ?: return@launch
                        android.util.Log.i("SonarVoice", "server_result status=${result.text("status")} action=${result.text("action")}")
                        if (clip.epoch != epoch || localQuiet || closing) return@launch
                        recognitionFailures = 0
                        result.optJSONObject("session")?.let(::rememberSession)
                        result.optJSONObject("profile")?.optJSONObject("presence")?.let(::applyProfile)
                        if (result.optJSONObject("voice_action") != null) {
                            handleVoiceAction(result.getJSONObject("voice_action"), result.optJSONObject("agent_response"), clip.epoch)
                        } else if (result.text("action") == "private_chat") {
                            ExchangeRuntime.mutable.update { it.copy(responseText = reply.assistantText, speaker = "agent", visibility = "private") }
                            update("ready", "正在听")
                        } else if (result.optJSONObject("agent_response") != null) {
                            val response = result.getJSONObject("agent_response")
                            val text = response.text("text")
                            ExchangeRuntime.mutable.update { it.copy(responseText = text, speaker = "agent", sources = response.objects("sources"), visibility = "private") }
                            update(if (result.text("status") == "clarifying") "clarifying" else "agent_response", text)
                            playSourcedAgent(text, clip.epoch, sources = response.objects("sources"), taskId = result.text("task_id").ifBlank { null })
                        } else if (result.text("status") == "chatting") {
                            ExchangeRuntime.mutable.update { it.copy(visibility = "participants") }
                            update("chatting", "车友交流中，双方原声仅参与者可听")
                        } else if (result.text("status") == "published") {
                            ExchangeRuntime.mutable.update { it.copy(lastPostId = result.text("post_id"), lastKind = result.text("action"), visibility = "public") }
                            val receipt = submissionReceipt(result)
                            update(if (result.text("action") == "question") "waiting" else "shared", receipt)
                            playAgent(receipt, clip.epoch)
                        } else if (result.text("status") == "ignored") update("ready", "现场交流中")
                        else {
                            retain(file)
                            if (result.text("status") == "deferred") failureVoice(R.raw.voice_retry)
                            else update("ready", "正在听")
                        }
                    } catch (e: CancellationException) {
                        if (uploadStarted && !submissionCompleted) {
                            retain(file)
                            update("unknown", "交流已暂停；刚才的发送结果待核对，不会自动补发")
                        }
                        throw e
                    }
                    catch (e: Exception) {
                        val apiError = (e as? ApiFailure)?.let {
                            val code = it.code.take(80).replace(Regex("[^A-Z0-9_]"), "_")
                            " http=${it.status} code=$code"
                        }.orEmpty()
                        android.util.Log.w("SonarVoice", "turn_failed uploaded=$uploadStarted type=${e.javaClass.simpleName}$apiError")
                        retain(file)
                        if (submissionCompleted) {
                            update("assistant_unavailable", "发言已处理，但后续语音回应未完成")
                            failureVoice(R.raw.voice_failure, stopAfter = e is LinxAuthFailure)
                            return@launch
                        }
                        update(if (uploadStarted) "unknown" else "private",
                            if (uploadStarted) "发送结果待核对，不会自动补发" else "这句话没有发送")
                        recognitionFailures++
                        failureVoice(if (uploadStarted) R.raw.voice_unknown else R.raw.voice_network,
                            stopAfter = e is LinxAuthFailure || recognitionFailures >= 2)
                        if (uploadStarted) scope.launch { reconcilePending() }
                    } finally {
                        android.util.Log.i("SonarVoice", "turn_complete elapsed_ms=${SystemClock.elapsedRealtime() - turnStarted} submitted=$submissionCompleted")
                        uploadInFlight = false
                        if (playing) lastAudioEnd = SystemClock.elapsedRealtime()
                        echoGuard.stop(); playing = false
                        ExchangeRuntime.mutable.update { it.copy(publishing = false, playing = false) }
                        clip.turn.cancel()
                        OpusFiles.forgetUpload(file)
                        file.delete()
                    }
                }

            }
        }
        launch {
            while (isActive) {
                try {
                    if (SystemClock.elapsedRealtime() - lastReceiptCheck > 15000 && receiptJob?.isActive != true) {
                        lastReceiptCheck = SystemClock.elapsedRealtime()
                        receiptJob = scope.launch { reconcilePending() }
                    }
                    val s = session ?: return@launch
                    val pollEpoch = epoch
                    val current = rememberSession(api.exchangeSession(s.getString("id")).getJSONObject("session"))
                    if (pollEpoch != epoch || closing) continue
                    rememberSession(current)
                    for (task in current.objects("task_updates")) {
                        val id = task.text("id")
                        if (task.text("status") == "timed_out" && id.isNotBlank() && task.text("question_id").isNotBlank() && id !in notifiedTasks) {
                            val place = task.text("place_name").ifBlank { "那里" }
                            pendingTaskNotices.putIfAbsent(id, "${place}还没有收到现场回应，这次先结束等待。")
                        }
                    }
                    if (current.optJSONObject("chat")?.text("state") == "closed") {
                        if (currentDelivery?.payload?.text("kind") == "chat") { audio.stopPlayback(); playerJob?.cancel() }
                        if (ExchangeRuntime.state.value.phase == "chatting") update("ready", "本次车友交流已结束，可继续向声呐提问")
                    }
                    if (current.text("state") == "ended") { update("ended", "本次交流已结束"); end(); return@launch }
                    if (!localQuiet && (!controlTransition && current.text("state") == "paused" || current.optBoolean("quiet"))) quiet()
                    if (!audioAvailable()) { audio.stopPlayback(); playerJob?.cancel() }
                    if (!localQuiet && audioAvailable() && current.text("state") == "active") {
                        for ((key, ack) in journal.acks()) {
                            sendStoredAck(api, ack)
                            journal.clearAck(key)
                        }
                        run {
                            val feed = api.exchangeInbox(current.getString("id"), journal.excludedDeliveryIds())
                            if (!localQuiet && !closing && localPending.get() == 0 && capture?.speaking != true && submitJob?.isActive != true && playerJob?.isActive != true && contextJob?.isActive != true) {
                                feed.objects("items").map(Delivery::parse)
                                    .firstOrNull { clock.valid(it) && !journal.attempted(it.key) && !journal.deferred(it.key) }
                                    ?.let { playOriginal(it, epoch) }
                            }
                        }
                        if (canPlay(epoch) && submitJob?.isActive != true && playerJob?.isActive != true && contextJob?.isActive != true) {
                            pendingTaskNotices.entries.firstOrNull()?.let { playTaskNotice(it.key, it.value, epoch) }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiFailure && e.status in setOf(401, 403, 410)) {
                        update("unavailable", "语音会话暂不可用，请停车后检查连接")
                        failureVoice(R.raw.voice_failure, stopAfter = true); return@launch
                    }
                }
                delay(1000)
            }
        }
        capture!!.run(onStart = {
            sourceBlocked = playing && capture?.echoProtected != true || submitJob?.isActive == true && !playing
            captureTurn?.cancel()
            captureTurn = CapturingTurn(scope, commands, config, cloud = !sourceBlocked && !localQuiet && !closing,
                contextPostId = session?.text("context_post_id")?.takeUnless { it.isBlank() || it == "null" })
            android.util.Log.i("SonarVoice", "capture_start turn=${captureTurn?.id} at_ms=${SystemClock.elapsedRealtime()}")
            segmentEcho = if (playing) lastPlaybackText else null
            segmentEpoch = epoch
            segmentTime = runCatching { clock.now().toString() }.getOrDefault("")
            val clipEpoch = epoch
            scope.launch {
                if (clipEpoch == epoch && !localQuiet && !closing) update("capturing", "正在收音，停顿后识别发送")
                if (clipEpoch == epoch && playing) {
                    interrupted = currentDelivery
                    currentDelivery?.let { journal.defer(it.key, true) }
                    audio.stopPlayback(); playerJob?.cancel()
                }
            }
        }, onSegment = segment@{ pcm, cut ->
            val turn = captureTurn ?: return@segment
            android.util.Log.i("SonarVoice", "capture_end turn=${turn.id} at_ms=${SystemClock.elapsedRealtime()} last_voice_ms=${capture?.lastVoiceFrameAt} pcm_bytes=${pcm.size}")
            captureTurn = null
            turn.finish()
            val captured = Captured(pcm, cut, sourceBlocked, segmentEpoch, segmentTime, segmentEcho, turn)
            localPending.incrementAndGet()
            if (!segments.trySend(captured).isSuccess) {
                turn.cancel()
                localPending.decrementAndGet()
                if (localQuiet || closing || captured.epoch != epoch) return@segment
                val private = File(cacheDir, "exchange-${UUID.randomUUID()}.wav")
                private.writeBytes(Wav.header(pcm.size) + pcm)
                retain(private); private.delete()
            }
        }, allowed = ::audioAvailable, onReady = {
            scope.launch { update(if (localQuiet) "quiet" else "ready", if (localQuiet) "已保留安静状态" else "正在听") }
        }, isSpeakerEcho = echoGuard::isEcho, onFrame = { frame -> captureTurn?.accept(frame) }, onSilence = {
            captureTurn?.cancel(); captureTurn = null
            scope.launch {
                if (!closing && !localQuiet && ExchangeRuntime.state.value.phase == "capturing" &&
                    localPending.get() == 0 && submitJob?.isActive != true) update("ready", "正在听")
            }
        })
    }

    private fun playOriginal(d: Delivery, expectedEpoch: Int) {
        playerJob = scope.launch {
            var file: File? = null
            var failed = false
            try {
                val bytes = api.download(d.payload.getString("audio_url"))
                val playbackBytes = originalPlaybackLevel(bytes)
                val before = Delivery.parse(api.delivery(d.id).getJSONObject("delivery"))
                val sid = session!!.getString("id")
                val live = rememberSession(api.exchangeSession(sid).getJSONObject("session"))
                if (epoch != expectedEpoch || localQuiet || localPending.get() > 0 || !audioAvailable() || capture?.speaking == true || submitJob?.isActive == true || live.text("state") != "active" ||
                    !clock.valid(before) || before.key != d.key || before.revision != d.revision) return@launch
                val intro = publicationIntro(before.payload, clock.now())
                if (intro.isNotBlank()) playTextNow(intro, expectedEpoch)
                val afterIntro = Delivery.parse(api.delivery(d.id).getJSONObject("delivery"))
                if (!canPlay(expectedEpoch) || submitJob?.isActive == true || !clock.valid(afterIntro) ||
                    afterIntro.key != before.key || afterIntro.revision != before.revision) return@launch
                journal.markAttempt(before.key)
                file = File(cacheDir, "exchange-play-${UUID.randomUUID()}.wav").apply { writeBytes(playbackBytes) }
                currentDelivery = before
                lastPlaybackText = before.payload.text("transcript")
                val synthetic = before.payload.optBoolean("synthetic")
                ExchangeRuntime.mutable.update { it.copy(speaker = if (synthetic) "agent" else "human", responseText = before.payload.text("transcript"),
                    sources = listOf(before.payload), visibility = if (before.payload.text("kind") == "chat") "participants" else before.payload.text("visibility", "public")) }
                audio.play(file!!, ownSpeaker = true,
                    mayStart = { epoch == expectedEpoch && !localQuiet && !closing && localPending.get() == 0 &&
                        capture?.speaking != true && audioAvailable() && clock.valid(afterIntro) },
                    onStarted = {
                        playing = true
                        echoGuard.begin(playbackBytes)
                        update("playing", if (synthetic) "正在听合成声音" else "正在听相关原声")
                        if (before.payload.text("kind") !in setOf("announcement", "knowledge")) {
                            heardContextReady = false
                            syncPlaybackContext(sid, before.payload.getString("post_id"), synthetic, expectedEpoch)
                        }
                    })
                journal.queueAck(before, if (before.type == "invitation") "prompted" else "played")
            } catch (e: CancellationException) {
                currentDelivery?.let { journal.defer(it.key, true) }
                throw e
            } catch (e: Exception) {
                failed = true
                journal.markAttempt(d.key); journal.queueAck(d, "failed")
                update("playback_unavailable", e.message ?: "原声播放暂不可用")
                failureVoice(R.raw.voice_playback_error)
            } finally {
                if (playing) lastAudioEnd = SystemClock.elapsedRealtime()
                echoGuard.stop()
                playing = false
                currentDelivery = null
                file?.delete()
                if (failed) ExchangeRuntime.mutable.update { it.copy(playing = false) }
                if (!closing && !failed) update(if (localQuiet) "quiet" else "ready", if (localQuiet) "已安静，自动分享与播放已暂停" else "现场交流中")
            }
        }
    }

    private fun playAgent(text: String, expectedEpoch: Int, wav: ByteArray? = null) =
        playSourcedAgent(text, expectedEpoch, wav)

    private fun playSourcedAgent(text: String, expectedEpoch: Int, wav: ByteArray? = null, sources: List<JSONObject> = emptyList(), taskId: String? = null) {
        ExchangeRuntime.mutable.update { it.copy(responseText = text, speaker = "agent", sources = sources, visibility = "private") }
        val submitting = submitJob
        playerJob = scope.launch {
            val file = File(cacheDir, "agent-response-${UUID.randomUUID()}.wav")
            try {
                submitting?.join()
                if (epoch != expectedEpoch || localQuiet || closing) return@launch
                if (wav != null) file.writeBytes(wav) else AgentSpeech(this@ExchangeService).synthesize(text, file)
                if (taskId != null && api.personalTask(taskId).text("status") in setOf("cancelled", "resolved", "timed_out", "failed")) {
                    ExchangeRuntime.mutable.update { it.copy(responseText = "") }
                    update("private", "任务已结束，这条回应没有播放")
                    return@launch
                }
                for ((purpose, group) in sources.filter { it.text("post_id").isNotBlank() }.groupBy { it.text("retrieval_purpose", "all") }) {
                    if (!api.validateInformation(group.map { it.getString("post_id") }, purpose)) {
                        ExchangeRuntime.mutable.update { it.copy(responseText = "") }
                        update("private", "来源已更新或失效，这条回应没有播放")
                        return@launch
                    }
                }
                if (sources.any { it.text("valid_until").isNotBlank() && clock.remaining(it.getString("valid_until")) <= 0 }) return@launch
                if (epoch != expectedEpoch || localQuiet || closing || capture?.speaking == true || localPending.get() > 0 || !audioAvailable()) return@launch
                lastPlaybackText = text
                audio.play(file, AudioMode.PROMPT, ownSpeaker = true,
                    mayStart = { epoch == expectedEpoch && !localQuiet && !closing && localPending.get() == 0 && capture?.speaking != true && audioAvailable() && sources.none { it.text("valid_until").isNotBlank() && clock.remaining(it.getString("valid_until")) <= 0 } },
                    onStarted = { playing = true; echoGuard.begin(file.readBytes()); update("agent_response", "声呐正在回应") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closing && !localQuiet) {
                    update("unavailable", "中文语音暂不可用，请停车后检查")
                    failureVoice(R.raw.voice_failure, stopAfter = true)
                }
            } finally {
                if (playing) lastAudioEnd = SystemClock.elapsedRealtime()
                echoGuard.stop()
                playing = false
                file.delete()
                ExchangeRuntime.mutable.update { it.copy(playing = false) }
            }
        }
    }

    private suspend fun handleVoiceAction(action: JSONObject, response: JSONObject?, expectedEpoch: Int) {
        when (action.text("type")) {
            "location" -> {
                epoch++
                audio.stopPlayback(); playerJob?.cancel(); contextJob?.cancel()
                heardContextReady = true
                val real = action.optBoolean("use_real_location")
                val selected = if (real) "" else action.getString("place_id")
                locationMutex.withLock {
                    val store = ConfigStore(this)
                    store.save(store.load().copy(remotePlaceId = selected))
                    config = store.load()
                    if (real) {
                        ExchangeRuntime.mutable.update { it.copy(place = "手机位置待更新", remote = false) }
                        try {
                            val fix = PhoneLocation(this).current()
                            val profile = Profile.parse(api.me().getJSONObject("profile"))
                            applyProfile(api.location(profile.revision, fix).getJSONObject("profile").getJSONObject("presence"))
                            rememberSession(api.exchangeSession(session!!.getString("id")).getJSONObject("session"))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            playSourcedAgent("手机真实位置暂未取得。你可以继续与我交谈，停车后再检查定位权限。", epoch)
                            return
                        }
                    }
                }
                val text = response?.text("text").orEmpty()
                if (text.isNotBlank()) playSourcedAgent(text, epoch)
            }
            "play_audio" -> {
                val items = action.objects("items")
                val submitting = submitJob
                val previous = playerJob
                previous?.cancel()
                playerJob = scope.launch {
                    previous?.join()
                    submitting?.join()
                    try {
                        val preface = action.text("preface")
                        if (preface.isNotBlank()) playTextNow(preface, expectedEpoch)
                        for (item in items) {
                            if (!canPlay(expectedEpoch)) break
                            playSelectedOriginal(item, expectedEpoch)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failureVoice(R.raw.voice_playback_error) }
                }
            }
        }
    }

    private fun canPlay(expectedEpoch: Int) = epoch == expectedEpoch && !localQuiet && !closing &&
        localPending.get() == 0 && capture?.speaking != true && audioAvailable()

    private suspend fun playTextNow(text: String, expectedEpoch: Int) {
        val file = File(cacheDir, "voice-preface-${UUID.randomUUID()}.wav")
        try {
            try { AgentSpeech(this).synthesize(text, file) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (text.contains("合成测试声音")) resources.openRawResource(R.raw.voice_sample_intro).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } else throw e
            }
            if (!canPlay(expectedEpoch)) return
            lastPlaybackText = text
            ExchangeRuntime.mutable.update { it.copy(speaker = "agent", responseText = text) }
            audio.play(file, AudioMode.PROMPT, ownSpeaker = true, mayStart = { canPlay(expectedEpoch) },
                onStarted = { playing = true; echoGuard.begin(file.readBytes()); update("agent_response", "声呐正在回应") })
        } finally { echoGuard.stop(); playing = false; file.delete() }
    }

    private suspend fun playSelectedOriginal(item: JSONObject, expectedEpoch: Int) {
        val postId = item.getString("post_id")
        val bytes = api.download(item.getString("audio_url"))
        val playbackBytes = originalPlaybackLevel(bytes)
        val message = api.message(postId).getJSONObject("message")
        val state = message.getJSONObject("state")
        check(state.text("publication") == "published" && state.text("applicability") !in setOf("expired", "not_started")) { "原声已失效" }
        val until = message.getJSONObject("time").text("valid_until")
        if (!canPlay(expectedEpoch) || until.isNotBlank() && clock.remaining(until) <= 0) return
        val publication = JSONObject(item.toString()).put("published_at", message.getJSONObject("time").text("published_at"))
        val intro = publicationIntro(publication, clock.now())
        if (intro.isNotBlank()) playTextNow(intro, expectedEpoch)
        val afterIntro = api.message(postId).getJSONObject("message").getJSONObject("state")
        if (!canPlay(expectedEpoch) || afterIntro.text("publication") != "published" ||
            afterIntro.text("applicability") in setOf("expired", "not_started") ||
            until.isNotBlank() && clock.remaining(until) <= 0) return
        val file = File(cacheDir, "voice-selected-${UUID.randomUUID()}.wav").apply { writeBytes(playbackBytes) }
        val synthetic = item.optBoolean("synthetic") || message.getJSONObject("body").text("content_origin") == "agent_synthesized"
        try {
            lastPlaybackText = item.text("transcript")
            ExchangeRuntime.mutable.update { it.copy(speaker = if (synthetic) "agent" else "human",
                responseText = item.text("transcript"), sources = listOf(item)) }
            audio.play(file, ownSpeaker = true, mayStart = { canPlay(expectedEpoch) && (until.isBlank() || clock.remaining(until) > 0) }, onStarted = {
                playing = true; echoGuard.begin(playbackBytes); update("playing", if (synthetic) "正在听合成测试声音" else "正在听原声")
                if (item.text("kind") !in setOf("announcement", "knowledge")) {
                    heardContextReady = false
                    syncPlaybackContext(session!!.getString("id"), postId, synthetic, expectedEpoch)
                }
            })
        } finally {
            echoGuard.stop(); playing = false; file.delete()
            if (!localQuiet && !closing) update("ready", "正在听")
        }
    }

    private fun syncPlaybackContext(sid: String, postId: String, synthetic: Boolean, expectedEpoch: Int) {
        contextJob?.cancel()
        contextJob = scope.launch {
            try {
                val live = rememberSession(api.exchangeSession(sid).getJSONObject("session"))
                if (epoch != expectedEpoch || localQuiet || closing) return@launch
                val synced = api.exchangeContext(sid, live.getInt("revision"), postId).getJSONObject("session")
                if (synthetic) {
                    val policy = synced.getJSONObject("voice_policy")
                    check(policy.text("last_audio_post_id") == postId && policy.text("heard_post_id").isBlank() &&
                        synced.text("context_post_id").isBlank()) { "合成试听不能建立真人回应上下文" }
                }
                rememberSession(synced)
                if (epoch == expectedEpoch && !closing) heardContextReady = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Following speech stays private until actual playback context is verified. */ }
        }
    }

    private fun playTaskNotice(id: String, text: String, expectedEpoch: Int) {
        playerJob = scope.launch {
            val file = File(cacheDir, "voice-task-${UUID.randomUUID()}.wav")
            try {
                noticeSynthesis(text, file)
                if (!canPlay(expectedEpoch)) return@launch
                lastPlaybackText = text
                ExchangeRuntime.mutable.update { it.copy(speaker = "agent", responseText = text, sources = emptyList()) }
                audio.play(file, AudioMode.PROMPT, ownSpeaker = true, mayStart = { canPlay(expectedEpoch) }, onStarted = {
                    notifiedTasks.add(id); pendingTaskNotices.remove(id)
                    playing = true; echoGuard.begin(file.readBytes()); update("agent_response", "等待已结束")
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failureVoice(R.raw.voice_failure, stopAfter = true) }
            finally {
                echoGuard.stop(); playing = false; file.delete()
                if (!localQuiet && !closing) update("ready", "正在听")
            }
        }
    }

    private fun failureVoice(resource: Int, stopAfter: Boolean = false) {
        val failureEpoch = epoch
        val previous = playerJob
        val submitting = submitJob
        previous?.cancel()
        playerJob = scope.launch {
            previous?.join()
            // A second captured segment may need feedback while the first turn is
            // awaiting its reply. Do not occupy the audio gate ahead of that reply.
            if (!stopAfter) submitting?.join()
            if (stopAfter) {
                capture?.stop()
                localQuiet = true
                update("unavailable", ExchangeRuntime.state.value.message.ifBlank { "语音暂不可用，请停车后检查" })
            }
            val file = File(cacheDir, "voice-failure-${UUID.randomUUID()}.wav")
            try {
                var waited = 0
                while (!closing && capture?.speaking == true && waited < 30000) { delay(50); waited += 50 }
                if (closing || failureEpoch != epoch || localQuiet && !stopAfter) return@launch
                resources.openRawResource(resource).use { input -> file.outputStream().use { input.copyTo(it) } }
                audio.play(file, AudioMode.PROMPT, ownSpeaker = true,
                    mayStart = { !closing && failureEpoch == epoch &&
                        (stopAfter || !localQuiet && submitJob?.isActive != true && capture?.speaking != true) },
                    onStarted = {
                        playing = true
                        echoGuard.begin(file.readBytes())
                        ExchangeRuntime.mutable.update { it.copy(playing = true, speaker = "system") }
                        android.util.Log.i("SonarVoice", "system_feedback_started")
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("SonarVoice", "feedback_playback_failed type=${e.javaClass.simpleName}") }
            finally {
                echoGuard.stop(); playing = false; file.delete()
                ExchangeRuntime.mutable.update { it.copy(playing = false) }
                if (stopAfter) end()
            }
        }
    }

    private fun cancelCurrentNeed() {
        epoch++
        val requestEpoch = epoch
        audio.stopPlayback(); playerJob?.cancel(); submitJob?.cancel()
        val previousControl = controlJob
        previousControl?.cancel()
        controlTransition = true
        controlJob = scope.launch {
            try {
                previousControl?.join()
                val old = rememberSession(api.exchangeSession(session!!.getString("id")).getJSONObject("session"))
                // Revoke an in-flight turn even if it has not created a durable task yet.
                rememberSession(api.changeExchange(old.getString("id"), old.getInt("revision"), "paused").getJSONObject("session"))
                val tasks = api.personalTasks().objects("items")
                val unfinished = tasks.filter { it.text("status") !in setOf("cancelled", "resolved", "timed_out", "failed") }
                val target = unfinished.firstOrNull { it.text("request_id") == lastRequestId }
                    ?: unfinished.firstOrNull { it.text("status") in setOf("waiting", "querying", "clarifying") }
                if (target != null) {
                    try { api.taskAction(target.getString("id"), target.getInt("revision"), UUID.randomUUID().toString(), "cancel") }
                    catch (e: ApiFailure) {
                        if (e.status != 409) throw e
                        val current = api.personalTask(target.getString("id"))
                        if (current.text("status") !in setOf("cancelled", "resolved", "timed_out", "failed"))
                            api.taskAction(current.getString("id"), current.getInt("revision"), UUID.randomUUID().toString(), "cancel")
                    }
                }
                if (closing || requestEpoch != epoch) return@launch
                if (!localQuiet) {
                    val latest = rememberSession(api.exchangeSession(old.getString("id")).getJSONObject("session"))
                    rememberSession(api.changeExchange(old.getString("id"), latest.getInt("revision"), "active").getJSONObject("session"))
                    playSourcedAgent(if (target == null) "目前没有需要等待的问题。" else "好，不再等这个问题。", requestEpoch)
                } else update("quiet", "已取消等待，仍保持安静")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                update("unknown", "取消结果待核对，安静状态不变")
                if (!localQuiet) failureVoice(R.raw.voice_unknown)
            } finally { controlTransition = false }
        }
    }

    private fun undoLastPublication() {
        val before = ExchangeRuntime.state.value
        val wasPending = uploadInFlight
        val requestId = lastRequestId
        quiet()
        val paused = controlJob
        controlJob = scope.launch {
            try {
                paused?.join()
                var postId = before.lastPostId
                var kind = before.lastKind
                if (wasPending && requestId != null) {
                    val receipt = api.requestResult(requestId)
                    val turn = receipt.optJSONObject("turn")
                    if (turn != null) {
                        journal.recordTurnResult(requestId, turn)
                        postId = turn.text("post_id").ifBlank { null }
                        kind = turn.text("action")
                        if (turn.text("status") in setOf("deciding", "clarifying")) {
                            update("unknown", "已暂停，刚才的发送结果仍待核对")
                            return@launch
                        }
                    }
                }
                when {
                    kind == "scene" && postId != null -> {
                        api.withdrawVoice(postId)
                        update("quiet", "刚才的现场分享已撤回，仍保持安静")
                    }
                    kind == "question" && postId != null -> {
                        api.cancel(postId)
                        update("quiet", "刚才的提问已取消，仍保持安静")
                    }
                    postId != null -> update("quiet", "这条原声已发出，当前暂不能撤回；仍保持安静")
                    else -> update("quiet", "已停止待发送内容，仍保持安静")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update("unknown", "已暂停，刚才的发送或撤回结果待核对") }
        }
    }

    private fun retain(file: File) {
        if (!::privateDir.isInitialized || !file.exists()) return
        synchronized(this) {
            file.copyTo(File(privateDir, file.name), overwrite = true)
            privateDir.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() }
                ?.drop(5)?.forEach { it.delete() }
        }
    }

    private fun handleCommand(command: String) {
        when (command) {
            "quiet" -> quiet()
            "resume" -> resume()
            "end" -> end()
            "stop" -> {
                audio.stopPlayback(); playerJob?.cancel()
                interrupted?.let { journal.defer(it.key, true) }
                update("ready", "当前声音已停，现场交流继续")
            }
            "cancel" -> cancelCurrentNeed()
            "undo" -> undoLastPublication()
        }
    }

    private fun quiet() {
        captureTurn?.cancel(); captureTurn = null
        if (closing) return
        requestedQuiet = true
        localQuiet = true; epoch++
        audio.stopPlayback(); playerJob?.cancel(); submitJob?.cancel()
        update("quiet", "已安静，自动分享与播放已暂停")
        controlJob?.cancel()
        controlJob = scope.launch {
            runCatching {
                val s = session ?: return@runCatching
                val latest = rememberSession(api.exchangeSession(s.getString("id")).getJSONObject("session"))
                rememberSession(api.changeExchange(s.getString("id"), latest.getInt("revision"), "paused").getJSONObject("session"))
                val p = Profile.parse(api.me().getJSONObject("profile"))
                api.context(json("expected_revision" to p.revision, "assist_enabled" to p.assist, "focus" to p.focus, "mode" to "quiet"))
            }
        }
    }

    private fun resume() {
        if (closing || !localQuiet) return
        requestedQuiet = false
        if (session == null) { localQuiet = false; epoch++; update("starting", "正在准备语音"); return }
        val resumeEpoch = epoch
        val pending = controlJob
        controlJob = scope.launch {
            pending?.join()
            if (closing || epoch != resumeEpoch || !localQuiet) return@launch
            try {
                val p = Profile.parse(api.me().getJSONObject("profile"))
                api.context(json("expected_revision" to p.revision, "assist_enabled" to p.assist, "focus" to p.focus, "mode" to "listen"))
                val s = rememberSession(api.exchangeSession(session!!.getString("id")).getJSONObject("session"))
                rememberSession(api.changeExchange(s.getString("id"), s.getInt("revision"), "active").getJSONObject("session"))
                if (epoch != resumeEpoch || closing) return@launch
                epoch++; localQuiet = false; update("ready", "正在听")
                scope.launch { reconcilePending() }
            } catch (_: Exception) { update("quiet", "仍保持安静，稍后再恢复") }
        }
    }

    private fun end() {
        captureTurn?.cancel(); captureTurn = null
        if (closing) return
        closing = true; epoch++
        controlJob?.cancel(); contextJob?.cancel()
        capture?.stop(); audio.close(); playerJob?.cancel(); submitJob?.cancel()
        playing = false
        if (ExchangeRuntime.state.value.phase != "unavailable") update("ending", "麦克风已关闭，正在结束本次交流")
        scope.launch {
            val known = session ?: withTimeoutOrNull(15000) { startResolved.await() }
            if (known != null) withTimeoutOrNull(3000) {
                repeat(2) {
                    try {
                        val latest = rememberSession(api.exchangeSession(known.getString("id")).getJSONObject("session"))
                        rememberSession(api.changeExchange(latest.getString("id"), latest.getInt("revision"), "ended").getJSONObject("session"))
                        return@withTimeoutOrNull
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Retry only the same end operation, never a voice turn. */ }
                }
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        captureTurn?.cancel(); captureTurn = null
        while (true) { val queued = segments.tryReceive().getOrNull() ?: break; queued.turn.cancel() }
        capture?.stop(); audio.close(); scope.cancel(); segments.close()
        ExchangeRuntime.mutable.update { it.copy(owned = false, publishing = false, playing = false,
            phase = if (it.phase == "unavailable") it.phase else "off") }
        if (ExchangeRuntime.activeService === this) ExchangeRuntime.activeService = null
        super.onDestroy()
    }

    companion object {
        const val START = "com.sonar.exchange.START"
        const val STOP = "com.sonar.exchange.STOP"
        const val QUIET = "com.sonar.exchange.QUIET"
        const val RESUME = "com.sonar.exchange.RESUME"
        fun command(context: Context, action: String) {
            context.startService(Intent(context, ExchangeService::class.java).setAction(action))
        }
    }
}
