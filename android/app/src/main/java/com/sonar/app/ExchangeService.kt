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
import kotlinx.coroutines.CancellationException
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
    val quiet: Boolean = false,
    val publishing: Boolean = false,
    val playing: Boolean = false,
    val lastPostId: String? = null,
    val lastKind: String? = null,
    val privateCount: Int = 0,
    val localCommands: Boolean = false,
    val echoProtected: Boolean = false,
)

object ExchangeRuntime {
    internal val mutable = MutableStateFlow(ExchangeState())
    val state = mutable.asStateFlow()
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
    private val segments = Channel<Captured>(1)
    private data class Captured(val pcm: ByteArray, val cut: Boolean, val blocked: Boolean, val epoch: Int, val time: String, val echo: String?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AudioCoordinator(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("exchange", "现场交流", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                if (runJob != null) return START_NOT_STICKY
                ExchangeRuntime.mutable.value = ExchangeState(owned = true, phase = "starting", message = "正在开启本次现场交流", place = intent.getStringExtra("place_label").orEmpty())
                val notification = NotificationCompat.Builder(this, "exchange")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("声呐现场交流")
                    .setContentText("麦克风用于本次交流；可随时安静或结束")
                    .setSilent(true).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(0, "安静", actionIntent(QUIET, 1))
                    .addAction(0, "结束", actionIntent(STOP, 2)).build()
                try {
                    if (Build.VERSION.SDK_INT >= 30)
                        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    else if (Build.VERSION.SDK_INT >= 29)
                        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
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

    private fun audioAvailable() = getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL

    private fun update(phase: String, message: String) {
        ExchangeRuntime.mutable.update { it.copy(phase = phase, message = message, quiet = localQuiet,
            playing = playing, echoProtected = capture?.echoProtected == true,
            privateCount = if (::privateDir.isInitialized) privateDir.listFiles()?.count { f -> f.extension == "wav" } ?: 0 else 0) }
    }

    private suspend fun runExchange(intent: Intent) {
        try {
            check(Build.VERSION.SDK_INT >= 28) { "自然交流需要 Android 9 或以上" }
            config = ConfigStore(this).load()
            check(!config.mock && !config.debugTranscript && !config.simulatedLocation && config.testPlaceId.isBlank()) { "自然交流使用真实定位和语音，请先退出模拟与手动转写模式" }
            check(config.token.isNotBlank()) { "请先配置声呐连接" }
            val publish = intent.getBooleanExtra("auto_publish", false)
            val play = intent.getBooleanExtra("auto_play", false)
            check(!publish || config.linxToken.isNotBlank()) { "请先绑定灵矽，验证真实语音识别" }
            check(PhoneLocation(this).permitted()) { "请先允许定位" }
            api = HttpSonarApi(config.baseUrl, config.token, clock, BuildConfig.DEBUG)
            journal = TaskStore(this, config.scope)
            privateDir = File(filesDir, "exchange-private/${config.scope}").apply { mkdirs() }
            val profile = Profile.parse(api.me().getJSONObject("profile"))
            check(profile.mode != "quiet") { "当前已安静，请先恢复收听" }
            session = api.startExchange(json("client_request_id" to UUID.randomUUID().toString(),
                "expected_revision" to profile.revision, "place_id" to intent.getStringExtra("place_id"),
                "auto_publish" to publish, "auto_play" to play)).getJSONObject("session")
            val commands = LocalSpeechCommands(this)
            // A recognizer service being installed does not prove the Chinese offline model works.
            ExchangeRuntime.mutable.update { it.copy(localCommands = false) }
            capture = ExchangeCapture()
            launchChildren(commands, publish, play)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update("unavailable", e.message ?: "自然交流不可用")
            end()
        }
    }

    private suspend fun launchChildren(commands: LocalSpeechCommands, publish: Boolean, play: Boolean) = kotlinx.coroutines.coroutineScope {
        launch {
            while (isActive) {
                try {
                    val me = api.me().getJSONObject("profile")
                    val fix = PhoneLocation(this@ExchangeService).current()
                    api.location(me.getInt("revision"), fix)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Server refuses automatic publishing with a stale fix. */ }
                delay(15000)
            }
        }
        launch {
            for (clip in segments) {
                if (clip.epoch != epoch || closing) { localPending.decrementAndGet(); continue }
                val file = File(cacheDir, "exchange-${UUID.randomUUID()}.wav")
                file.writeBytes(Wav.header(clip.pcm.size) + clip.pcm)
                // Local controls must not wait for either cloud recognition or a pending upload.
                val local = runCatching { commands.recognize(file) }.getOrNull()
                localPending.decrementAndGet()
                ExchangeRuntime.mutable.update { it.copy(localCommands = !local.isNullOrBlank()) }
                val localCommand = local?.let(::localExchangeCommand)
                if (localCommand != null) {
                    file.delete()
                    if (!looksLikePlaybackEcho(local, clip.echo, minLength = 2)) handleCommand(localCommand)
                    continue
                }
                if (clip.epoch != epoch || localQuiet || !publish || closing || !audioAvailable()) { file.delete(); continue }
                if (clip.cut || clip.blocked || submitJob?.isActive == true) {
                    retain(file); file.delete(); update("private", "这段保持私有，现场交流继续"); continue
                }
                submitJob = launch {
                    var uploadStarted = false
                    try {
                        ExchangeRuntime.mutable.update { it.copy(publishing = true) }
                        update("recognizing", "正在处理本次发言")
                        val transcript = local?.takeIf { it.isNotBlank() }
                            ?: LinxSession(allowLocal = BuildConfig.DEBUG).transcribe(file, config)
                        val command = localExchangeCommand(transcript)
                        if (looksLikePlaybackEcho(transcript, clip.echo, minLength = if (command == null) 4 else 2)) {
                            retain(file); update("private", "这段可能含扬声器回声，保持私有"); return@launch
                        }
                        if (command != null) { handleCommand(command); return@launch }
                        ExchangeRuntime.mutable.update { it.copy(lastPostId = null, lastKind = null) }
                        contextJob?.join()
                        if (!heardContextReady) { retain(file); update("private", "原声上下文尚未同步，这段保持私有"); return@launch }
                        val revision = session!!.getInt("revision")
                        if (clip.epoch != epoch || localQuiet || closing || !audioAvailable()) { retain(file); return@launch }
                        uploadStarted = true
                        uploadInFlight = true
                        val result = api.exchangeUpload(session!!.getString("id"), file,
                            json("client_request_id" to file.nameWithoutExtension.removePrefix("exchange-"),
                                "expected_session_revision" to revision, "transcript" to transcript, "recorded_at" to clip.time))
                        if (clip.epoch != epoch || localQuiet || closing) return@launch
                        if (result.text("status") == "published") {
                            ExchangeRuntime.mutable.update { it.copy(lastPostId = result.text("post_id"), lastKind = result.text("action")) }
                            update("shared", "已分享现场原声") // UI only; never TTS.
                        } else if (result.text("status") == "ignored") update("ready", "现场交流中")
                        else { retain(file); update("private", "这段保持私有，现场交流继续") }
                    } catch (e: CancellationException) {
                        if (uploadStarted) {
                            retain(file)
                            update("unknown", "交流已暂停；刚才的发送结果待核对，不会自动补发")
                        }
                        throw e
                    }
                    catch (e: Exception) {
                        retain(file)
                        update(if (uploadStarted) "unknown" else "private",
                            if (uploadStarted) "发送结果待核对，这段不会自动补发"
                            else if (e is ApiFailure && e.status in setOf(401, 403, 410)) e.message else "这段暂存，等待网络或位置恢复")
                    } finally {
                        uploadInFlight = false
                        ExchangeRuntime.mutable.update { it.copy(publishing = false) }
                        file.delete()
                    }
                }

            }
        }
        launch {
            while (isActive) {
                try {
                    val s = session ?: return@launch
                    val pollEpoch = epoch
                    val current = api.exchangeSession(s.getString("id")).getJSONObject("session")
                    if (pollEpoch != epoch || closing) continue
                    if (current.getInt("revision") >= (session?.optInt("revision") ?: 0)) session = current
                    if (current.text("state") == "ended") { update("ended", "本次交流已结束"); end(); return@launch }
                    if (!localQuiet && (current.text("state") == "paused" || current.optBoolean("quiet"))) quiet()
                    if (!audioAvailable()) { audio.stopPlayback(); playerJob?.cancel() }
                    if (!localQuiet && audioAvailable() && current.text("state") == "active") {
                        for ((key, ack) in journal.acks()) {
                            sendStoredAck(api, ack)
                            journal.clearAck(key)
                        }
                        if (play) {
                            val feed = api.exchangeInbox(current.getString("id"), journal.excludedDeliveryIds())
                            if (!localQuiet && !closing && localPending.get() == 0 && capture?.speaking != true && submitJob?.isActive != true && playerJob?.isActive != true && contextJob?.isActive != true) {
                                feed.objects("items").map(Delivery::parse)
                                    .firstOrNull { clock.valid(it) && !journal.attempted(it.key) && !journal.deferred(it.key) }
                                    ?.let { playOriginal(it, epoch) }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiFailure && (e.status in setOf(401, 403) || e.status == 410 && !localQuiet)) {
                        update("unavailable", e.message); end(); return@launch
                    }
                }
                delay(1000)
            }
        }
        update("ready", "现场交流中")
        capture!!.run(onStart = {
            val speakerWindow = playing || SystemClock.elapsedRealtime() - lastAudioEnd < 500
            sourceBlocked = speakerWindow && capture?.echoProtected != true
            segmentEcho = if (speakerWindow) lastPlaybackText else null
            segmentEpoch = epoch
            segmentTime = runCatching { clock.now().toString() }.getOrDefault("")
            val clipEpoch = epoch
            scope.launch {
                if (clipEpoch == epoch && playing) {
                    interrupted = currentDelivery
                    currentDelivery?.let { journal.defer(it.key, true) }
                    audio.stopPlayback(); playerJob?.cancel()
                }
            }
        }, onSegment = segment@{ pcm, cut ->
            val captured = Captured(pcm, cut, sourceBlocked, segmentEpoch, segmentTime, segmentEcho)
            localPending.incrementAndGet()
            if (!segments.trySend(captured).isSuccess) {
                localPending.decrementAndGet()
                if (localQuiet || !publish || closing || captured.epoch != epoch) return@segment
                val private = File(cacheDir, "exchange-${UUID.randomUUID()}.wav")
                private.writeBytes(Wav.header(pcm.size) + pcm)
                retain(private); private.delete()
            }
        }, allowed = ::audioAvailable)
    }

    private fun playOriginal(d: Delivery, expectedEpoch: Int) {
        playerJob = scope.launch {
            var file: File? = null
            var failed = false
            try {
                val bytes = api.download(d.payload.getString("audio_url"))
                val before = Delivery.parse(api.delivery(d.id).getJSONObject("delivery"))
                val sid = session!!.getString("id")
                val live = api.exchangeSession(sid).getJSONObject("session")
                if (epoch != expectedEpoch || localQuiet || localPending.get() > 0 || !audioAvailable() || capture?.speaking == true || submitJob?.isActive == true || live.text("state") != "active" ||
                    !clock.valid(before) || before.key != d.key || before.revision != d.revision) return@launch
                journal.markAttempt(before.key)
                file = File(cacheDir, "exchange-play-${UUID.randomUUID()}.wav").apply { writeBytes(bytes) }
                currentDelivery = before
                lastPlaybackText = before.payload.text("transcript")
                audio.play(file!!, ownSpeaker = true,
                    mayStart = { epoch == expectedEpoch && !localQuiet && !closing && localPending.get() == 0 &&
                        capture?.speaking != true && audioAvailable() },
                    onStarted = {
                        playing = true
                        heardContextReady = false
                        update("playing", "正在听相关原声")
                        contextJob = scope.launch {
                            try {
                                api.exchangeContext(sid, live.getInt("revision"), before.payload.getString("post_id"))
                                if (epoch == expectedEpoch && !closing) heardContextReady = true
                            } catch (_: Exception) { /* Following speech stays private without a verified parent. */ }
                        }
                    })
                journal.queueAck(before, "played")
            } catch (e: CancellationException) {
                currentDelivery?.let { journal.defer(it.key, true) }
                throw e
            } catch (e: Exception) {
                failed = true
                journal.markAttempt(d.key); journal.queueAck(d, "failed")
                update("playback_unavailable", e.message ?: "原声播放暂不可用")
            } finally {
                if (playing) lastAudioEnd = SystemClock.elapsedRealtime()
                playing = false
                currentDelivery = null
                file?.delete()
                if (failed) ExchangeRuntime.mutable.update { it.copy(playing = false) }
                if (!closing && !failed) update(if (localQuiet) "quiet" else "ready", if (localQuiet) "已安静，自动分享与播放已暂停" else "现场交流中")
            }
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
            "undo" -> {
                val pending = uploadInFlight
                quiet()
                val last = ExchangeRuntime.state.value
                if (pending) update("unknown", "交流已暂停；刚才的发送结果待核对，不会自动补发")
                else if (last.lastKind == "scene" && last.lastPostId != null) scope.launch {
                    runCatching { api.withdrawVoice(last.lastPostId) }
                        .onSuccess { update("quiet", "刚才的现场分享已撤回，交流已暂停") }
                        .onFailure { update("quiet", "交流已暂停，刚才的发送结果待核对") }
                }
            }
        }
    }

    private fun quiet() {
        if (closing) return
        localQuiet = true; epoch++
        audio.stopPlayback(); playerJob?.cancel(); submitJob?.cancel()
        update("quiet", "已安静，自动分享与播放已暂停")
        controlJob?.cancel()
        controlJob = scope.launch {
            runCatching {
                val s = session ?: return@runCatching
                val latest = api.exchangeSession(s.getString("id")).getJSONObject("session")
                session = api.changeExchange(s.getString("id"), latest.getInt("revision"), "paused").getJSONObject("session")
                val p = Profile.parse(api.me().getJSONObject("profile"))
                api.context(json("expected_revision" to p.revision, "assist_enabled" to p.assist, "focus" to p.focus, "mode" to "quiet"))
            }
        }
    }

    private fun resume() {
        if (closing || !localQuiet) return
        val resumeEpoch = epoch
        val pending = controlJob
        controlJob = scope.launch {
            pending?.join()
            if (closing || epoch != resumeEpoch || !localQuiet) return@launch
            try {
                val p = Profile.parse(api.me().getJSONObject("profile"))
                api.context(json("expected_revision" to p.revision, "assist_enabled" to p.assist, "focus" to p.focus, "mode" to "listen"))
                val s = api.exchangeSession(session!!.getString("id")).getJSONObject("session")
                session = api.changeExchange(s.getString("id"), s.getInt("revision"), "active").getJSONObject("session")
                if (epoch != resumeEpoch || closing) return@launch
                epoch++; localQuiet = false; update("ready", "现场交流中")
            } catch (_: Exception) { update("quiet", "仍保持安静，稍后再恢复") }
        }
    }

    private fun end() {
        if (closing) return
        closing = true; epoch++
        controlJob?.cancel(); contextJob?.cancel()
        capture?.stop(); audio.close(); playerJob?.cancel(); submitJob?.cancel()
        scope.launch {
            withTimeoutOrNull(3000) {
                runCatching {
                    session?.let {
                        val latest = api.exchangeSession(it.getString("id")).getJSONObject("session")
                        api.changeExchange(latest.getString("id"), latest.getInt("revision"), "ended")
                    }
                }
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        capture?.stop(); audio.close(); scope.cancel(); segments.close()
        ExchangeRuntime.mutable.update { it.copy(owned = false, publishing = false, playing = false,
            phase = if (it.phase == "unavailable") it.phase else "off") }
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
