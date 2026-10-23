package com.sonar.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ScreenState(
    val config: DeviceConfig = DeviceConfig(),
    val connected: Boolean = false,
    val profile: Profile? = null,
    val question: Question? = null,
    val deliveries: List<Delivery> = emptyList(),
    val draft: LocalDraft? = null,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val busy: Boolean = false,
    val playing: String? = null,
    val quiet: Boolean = true,
    val message: String = "配置连接后，向附近的人问一句。",
    val remainingSeconds: Long? = null,
    val audioOutcome: String? = null,
)

class SonarViewModel(app: Application) : AndroidViewModel(app) {
    private val configStore = ConfigStore(app)
    private val audio = AudioCoordinator(app)
    private val clock = ServerClock(SystemClock::elapsedRealtime)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var config = DeviceConfig()
    private var store = TaskStore(app, config.scope)
    private var api: SonarApi? = null
    private var polling: Job? = null
    private var playerJob: Job? = null
    private var recordingJob: Job? = null
    private var foreground = false
    private var generation = 0
    private var pendingMode: String? = null
    private var presenceConfirmed = false
    private var lastHeartbeat = 0L

    init {
        runCatching { configStore.load() }
            .onSuccess { configure(it, false) }
            .onFailure { e -> mutable.update { it.copy(message = e.message ?: "读取配置失败") } }
    }

    fun configure(next: DeviceConfig, save: Boolean = true) {
        polling?.cancel()
        stopPlayback()
        audio.stopRecording()
        recordingJob?.cancel()
        generation++
        config =
            next.copy(
                mock = BuildConfig.DEBUG && next.mock,
                debugTranscript = BuildConfig.DEBUG && next.debugTranscript,
            )
        if (save) configStore.save(config)
        store = TaskStore(getApplication(), config.scope)
        pendingMode = store.pendingMode
        presenceConfirmed = false
        var connectionError: String? = null
        api =
            runCatching {
                    if (config.mock) MockSonarApi(clock)
                    else if (config.token.isNotBlank())
                        HttpSonarApi(config.baseUrl, config.token, clock, BuildConfig.DEBUG)
                    else null
                }
                .onFailure { e -> connectionError = e.message ?: "配置无效" }
                .getOrNull()
        mutable.value =
            ScreenState(
                config = config,
                draft = store.draft,
                quiet = true,
                message = connectionError ?: if (api == null) "请先填写服务地址和设备凭证" else "正在连接…",
            )
        if (foreground) activate()
    }

