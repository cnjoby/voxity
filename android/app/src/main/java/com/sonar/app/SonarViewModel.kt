package com.sonar.app

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val selectedPlace: PlaceChoice? = null,
    val nearbyPlaces: List<PlaceChoice> = emptyList(),
    val locating: Boolean = false,
    val locationStatus: String = "开启定位，问问附近的人",
    val catalog: List<org.json.JSONObject> = emptyList(),
    val channels: List<org.json.JSONObject> = emptyList(),
    val announcementStatus: String = "选择地点后，听听这里的声音",
    val catalogPlace: PlaceChoice? = null,
    val conversation: List<org.json.JSONObject> = emptyList(),
    val conversationId: String? = null,
    val canReply: Boolean = false,
    val linx: LinxState = LinxState(),
    val placeShortcuts: List<PlaceChoice> = emptyList(),
    val personalTasks: List<PersonalTask> = emptyList(),
    val pendingRequests: Int = 0,
    val taskServiceAvailable: Boolean = true,
    val taskNotice: String = "",
)

class SonarViewModel(app: Application) : AndroidViewModel(app) {
    private val configStore = ConfigStore(app)
    private val audio = AudioCoordinator(app, onFocusLost = {
        if (!mutable.value.quiet) toggleQuiet()
    })
    private val phoneLocation = PhoneLocation(app)
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
    private var uiVisible = false
    private var automaticAudioSuspended = ExchangeRuntime.state.value.owned
    private var generation = 0
    private var pendingMode: String? = null
    private var presenceConfirmed = false
    private var lastHeartbeat = 0L
    private var locationJob: Job? = null
    private var locationLoop: Job? = null
    private var lastFix: org.json.JSONObject? = null
    private var manualTarget = false
    private var composition: String? = null
    private val received = mutableSetOf<String>()
    private var catalogRefresh = 0L
    private var taskRefresh = 0L
    private var receiptCursor = 0
    private var linxBindingJob: Job? = null
    private var linxVoiceJob: Job? = null

    init {
        runCatching { configStore.load() }
            .onSuccess { configure(it, false) }
            .onFailure { e -> mutable.update { it.copy(message = e.message ?: "读取配置失败") } }
        viewModelScope.launch {
            var owned = ExchangeRuntime.state.value.owned
            ExchangeRuntime.state.collect { current ->
                if (owned && !current.owned) {
                    if (current.lastKind == "question" && current.lastPostId != null) {
                        store.questionId = current.lastPostId
                        mutable.update { it.copy(question = null) }
                    }
                    val saved = runCatching { configStore.load() }.getOrNull()
                    if (saved != null && saved != config) configure(saved, false)
                    if (uiVisible) activate()
                }
                owned = current.owned
            }
        }
    }