    fun activate() {
        foreground = true
        if (polling?.isActive == true || api == null) return
        polling =
            viewModelScope.launch {
                var backoff = 1000L
                while (isActive && foreground) {
                    try {
                        refresh()
                        backoff = 1000
                        delay(1000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update { it.copy(connected = false, message = errorText(e)) }
                        if (e is ApiFailure && e.status == 401) return@launch
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(10000)
                    }
                }
            }
    }

    fun pause() {
        foreground = false
        presenceConfirmed = false
        polling?.cancel()
        polling = null
        stopPlayback()
        audio.stopRecording()
    }

    private suspend fun refresh() {
        val current = api ?: return
        val me = Me.parse(current.me())
        var profile = me.profile
        pendingMode?.let { desired ->
            if (profile.mode != desired)
                profile =
                    Profile.parse(
                        current.context(contextData(profile, desired)).getJSONObject("profile")
                    )
            pendingMode = null
            store.pendingMode = null
        }
        val quiet = profile.mode == "quiet"
        if (quiet && !mutable.value.quiet) stopPlayback()
        mutable.update {
            it.copy(
                connected = true,
                profile = profile,
                quiet = quiet,
                message =
                    if (it.message.startsWith("正在连接") || it.message.startsWith("连接中断")) "已连接，可以录音提问"
                    else it.message,
            )
        }
        if (presenceConfirmed && SystemClock.elapsedRealtime() - lastHeartbeat > 60000) {
            current.context(contextData(profile))
            lastHeartbeat = SystemClock.elapsedRealtime()
        }
        for ((key, ack) in store.acks()) {
            sendStoredAck(current, ack)
            store.clearAck(key)
        }
        val qid = store.questionId ?: me.recent.firstOrNull()?.also { store.questionId = it }
        val previous = mutable.value.question
        if (qid != null && (previous?.id != qid || previous.status == "waiting")) {
            val question = Question.parse(current.question(qid))
            if (store.questionId == qid) mutable.update { it.copy(question = question) }
        }
        val q = mutable.value.question
        mutable.update {
            it.copy(
                remainingSeconds =
                    if (q?.status == "waiting")
                        (clock.remaining(q.expiresAt) / 1000).coerceAtLeast(0)
                    else null,
                audioOutcome = q?.id?.let(store::outcome),
            )
        }
        val items = current.inbox().objects("items").map(Delivery::parse)
        mutable.update { it.copy(deliveries = items) }
        if (
            audio.gate.mode == AudioMode.IDLE &&
                !mutable.value.busy &&
                !mutable.value.recording &&
                !mutable.value.transcribing &&
                !quiet &&
                playerJob?.isActive != true
        ) {
            items
                .firstOrNull { it.type == "audio" && clock.valid(it) && !store.attempted(it.key) }
                ?.let { playDelivery(it, false) }
            items
                .firstOrNull {
                    it.type == "invitation" && clock.valid(it) && !store.attempted(it.key)
                }
                ?.let { promptInvitation(it) }
        }
    }

    private fun contextData(p: Profile, mode: String = p.mode, assist: Boolean = p.assist) =
        json(
            "expected_revision" to p.revision,
            "presence" to
                json(
                    "place_id" to p.presence.text("place_id"),
                    "direction" to "demo_exit",
                    "source" to "simulated",
                ),
            "mode" to mode,
            "assist_enabled" to assist,
            "focus" to if (mode == "quiet") "现在安静" else "愿意回答附近路口的拥堵问题",
        )

    fun toggleQuiet() {
        val desired = if (mutable.value.quiet) "listen" else "quiet"
        pendingMode = desired
        store.pendingMode = desired
        if (desired == "quiet") {
            stopPlayback()
            mutable.update { it.copy(quiet = true, message = "已在本机静音，正在同步") }
        } else mutable.update { it.copy(message = "正在恢复收听，等待服务确认") }
        polling?.cancel()
        polling = null
        activate()
    }

    fun confirmPresence() = action {
        val p =
            Profile.parse(
                api!!
                    .context(contextData(mutable.value.profile ?: error("请先连接")))
                    .getJSONObject("profile")
            )
        presenceConfirmed = true
        lastHeartbeat = SystemClock.elapsedRealtime()
        mutable.update { it.copy(profile = p, message = "模拟位置已确认，仅在本次前台会话刷新") }
    }

    fun toggleAssist() = action {
        val p = mutable.value.profile ?: error("请先连接")
        val next =
            Profile.parse(
                api!!.context(contextData(p, assist = !p.assist)).getJSONObject("profile")
            )
        mutable.update {
            it.copy(profile = next, message = if (next.assist) "已开启现场互助" else "已关闭现场互助")
        }
    }

    fun record(reply: Delivery? = null) {
        if (
            mutable.value.busy ||
                mutable.value.recording ||
                mutable.value.transcribing ||
                mutable.value.draft != null
        )
            return
        if (!mutable.value.connected) {
            notice("请先连接服务")
            return
        }
        if (reply == null && mutable.value.question?.status == "waiting") {
            notice("请先结束当前提问")
            return
        }
        stopPlayback()
        generation++
        val capturedApi = api ?: return
        val capturedStore = store
        mutable.update { it.copy(recording = true, message = "正在录音，最长8秒；再按一次结束") }
        recordingJob =
            viewModelScope.launch {
                val file =
                    File(getApplication<Application>().filesDir, "voice-${UUID.randomUUID()}.wav")
                try {
                    if (reply != null) {
                        val latest =
                            Delivery.parse(capturedApi.delivery(reply.id).getJSONObject("delivery"))
                        check(
                            latest.state in setOf("pending", "prompted") &&
                                clock.remaining(latest.expiresAt) > 0
                        ) {
                            "邀请已结束"
                        }
                    }
                    val draft =
                        LocalDraft(
                            UUID.randomUUID().toString(),
                            file.absolutePath,
                            replyTo = reply?.questionId,
                            invitationId = reply?.id,
                        )
                    capturedStore.draft = draft
                    audio.record(file)
                    mutable.update { it.copy(recording = false, draft = draft) }
                    if (!foreground) {
                        notice("录音已私有保存，返回后请核对或重录")
                        return@launch
                    }
                    if (config.debugTranscript) {
                        val next = draft.copy(source = "调试转写（手动输入，未经过 STT）")
                        capturedStore.draft = next
                        mutable.update { it.copy(draft = next, message = "已保存录音；填写调试转写并核对原声") }
                    } else {
                        mutable.update { it.copy(transcribing = true, message = "正在获取本轮识别候选…") }
                        check(audio.gate.enter(AudioMode.TRANSCRIBING))
                        val text =
                            try {
                                LinxSession().transcribe(file, config)
                            } finally {
                                audio.gate.leave()
                            }
                        if (capturedStore !== store) return@launch
                        val next = draft.copy(transcript = text, source = "Linx 识别候选，请核对原声")
                        capturedStore.draft = next
                        mutable.update { it.copy(draft = next, message = "核对原声与文字后，由你确认公开") }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!file.exists() || file.length() <= 44) {
                        capturedStore.draft = null
                        mutable.update { it.copy(draft = null) }
                    }
                    notice(errorText(e))
                } finally {
                    if (capturedStore === store)
                        mutable.update {
                            it.copy(
                                recording = false,
                                transcribing = false,
                                draft = capturedStore.draft,
                            )
                        }
                }
            }
    }

    fun finishRecording() {
        audio.stopRecording()
    }

    fun editTranscript(value: String) {
        val draft = mutable.value.draft ?: return
        if (!config.debugTranscript || draft.frozen) return
        val next = draft.copy(transcript = value.take(1000))
        store.draft = next
        mutable.update { it.copy(draft = next) }
    }

    fun discardDraft() {
        if (mutable.value.busy || mutable.value.recording || mutable.value.transcribing) return
        stopPlayback()
        store.draft?.let { File(it.file).delete() }
        store.draft = null
        mutable.update { it.copy(draft = null, message = "草稿已丢弃，可以重新录音") }
    }

    fun publish() = action {
        check(
            !mutable.value.recording &&
                !mutable.value.transcribing &&
                audio.gate.mode == AudioMode.IDLE
        ) {
            "请先结束当前音频"
        }
        var draft = store.draft ?: error("没有待发布录音")
        check(draft.transcript.isNotBlank()) { "没有可核对的转写，本次不会发布" }
        check(File(draft.file).exists()) { "录音文件已丢失，请重录" }
        draft = draft.copy(frozen = true)
        store.draft = draft
        mutable.update { it.copy(draft = draft, message = "正在上传并确认公开…") }
        if (draft.postId == null) {
            val post = retry { api!!.upload(draft) }
            draft = draft.copy(postId = post.getString("post_id"))
            store.draft = draft
            mutable.update { it.copy(draft = draft) }
        }
        val result = retry { api!!.confirm(draft.postId!!) }
        if (draft.replyTo == null) {
            store.questionId = result.getString("question_id")
            mutable.update { it.copy(question = null) }
        }
        File(draft.file).delete()
        store.draft = null
        mutable.update {
            it.copy(
                draft = null,
                message = if (draft.replyTo == null) "已发出提问，Agent 正在替你等回复" else "你的原声回答已发出",
            )
        }
    }

    fun cancelQuestion() = action {
        val id = mutable.value.question?.id ?: return@action
        val q = Question.parse(retry { api!!.cancel(id) })
        mutable.update { it.copy(question = q, message = "提问已取消") }
    }

    fun decline(d: Delivery) = action {
        retry { api!!.ack(d.id, d.requestId, "declined") }
        mutable.update {
            it.copy(
                deliveries = it.deliveries.filterNot { item -> item.id == d.id },
                message = "已拒绝本次邀请",
            )
        }
    }

    fun preview() {
        val draft = store.draft ?: return
        if (mutable.value.quiet) {
            notice("请先恢复收听")
            return
        }
        if (audio.gate.mode != AudioMode.IDLE || mutable.value.busy) return
        playerJob =
            viewModelScope.launch {
                mutable.update { it.copy(playing = "preview") }
                try {
                    audio.play(File(draft.file), AudioMode.PREVIEW)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice(errorText(e))
                } finally {
                    mutable.update { it.copy(playing = null) }
                }
            }
    }

    fun playDelivery(d: Delivery, manual: Boolean = true) {
        if (
            playerJob?.isActive == true ||
                mutable.value.quiet ||
                mutable.value.busy ||
                audio.gate.mode != AudioMode.IDLE ||
                !foreground
        )
            return
        if (!manual && store.attempted(d.key)) return
        val captured = api ?: return
        val capturedStore = store
        val startedGeneration = generation
        playerJob =
            viewModelScope.launch {
                var file: File? = null
                mutable.update { it.copy(playing = d.id) }
                try {
                    val before = Delivery.parse(captured.delivery(d.id).getJSONObject("delivery"))
                    check(
                        clock.valid(before) &&
                            before.requestId == d.requestId &&
                            before.revision == d.revision
                    ) {
                        "播放请求已改变，请等候刷新"
                    }
                    val bytes = captured.download(before.payload.getString("audio_url"))
                    file =
                        File(
                                getApplication<Application>().cacheDir,
                                "play-${UUID.randomUUID()}.wav",
                            )
                            .apply { writeBytes(bytes) }
                    val latest = Delivery.parse(captured.delivery(d.id).getJSONObject("delivery"))
                    if (
                        !foreground ||
                            generation != startedGeneration ||
                            mutable.value.quiet ||
                            audio.gate.mode != AudioMode.IDLE
                    )
                        return@launch
                    check(
                        clock.valid(latest) &&
                            latest.requestId == d.requestId &&
                            latest.revision == d.revision
                    ) {
                        "声音已失效，本次不播放"
                    }
                    capturedStore.markAttempt(d.key)
                    audio.play(file)
                    capturedStore.outcome(d.questionId, "played")
                    capturedStore.queueAck(d, "played")
                    mutable.update {
                        it.copy(
                            message = "原声已播放",
                            audioOutcome =
                                if (it.question?.id == d.questionId) "played" else it.audioOutcome,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    capturedStore.markAttempt(d.key)
                    capturedStore.queueAck(d, "failed")
                    notice(errorText(e))
                } finally {
                    file?.delete()
                    mutable.update { it.copy(playing = null) }
                }
            }
    }

    fun stopPlayback(markSkipped: Boolean = false) {
        generation++
        if (markSkipped)
            mutable.value.deliveries
                .firstOrNull { it.id == mutable.value.playing }
                ?.let {
                    store.outcome(it.questionId, "skipped")
                    store.queueAck(it, "skipped")
                }
        playerJob?.cancel()
        playerJob = null
        audio.stopPlayback()
        mutable.update { it.copy(playing = null) }
    }

    private fun promptInvitation(d: Delivery) {
        if (playerJob?.isActive == true) return
        val currentApi = api ?: return
        val currentStore = store
        playerJob =
            viewModelScope.launch {
                try {
                    val latest = Delivery.parse(currentApi.delivery(d.id).getJSONObject("delivery"))
                    if (
                        !clock.valid(latest) ||
                            latest.requestId != d.requestId ||
                            mutable.value.quiet ||
                            audio.gate.mode != AudioMode.IDLE
                    )
                        return@launch
                    currentStore.markAttempt(d.key)
                    val file = File(getApplication<Application>().cacheDir, "onsite-prompt.wav")
                    getApplication<Application>()
                        .resources
                        .openRawResource(com.sonar.app.R.raw.onsite_question)
                        .use { input -> file.outputStream().use { input.copyTo(it) } }
                    audio.play(file, AudioMode.PROMPT)
                    currentStore.queueAck(d, "alerted")
                    notice("有人询问路口路况，愿意的话回应一句")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice(errorText(e))
                }
            }
    }

    fun notice(message: String) {
        mutable.update { it.copy(message = message) }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(errorText(e))
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun <T> retry(block: suspend () -> T): T {
        var wait = 1000L
        repeat(3) {
            try {
                return block()
            } catch (e: IOException) {
                if (e is ApiFailure && !e.retryable) throw e
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }

    private fun errorText(e: Exception) =
        when (e) {
            is ApiFailure -> e.message
            is IOException -> "连接中断，正在重试；未确认的录音不会公开"
            else -> e.message ?: "操作未完成，请重试"
        }

    override fun onCleared() {
        audio.close()
        super.onCleared()
    }
}