    fun startVoiceSession() {
        val s = mutable.value
        if (ExchangeRuntime.state.value.owned) return
        if (s.recording || s.transcribing || s.busy || s.linx.busy) {
            notice("请先完成当前语音检查")
            return
        }
        if (!s.connected || config.token.isBlank()) { notice("请先在连接与隐私中连接声呐"); return }
        if (config.linxToken.isBlank()) { notice("请先在连接与隐私中绑定灵矽语音"); return }
        automaticAudioSuspended = true
        store.automaticAudioSuspended = true
        val visible = uiVisible
        pause()
        uiVisible = visible
        val place = s.selectedPlace
        ExchangeRuntime.mutable.value = ExchangeState(owned = true, phase = "starting", place = place?.label.orEmpty(), message = "正在准备语音")
        runCatching {
            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ExchangeService::class.java)
                .setAction(ExchangeService.START).putExtra("voice_first", true)
                .putExtra("place_id", place?.id.orEmpty()).putExtra("place_label", place?.label.orEmpty())
                .putExtra("remote_place_id", config.remotePlaceId))
        }.onFailure {
            ExchangeRuntime.mutable.value = ExchangeState(phase = "unavailable", message = "系统未允许开启麦克风，请停车后重试")
        }
    }

    fun configure(next: DeviceConfig, save: Boolean = true) {
        if (save && ExchangeRuntime.state.value.owned) ExchangeService.command(getApplication(), ExchangeService.STOP)
        linxBindingJob?.cancel()
        linxVoiceJob?.cancel()
        polling?.cancel()
        locationJob?.cancel()
        locationLoop?.cancel()
        lastFix = null
        manualTarget = false
        composition = null
        received.clear()
        stopPlayback()
        audio.stopRecording()
        recordingJob?.cancel()
        generation++
        taskRefresh = 0L
        config = configStore.normalize(next)
        manualTarget = config.remotePlaceId.isNotBlank()
        if (save) configStore.save(config)
        store = TaskStore(getApplication(), config.scope)
        automaticAudioSuspended = store.automaticAudioSuspended || ExchangeRuntime.state.value.owned
        pendingMode = store.pendingMode
        presenceConfirmed = false
        var connectionError: String? = null
        api =
            runCatching {
                    if (config.token.isNotBlank())
                        HttpSonarApi(config.baseUrl, config.token, clock, BuildConfig.DEBUG)
                    else null
                }
                .onFailure { e -> connectionError = e.message ?: "配置无效" }
                .getOrNull()
        mutable.value =
            ScreenState(
                config = config,
                linx =
                    if (config.linxToken.isNotBlank())
                        LinxState(phase = "bound", status = "已保存绑定配置，等待验证语音连接")
                    else LinxState(),
                draft = store.draft,
                pendingRequests = store.pendingRequests().size,
                quiet = true,
                selectedPlace = null,
                locationStatus = if (config.remotePlaceId.isNotBlank()) "远程地点 · 正在读取高德地点" else "手机位置尚未更新",
                message = connectionError ?: if (api == null) "请先填写服务地址和设备凭证" else "正在连接…",
            )
        if (foreground) activate()
    }

    fun activate() {
        uiVisible = true
        if (ExchangeRuntime.state.value.owned) return
        foreground = true
        if (polling?.isActive == true || api == null) return
        if (config.remotePlaceId.isBlank()) {
            locationLoop?.cancel()
            locationLoop =
                viewModelScope.launch {
                    while (isActive && foreground) {
                        if (
                            config.remotePlaceId.isBlank() &&
                                phoneLocation.permitted() &&
                                mutable.value.connected &&
                                !mutable.value.recording &&
                                !mutable.value.transcribing
                        )
                            refreshLocation()
                        delay(if (mutable.value.connected) 15000 else 1000)
                    }
                }
        }
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

    fun refreshTasks() {
        viewModelScope.launch { refreshPersonalTasks() }
    }

    private suspend fun refreshPersonalTasks() {
        val current = api ?: return
        val taskStore = store
        val requestGeneration = generation
        try {
            val tasks = current.personalTasks().objects("items").map(PersonalTask::parse)
            val receipts = taskStore.pendingRequests()
            val batch = if (receipts.isEmpty()) emptyList() else (0 until minOf(3, receipts.size)).map { receipts[(receiptCursor + it) % receipts.size] }
            receiptCursor = if (receipts.isEmpty()) 0 else (receiptCursor + batch.size) % receipts.size
            for (pending in batch) {
                try {
                    val result = current.requestResult(pending.getString("request_id"))
                    taskStore.recordTurnResult(pending.getString("request_id"), result)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Remains unknown; never resend. */ }
            }
            if (generation == requestGeneration)
                mutable.update { it.copy(personalTasks = tasks, pendingRequests = taskStore.pendingRequests().size, taskServiceAvailable = true, taskNotice = if (it.taskNotice.startsWith("任务核对暂不可用")) "任务已刷新" else it.taskNotice) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (generation == requestGeneration)
                mutable.update { it.copy(pendingRequests = taskStore.pendingRequests().size,
                    taskServiceAvailable = !(e is ApiFailure && e.status == 404), taskNotice = "任务核对暂不可用，保留上次结果；可稍后刷新。") }
        }
    }

    fun changeTask(task: PersonalTask, action: String) {
        val current = api ?: return
        val taskStore = store
        stopPlayback()
        val requestGeneration = generation
        viewModelScope.launch {
            try {
                current.taskAction(task.id, task.revision, taskStore.taskActionId(task.id, task.revision, action), action)
                if (generation == requestGeneration) refreshPersonalTasks()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == requestGeneration) {
                    mutable.update { it.copy(taskNotice = "任务结果待核对，请刷新；重试保留原动作ID") }
                    refreshPersonalTasks()
                }
            }
        }
    }

    fun prepareTaskQuery(task: PersonalTask): Boolean {
        val current = mutable.value
        if (ExchangeRuntime.state.value.owned || current.draft != null || current.recording || current.transcribing || current.busy || current.linx.busy) {
            mutable.update { it.copy(taskNotice = "先处理当前录音或草稿，再重新开启查询。") }
            return false
        }
        if (task.placeId != null && mutable.value.selectedPlace?.id != task.placeId) {
            val label = task.sources.firstOrNull()?.text("place_label").orEmpty().ifBlank { "原任务地点" }
            selectPlace(PlaceChoice(task.placeId, label))
        }
        return true
    }

    /** Explicit history playback never resets completed deliveries or binds a new public reply context. */
    fun hearTaskOriginal(postId: String, deliveryId: String? = null) {
        if (ExchangeRuntime.state.value.owned || playerJob?.isActive == true || mutable.value.busy ||
            mutable.value.recording || mutable.value.transcribing || mutable.value.linx.busy || !foreground) return
        if (mutable.value.quiet) {
            mutable.update { it.copy(taskNotice = "当前已安静，请先返回首页恢复收听，再主动听原声。") }
            return
        }
        val current = api ?: return
        val capturedStore = store
        val requestGeneration = generation
        playerJob = viewModelScope.launch {
            var file: File? = null
            var pendingDelivery: Delivery? = null
            var started = false
            try {
                mutable.update { it.copy(playing = "history:$postId", taskNotice = "正在核对原声来源与时效") }
                val message = VoiceMessageView.parse(current.message(postId).getJSONObject("message"))
                check(message.id == postId && message.body.text("content_origin") == "human_original" &&
                    message.state.text("publication") == "published" && message.state.text("applicability") == "valid") { "这条原声已失效，不能重听" }
                check(current.validateInformation(listOf(postId))) { "原声已更新或失效，不能重听" }
                pendingDelivery = if (deliveryId != null) Delivery.parse(current.delivery(deliveryId).getJSONObject("delivery"))
                    else current.inbox().objects("items").map(Delivery::parse).firstOrNull { it.type == "audio" && it.payload.text("post_id") == postId }
                pendingDelivery = pendingDelivery?.takeIf { clock.valid(it) && it.payload.text("post_id") == postId }
                val bytes = current.download(message.body.getString("audio_ref"))
                file = File(getApplication<Application>().cacheDir, "task-play-${UUID.randomUUID()}.wav").apply { writeBytes(bytes) }
                check(current.validateInformation(listOf(postId))) { "原声已更新或失效，本次不播放" }
                pendingDelivery?.let { before ->
                    val latest = Delivery.parse(current.delivery(before.id).getJSONObject("delivery"))
                    check(clock.valid(latest) && latest.key == before.key && latest.revision == before.revision) { "待听状态已变化，请刷新后重试" }
                }
                audio.play(file, ownSpeaker = true, mayStart = {
                    generation == requestGeneration && foreground && !mutable.value.quiet && !ExchangeRuntime.state.value.owned
                }, onStarted = {
                    started = true
                    pendingDelivery?.let { capturedStore.markAttempt(it.key) }
                    mutable.update { it.copy(taskNotice = "正在播放原声；可随时停止。") }
                })
                pendingDelivery?.let { capturedStore.queueAck(it, "played") }
                if (generation == requestGeneration) mutable.update { it.copy(taskNotice = "原声已听完；任务是否解决仍由你决定。") }
            } catch (e: CancellationException) {
                if (started) pendingDelivery?.let { capturedStore.defer(it.key, true) }
                throw e
            } catch (e: Exception) {
                if (started) pendingDelivery?.let { capturedStore.defer(it.key, true) }
                if (generation == requestGeneration) mutable.update { it.copy(taskNotice = errorText(e)) }
            } finally {
                file?.delete()
                if (generation == requestGeneration) mutable.update { it.copy(playing = null) }
            }
        }
    }

    fun pause() {
        uiVisible = false
        if (ExchangeRuntime.state.value.owned) return
        foreground = false
        linxVoiceJob?.cancel()
        linxBindingJob?.cancel()
        locationLoop?.cancel()
        locationJob?.cancel()
        presenceConfirmed = false
        polling?.cancel()
        polling = null
        stopPlayback()
        audio.stopRecording()
    }

    private suspend fun refresh() {
        val current = api ?: return
        val raw = current.me()
        val me = Me.parse(raw)
        val shortcuts = raw.objects("place_shortcuts").map(PlaceChoice::parse)
        var profile = me.profile
        if (config.remotePlaceId.isNotBlank()) {
            if (profile.presence.text("source") != "remote_place" ||
                profile.presence.text("place_id") != config.remotePlaceId ||
                SystemClock.elapsedRealtime() - lastHeartbeat > 15000) {
                profile = Profile.parse(current.remotePlace(profile.revision, config.remotePlaceId).getJSONObject("profile"))
                lastFix = profile.presence.optJSONObject("location")
                lastHeartbeat = SystemClock.elapsedRealtime()
            }
        } else if (profile.presence.text("source") in setOf("simulated", "simulated_place", "remote_place")) {
            profile = Profile.parse(current.location(profile.revision, null).getJSONObject("profile"))
        }
        mutable.update { state -> state.copy(placeShortcuts = shortcuts,
            selectedPlace = if (config.remotePlaceId.isNotBlank()) shortcuts.firstOrNull { it.id == config.remotePlaceId }
                ?: state.selectedPlace else state.selectedPlace,
            locationStatus = if (config.remotePlaceId.isNotBlank()) "远程地点 · 非手机实到位置" else state.locationStatus) }
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
                    if (it.message.startsWith("正在连接") || it.message.startsWith("连接中断")) "已连接，可以开始语音"
                    else it.message,
            )
        }
        for ((key, ack) in store.acks()) {
            sendStoredAck(current, ack)
            store.clearAck(key)
        }
        val qid = store.questionId ?: me.recent.firstOrNull()?.also { store.questionId = it }
        val previous = mutable.value.question
        if (qid != null && (previous?.id != qid || previous.status == "waiting")) {
            try {
                val question = Question.parse(current.question(qid))
                if (store.questionId == qid) mutable.update { it.copy(question = question) }
            } catch (e: ApiFailure) {
                if (e.status !in setOf(404, 410)) throw e
                if (store.questionId == qid) {
                    store.questionId = null
                    mutable.update {
                        it.copy(
                            question = null, remainingSeconds = null, audioOutcome = null,
                            conversationId = it.conversationId.takeUnless { id -> id == qid },
                            conversation = if (it.conversationId == qid) emptyList() else it.conversation,
                            canReply = it.canReply && it.conversationId != qid,
                            message = "已连接，可以开始语音",
                        )
                    }
                }
            }
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
        run {
            if (SystemClock.elapsedRealtime() - taskRefresh > 5000) {
                refreshPersonalTasks()
                taskRefresh = SystemClock.elapsedRealtime()
            }
            items
                .filter { it.key !in received }
                .forEach { d ->
                    current.ack(d.id, d.requestId, "received")
                    received.add(d.key)
                }
            if (SystemClock.elapsedRealtime() - catalogRefresh > 5000) {
                (mutable.value.catalogPlace ?: mutable.value.selectedPlace.takeIf { config.remotePlaceId.isNotBlank() })?.let { loadCatalog(current, it) }
                mutable.value.conversationId?.let { loadConversation(current, it) }
                catalogRefresh = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun contextData(p: Profile, mode: String = p.mode, assist: Boolean = p.assist) =
        json("expected_revision" to p.revision, "mode" to mode, "assist_enabled" to assist, "focus" to p.focus)

    fun useCurrentLocation() {
        if (!canChangeLocation()) return
        stopPlayback()
        locationJob?.cancel()
        locationJob = null
        config = config.copy(remotePlaceId = "", testPlaceId = "", simulatedLocation = false)
        configStore.save(config)
        lastFix = null
        manualTarget = false
        mutable.update {
            it.copy(
                config = config,
                selectedPlace = null,
                locationStatus = "正在获取当前设备位置…",
                catalog = emptyList(),
                channels = emptyList(),
                catalogPlace = null,
            )
        }
        // Restart the foreground loop when returning from the legacy simulated mode.
        polling?.cancel()
        polling = null
        activate()
        refreshLocation(applyToDraft = true)
    }

    private fun canChangeLocation(): Boolean {
        val s = mutable.value
        if (ExchangeRuntime.state.value.owned || s.recording || s.transcribing || s.busy || s.draft != null || s.linx.busy) {
            notice("交流中请用语音切换地点；已有录音保持原地点。")
            return false
        }
        return true
    }

    fun useRemotePlace(place: PlaceChoice) {
        if (!canChangeLocation() || place !in mutable.value.placeShortcuts) return
        stopPlayback()
        locationJob?.cancel()
        locationJob = null
        lastFix = null
        manualTarget = true
        config = config.copy(remotePlaceId = place.id, testPlaceId = "", simulatedLocation = false)
        configStore.save(config)
        mutable.update { it.copy(config = config, selectedPlace = place, locating = false,
            locationStatus = "远程地点 · 非手机实到位置", catalog = emptyList(), channels = emptyList(),
            catalogPlace = null, message = "已选择${place.label}，语音会话使用远程地点，不代表人在现场。") }
        lastHeartbeat = 0L
        polling?.cancel()
        polling = null
        activate()
    }

    fun refreshLocation(applyToDraft: Boolean = false) {
        if (
            config.remotePlaceId.isNotBlank() ||
                locationJob?.isActive == true ||
                !foreground
        )
            return
        val current = api
        locationJob =
            viewModelScope.launch {
                mutable.update { it.copy(locating = true, locationStatus = "正在寻找你附近的地点…") }
                try {
                    val fix = phoneLocation.current()
                    if (current == null) {
                        lastFix = fix
                        val source =
                            if (fix.text("source") == "android_mock") "系统测试定位（非真实 GPS）"
                            else "已取得手机位置"
                        mutable.update {
                            it.copy(
                                locationStatus =
                                    "$source · 精度约${fix.optDouble("accuracy_m").toInt()}米；连接服务后解析地点"
                            )
                        }
                        return@launch
                    }
                    var result: org.json.JSONObject? = null
                    repeat(2) {
                        if (result == null) {
                            val profile = Me.parse(current.me()).profile
                            try {
                                result = current.location(profile.revision, fix)
                            } catch (e: ApiFailure) {
                                if (e.code != "REVISION_CONFLICT" || it == 1) throw e
                            }
                        }
                    }
                    val value = result ?: error("定位尚未同步，请重试")
                    val places = value.objects("places").map(PlaceChoice::parse)
                    lastFix = fix
                    mutable.update {
                        it.copy(
                            profile = Profile.parse(value.getJSONObject("profile")),
                            draft = store.draft,
                            nearbyPlaces = places,
                            selectedPlace =
                                if (manualTarget) it.selectedPlace
                                else if (fix.optDouble("accuracy_m") <= 100) places.firstOrNull()
                                else null,
                            locationStatus =
                                when {
                                    fix.text("source") == "android_mock" -> "测试定位 · 不证明真实在场"
                                    fix.optDouble("accuracy_m") > 100 -> "位置较粗略 · 请手选询问地点"
                                    else -> "定位已更新 · 用于附近互助"
                                },
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    mutable.update { it.copy(locationStatus = "定位超时，点地点重试或手选。") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(locationStatus = errorText(e) ?: "定位失败，请重试或手选地点。") }
                } finally {
                    mutable.update { it.copy(locating = false) }
                }
            }
    }

    suspend fun searchPlaces(query: String): List<PlaceChoice> =
        (api ?: error("请先连接声呐")).places(query).objects("places").map(PlaceChoice::parse)

    fun selectPlace(place: PlaceChoice) {
        if (mutable.value.draft?.frozen == true) return
        manualTarget = true
        val draft = store.draft
        val updated =
            if (draft != null && draft.replyTo == null && !draft.frozen)
                draft.copy(targetPlaceId = place.id, targetLabel = place.label)
            else draft
        if (updated != draft) store.draft = updated
        mutable.update { it.copy(selectedPlace = place, draft = updated) }
    }

    private fun freshFix(): org.json.JSONObject? =
        lastFix?.takeIf {
            val age =
                java.time.Duration.between(
                        java.time.Instant.parse(it.text("sampled_at")),
                        java.time.Instant.now(),
                    )
                    .seconds
            age in 0..55
        }

    fun toggleQuiet() {
        if (ExchangeRuntime.state.value.owned) {
            ExchangeService.command(getApplication(), if (ExchangeRuntime.state.value.quiet) ExchangeService.RESUME else ExchangeService.QUIET)
            return
        }
        val desired = if (mutable.value.quiet) "listen" else "quiet"
        pendingMode = desired
        store.pendingMode = desired
        if (desired == "quiet") {
            stopLinxVoice()
            stopPlayback()
            mutable.update { it.copy(quiet = true, message = "已在本机静音，正在同步") }
        } else mutable.update { it.copy(message = "正在恢复收听，等待服务确认") }
        polling?.cancel()
        polling = null
        activate()
    }

    fun setFocus(value: String) = action {
        val focus = value.trim().take(1000)
        check(focus.isNotBlank()) { "说说你关心什么，再保存。" }
        stopPlayback()
        val p = mutable.value.profile ?: error("请先连接")
        val next =
            Profile.parse(
                api!!.context(contextData(p).put("focus", focus)).getJSONObject("profile")
            )
        mutable.update { it.copy(profile = next, message = "已更新关注，之后按你的关注筛选。") }
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
        if (ExchangeRuntime.state.value.owned) { notice("请先结束现场交流，再使用手动录音"); return }
        if (
            mutable.value.busy ||
                mutable.value.linx.busy ||
                mutable.value.recording ||
                mutable.value.transcribing ||
                mutable.value.draft != null
        )
            return
        if (!mutable.value.connected) {
            notice("请先连接服务")
            return
        }
        if (reply == null && composition == null && mutable.value.question?.status == "waiting") {
            notice("请先结束当前提问")
            return
        }
        if (reply == null && mutable.value.selectedPlace == null) {
            notice("先获取附近地点，或点地点名称手选。")
            refreshLocation()
            return
        }
        if (
            reply == null &&
                !config.mock &&
                !config.simulatedLocation &&
                !manualTarget &&
                freshFix() == null
        ) {
            notice("位置已过期，正在更新；更新后再说一句。")
            refreshLocation()
            return
        }
        val extra =
            if (reply?.type == "audio")
                json(
                        "kind" to "reply",
                        "reply_to" to reply.payload.getString("post_id"),
                        "root_question_id" to reply.questionId,
                        "recipient_id" to reply.payload.getString("author_id"),
                        "visibility" to "public",
                    )
                    .apply {
                        reply.payload
                            .text("reply_channel_id")
                            .takeIf { it.isNotBlank() }
                            ?.let { put("channel_id", it) }
                    }
                    .toString()
            else if (reply?.type == "invitation")
                json("kind" to "answer")
                    .apply {
                        reply.payload
                            .text("reply_channel_id")
                            .takeIf { it.isNotBlank() }
                            ?.let { put("channel_id", it) }
                    }
                    .toString()
            else if (reply == null) composition else null
        composition = null
        val target = mutable.value.selectedPlace
        val recordedFix = freshFix()?.toString()
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
                    if (reply?.type == "invitation") {
                        val latest =
                            Delivery.parse(capturedApi.delivery(reply.id).getJSONObject("delivery"))
                        check(
                            latest.state in setOf("pending", "prompted") &&
                                clock.remaining(latest.expiresAt) > 0
                        ) {
                            "邀请已结束"
                        }
                    }
                    if (reply?.type == "audio") {
                        val thread = capturedApi.conversation(reply.questionId)
                        check(
                            thread.optBoolean("can_reply") &&
                                thread.objects("items").any {
                                    it.text("post_id") == reply.payload.text("post_id")
                                }
                        ) {
                            "对话已结束或上下文不可访问"
                        }
                    }
                    val draft =
                        LocalDraft(
                            UUID.randomUUID().toString(),
                            file.absolutePath,
                            replyTo = reply?.questionId,
                            invitationId = reply?.id,
                            targetPlaceId = target?.id ?: "",
                            targetLabel =
                                reply?.payload?.text("place_name") ?: target?.label.orEmpty(),
                            recordedLocation = recordedFix,
                            voiceMetadata =
                                extra?.let {
                                    org.json
                                        .JSONObject(it)
                                        .put("recorded_at", clock.now().toString())
                                        .toString()
                                },
                            replyQuote = reply?.payload?.text("transcript"),
                            replyRecipient = reply?.payload?.text("author_label"),
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
                        mutable.update { it.copy(draft = next, message = "录音已保存，请补充刚才说的话。") }
                    } else {
                        mutable.update { it.copy(transcribing = true, message = "正在把你的话转成文字…") }
                        check(audio.gate.enter(AudioMode.TRANSCRIBING))
                        val text =
                            try {
                                LinxSession(allowLocal = BuildConfig.DEBUG).transcribe(file, config)
                            } finally {
                                audio.gate.leave()
                            }
                        if (capturedStore !== store) return@launch
                        val next = draft.copy(transcript = text, source = "Linx 识别候选，请核对原声")
                        capturedStore.draft = next
                        mutable.update { it.copy(draft = next, message = "确认后，才会把这句话发给别人。") }
                    }
                } catch (e: TimeoutCancellationException) {
                    notice("灵矽识别超时；录音已私有保存，请重试或重录")
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
        store.draft?.let { OpusFiles.forgetUpload(File(it.file)); File(it.file).delete() }
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
        if (draft.metadata().text("kind") == "question") {
            store.questionId = result.getString("question_id")
            mutable.update { it.copy(question = null) }
        }
        OpusFiles.forgetUpload(File(draft.file))
        File(draft.file).delete()
        store.draft = null
        mutable.update {
            it.copy(
                draft = null,
                message =
                    if (draft.metadata().text("kind") == "question") "已发出，正在替你等回复。" else "原声已公开发布。",
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
        if (audio.gate.mode != AudioMode.IDLE || mutable.value.busy || mutable.value.linx.busy)
            return
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
                mutable.value.recording ||
                mutable.value.transcribing ||
                mutable.value.busy ||
                mutable.value.linx.busy ||
                audio.gate.mode != AudioMode.IDLE ||
                !foreground
        )
            return
        if (!manual && (store.attempted(d.key) || store.deferred(d.key))) return
        if (manual) store.defer(d.key, false)
        val captured = api ?: return
        val capturedStore = store
        val startedGeneration = generation
        playerJob =
            viewModelScope.launch {
                var file: File? = null
                var actual = d
                mutable.update { it.copy(playing = d.id) }
                try {
                    val before =
                        if (
                            manual &&
                                d.payload.text("kind") in
                                    setOf("scene", "knowledge", "announcement", "answer", "reply")
                        )
                            Delivery.parse(
                                captured
                                    .queueVoice(d.payload.getString("post_id"))
                                    .getJSONObject("delivery")
                            )
                        else Delivery.parse(captured.delivery(d.id).getJSONObject("delivery"))
                    check(
                        clock.valid(before) &&
                            (manual || before.requestId == d.requestId) &&
                            (manual || before.revision == d.revision)
                    ) {
                        "播放请求已改变，请等候刷新"
                    }
                    actual = before
                    if (manual) captured.ack(before.id, before.requestId, "seen")
                    mutable.update {
                        it.copy(
                            deliveries =
                                it.deliveries.map { item ->
                                    if (item.id == before.id) before else item
                                }
                        )
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
                            latest.requestId == before.requestId &&
                            latest.revision == before.revision
                    ) {
                        "声音已失效，本次不播放"
                    }
                    val intro = publicationIntro(latest.payload, clock.now())
                    if (intro.isNotBlank()) {
                        val introFile = File(getApplication<Application>().cacheDir, "publication-${UUID.randomUUID()}.wav")
                        try {
                            AgentSpeech(getApplication()).synthesize(intro, introFile)
                            audio.play(introFile, AudioMode.PROMPT, ownSpeaker = true, mayStart = {
                                foreground && generation == startedGeneration && !mutable.value.quiet && clock.valid(latest)
                            })
                        } finally { introFile.delete() }
                    }
                    val ready = Delivery.parse(captured.delivery(before.id).getJSONObject("delivery"))
                    if (!foreground || generation != startedGeneration || mutable.value.quiet ||
                        !clock.valid(ready) || ready.key != before.key || ready.revision != before.revision) return@launch
                    capturedStore.markAttempt(before.key)
                    audio.play(file, mayStart = { foreground && generation == startedGeneration && !mutable.value.quiet && clock.valid(ready) })
                    capturedStore.outcome(d.questionId, "played")
                    capturedStore.queueAck(before, "played")
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
                    capturedStore.markAttempt(actual.key)
                    capturedStore.queueAck(actual, "failed")
                    notice(errorText(e))
                } finally {
                    file?.delete()
                    mutable.update { it.copy(playing = null) }
                }
            }
    }

    fun activateLinx(checkBinding: Boolean = false) {
        if (mutable.value.linx.busy || audio.gate.mode != AudioMode.IDLE) return
        val captured = config
        val epoch = generation
        linxBindingJob =
            viewModelScope.launch {
                mutable.update {
                    it.copy(linx = it.linx.copy(phase = "activating", status = "正在检查手机激活状态…"))
                }
                try {
                    val gateway = LinxGateway(captured.linxOtaUrl, BuildConfig.DEBUG)
                    if (checkBinding && !gateway.activated(captured)) {
                        mutable.update {
                            it.copy(
                                linx =
                                    it.linx.copy(phase = "pending", status = "还未绑定，请在灵矽控制台输入本机激活码")
                            )
                        }
                        return@launch
                    }
                    val hash =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            File(getApplication<Application>().applicationInfo.sourceDir)
                                .inputStream()
                                .use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        digest.update(buffer, 0, size)
                                    }
                                }
                            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        }
                    when (val result = gateway.report(captured, hash)) {
                        is LinxRegistration.Pending ->
                            if (generation == epoch)
                                mutable.update {
                                    it.copy(
                                        linx =
                                            it.linx.copy(
                                                phase = "pending",
                                                status = "在灵矽控制台绑定本机，然后检查连接",
                                                activationCode = result.code,
                                                consoleUrl = result.consoleUrl,
                                            )
                                    )
                                }
                        is LinxRegistration.Bound ->
                            if (generation == epoch) {
                                config = config.copy(linxUrl = result.url, linxToken = result.token)
                                configStore.save(config)
                                mutable.update {
                                    it.copy(
                                        config = config,
                                        linx =
                                            it.linx.copy(
                                                phase = "bound",
                                                activationCode = null,
                                                status = "手机已绑定，连接凭证已加密保存；可以试说一句",
                                            ),
                                    )
                                }
                            }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(linx = it.linx.copy(phase = "failed", status = linxError(e)))
                    }
                } finally {
                    if (generation == epoch)
                        mutable.update {
                            if (it.linx.phase == "activating")
                                it.copy(
                                    linx = it.linx.copy(phase = "unbound", status = "激活检查已停止，可重新检查")
                                )
                            else it
                        }
                }
            }
    }

    fun linxSound(enabled: Boolean) {
        if (!enabled && mutable.value.linx.phase == "playing") stopLinxVoice()
        mutable.update { it.copy(linx = it.linx.copy(soundEnabled = enabled)) }
    }

    fun recordLinx() {
        if (ExchangeRuntime.state.value.owned) { notice("请先结束现场交流，再测试私人助手"); return }
        if (
            !foreground ||
                mutable.value.linx.busy ||
                mutable.value.busy ||
                mutable.value.recording ||
                mutable.value.transcribing ||
                audio.gate.mode != AudioMode.IDLE
        )
            return
        if (config.linxToken.isBlank()) {
            notice("请先在我的 → 灵矽语音激活这台手机")
            return
        }
        val captured = config
        stopPlayback()
        val epoch = generation
        linxVoiceJob =
            viewModelScope.launch {
                val file =
                    File(
                        getApplication<Application>().cacheDir,
                        "linx-mic-${UUID.randomUUID()}.wav",
                    )
                var response: File? = null
                mutable.update {
                    it.copy(
                        linx =
                            it.linx.copy(
                                phase = "recording",
                                status = "正在录音，最长8秒；说完点结束",
                                transcript = "",
                                reply = "",
                            )
                    )
                }
                try {
                    audio.record(file)
                    if (!foreground || generation != epoch) return@launch
                    mutable.update {
                        it.copy(linx = it.linx.copy(phase = "recognizing", status = "正在连接灵矽…"))
                    }
                    check(audio.gate.enter(AudioMode.TRANSCRIBING))
                    val result =
                        try {
                            LinxSession(allowLocal = BuildConfig.DEBUG)
                                .exchange(
                                    file,
                                    captured,
                                    status = { text ->
                                        mutable.update {
                                            it.copy(linx = it.linx.copy(status = text))
                                        }
                                    },
                                    recognized = { text ->
                                        mutable.update {
                                            it.copy(linx = it.linx.copy(transcript = text))
                                        }
                                    },
                                )
                        } finally {
                            audio.gate.leave()
                        }
                    if (!foreground || generation != epoch) return@launch
                    mutable.update {
                        it.copy(
                            linx =
                                it.linx.copy(
                                    transcript = result.transcript,
                                    reply = result.assistantText,
                                    format =
                                        "${result.params.format.uppercase()} · ${result.params.rate} Hz",
                                )
                        )
                    }
                    if (
                        mutable.value.linx.soundEnabled &&
                            pendingMode != "quiet" &&
                            mutable.value.profile?.mode != "quiet"
                    ) {
                        val pcm = result.audio ?: error("灵矽没有返回语音")
                        response =
                            File(
                                    getApplication<Application>().cacheDir,
                                    "linx-reply-${UUID.randomUUID()}.wav",
                                )
                                .apply {
                                    writeBytes(Wav.header(pcm.bytes.size, pcm.rate) + pcm.bytes)
                                }
                        mutable.update {
                            it.copy(
                                linx = it.linx.copy(phase = "playing", status = "正在播放灵矽 AI 回复…")
                            )
                        }
                        audio.play(response, AudioMode.PROMPT)
                        mutable.update {
                            it.copy(
                                linx =
                                    it.linx.copy(phase = "complete", status = "语音往返完成：识别与回复播放均已完成")
                            )
                        }
                    } else
                        mutable.update {
                            it.copy(
                                linx =
                                    it.linx.copy(phase = "complete", status = "已收到灵矽回复；当前不出声，可查看文字")
                            )
                        }
                } catch (e: TimeoutCancellationException) {
                    mutable.update {
                        it.copy(
                            linx = it.linx.copy(phase = "failed", status = "灵矽响应超时，请重试；本次没有公开发布")
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is LinxAuthFailure) {
                        config = config.copy(linxToken = "")
                        configStore.save(config)
                    }
                    mutable.update {
                        it.copy(
                            config = config,
                            linx = it.linx.copy(phase = "failed", status = linxError(e)),
                        )
                    }
                } finally {
                    file.delete()
                    response?.delete()
                    if (generation == epoch)
                        mutable.update {
                            if (it.linx.busy)
                                it.copy(
                                    linx =
                                        it.linx.copy(
                                            phase =
                                                if (config.linxToken.isNotBlank()) "bound"
                                                else "unbound",
                                            status = "本次语音已停止，没有公开发布",
                                        )
                                )
                            else it
                        }
                }
            }
    }

    fun finishLinxRecording() {
        audio.stopRecording()
    }

    fun stopLinxVoice() {
        if (linxVoiceJob?.isActive == true) {
            linxVoiceJob?.cancel()
            audio.stopRecording()
        }
        mutable.update {
            if (it.linx.phase in setOf("recording", "recognizing", "playing"))
                it.copy(
                    linx =
                        it.linx.copy(
                            phase = if (config.linxToken.isNotBlank()) "bound" else "unbound",
                            status = "本次语音已停止，没有公开发布",
                        )
                )
            else it
        }
    }

    private fun linxError(e: Exception) =
        when (e) {
            is LinxAuthFailure -> e.message.orEmpty()
            is IOException -> "连接灵矽失败，请检查网络或重新检查绑定"
            is org.json.JSONException -> "灵矽协议响应无法识别，请重试"
            else -> e.message?.take(180) ?: "灵矽操作未完成，请重试"
        }

    fun saveListening(values: org.json.JSONObject) = action {
        val p = mutable.value.profile ?: error("请先连接")
        val next = org.json.JSONObject(p.listening.toString())
        values.keys().forEach { next.put(it, values.get(it)) }
        next.put("expected_revision", p.revision)
        val profile = Profile.parse(api!!.listening(next).getJSONObject("profile"))
        mutable.update { it.copy(profile = profile, message = "已更新收听设置") }
    }

    fun browseFromDraft() {
        discardDraft()
        browseVoices()
    }

    fun browseVoices() = action {
        val place = mutable.value.selectedPlace ?: error("请先选一个地点")
        loadCatalog(api!!, place)
    }

    private suspend fun loadCatalog(current: SonarApi, place: PlaceChoice) {
        val result = current.voices(place.id)
        mutable.update {
            it.copy(
                catalog = result.objects("items"),
                channels = result.objects("channels"),
                catalogPlace = place,
                announcementStatus = result.text("announcement_status", "暂无有效运营公告"),
            )
        }
    }

    fun hearVoice(postId: String) = action {
        check(!mutable.value.quiet) { "先恢复收听，再听原声。" }
        val d = Delivery.parse(api!!.queueVoice(postId).getJSONObject("delivery"))
        // The action gate clears when this coroutine completes.
        mutable.update {
            it.copy(deliveries = listOf(d) + it.deliveries.filterNot { item -> item.id == d.id })
        }
        store.defer(d.key, false)
        viewModelScope.launch {
            while (mutable.value.busy) delay(20)
            playDelivery(d)
        }
    }

    fun later(d: Delivery) {
        store.markAttempt(d.key)
        store.defer(d.key, true)
        if (mutable.value.playing == d.id) stopPlayback()
        notice("已留在待听，点听原声可继续")
    }

    fun skip(d: Delivery) = action {
        if (mutable.value.playing == d.id) stopPlayback()
        store.markAttempt(d.key)
        store.queueAck(d, "skipped")
        mutable.update { it.copy(deliveries = it.deliveries.filterNot { item -> item.id == d.id }) }
    }

    fun reduceScene(d: Delivery) {
        val topics = d.payload.optJSONArray("topics")?.strings().orEmpty()
        if (topics.isEmpty()) saveListening(json("scene_enabled" to false))
        else {
            val muted =
                mutable.value.profile?.listening?.optJSONArray("muted_topics")?.strings().orEmpty()
            saveListening(
                json("muted_topics" to org.json.JSONArray((muted + topics).distinct().take(20)))
            )
        }
        later(d)
    }

    fun muteChannel(channelId: String) {
        val muted =
            mutable.value.profile?.listening?.optJSONArray("muted_channels")?.strings().orEmpty()
        saveListening(
            json("muted_channels" to org.json.JSONArray((muted + channelId).distinct().take(20)))
        )
    }

    fun withdrawVoice(postId: String) = action {
        api!!.withdrawVoice(postId)
        mutable.value.catalogPlace?.let { loadCatalog(api!!, it) }
    }

    fun openConversation(qid: String) = action { loadConversation(api!!, qid) }

    private suspend fun loadConversation(current: SonarApi, qid: String) {
        val result = current.conversation(qid)
        mutable.update {
            it.copy(
                conversationId = qid,
                conversation = result.objects("items"),
                canReply = result.optBoolean("can_reply"),
            )
        }
    }

    fun closeConversation() {
        mutable.update { it.copy(conversationId = null, conversation = emptyList()) }
    }

    fun replyTarget(item: org.json.JSONObject): Delivery =
        Delivery(
            "",
            "audio",
            mutable.value.conversationId ?: error("请先打开对话"),
            "",
            0,
            "pending",
            "",
            false,
            item,
        )

    fun prepareChannelQuestion(channelId: String) {
        check(mutable.value.question?.status != "waiting") { "请先结束当前提问" }
        composition = json("kind" to "question", "channel_id" to channelId).toString()
    }

    fun clearComposition() {
        composition = null
    }

    fun prepareVoice(fields: org.json.JSONObject, ttlMinutes: Int) {
        check(ttlMinutes in 1..60) { "即时声音有效期为 1～60 分钟" }
        if (fields.text("kind") == "announcement" || fields.text("purpose") == "observation") {
            fields.put("valid_from", clock.now().toString())
            fields.put("valid_until", clock.now().plusSeconds(ttlMinutes * 60L).toString())
        }
        composition = fields.toString()
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
        mutable.update { it.copy(playing = null, taskNotice = if (it.playing?.startsWith("history:") == true) "原声已停止，未更改任务状态。" else it.taskNotice) }
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
            is LinxAuthFailure -> e.message.orEmpty()
            is ApiFailure -> e.message
            is IOException -> "连接中断，正在重试；未确认的录音不会公开"
            else -> e.message ?: "操作未完成，请重试"
        }

    override fun onCleared() {
        audio.close()
        super.onCleared()
    }
}
