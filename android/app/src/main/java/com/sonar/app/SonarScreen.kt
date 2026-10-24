package com.sonar.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Sea = Color(0xFF123E51)
private val Teal = Color(0xFF007F86)
private val Paper = Color(0xFFF0F5F5)
private val Ink = Color(0xFF163440)
private val Amber = Color(0xFF966019)
private val Mist = Color(0xFFD8E8E9)

@Composable
fun SonarScreen(
    vm: SonarViewModel,
    onLocate: () -> Unit,
    onLinxRecord: () -> Unit,
    onExchangeStart: (Boolean, Boolean) -> Unit = { publish, play -> vm.startExchange(publish, play) },
    onRecord: (Delivery?) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val exchange by ExchangeRuntime.state.collectAsStateWithLifecycle()
    var mine by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var placePicker by remember { mutableStateOf(false) }
    var exchangeSetup by remember { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val mineScroll = rememberScrollState()
    BackHandler(enabled = mine && !connection) { mine = false }
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Teal,
                onPrimary = Color.White,
                secondary = Sea,
                background = Paper,
                surface = Color.White,
                onSurface = Ink,
            )
    ) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(if (mine) mineScroll else homeScroll)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "声呐",
                        Modifier.weight(1f),
                        color = Sea,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = { mine = !mine },
                        enabled = !exchange.owned && !s.busy && !s.recording && !s.transcribing,
                        modifier = Modifier.testTag("settings"),
                    ) {
                        Text(if (mine) "返回" else "我的")
                    }
                }
                if (mine) {
                    LinxPanel(s, vm, onLinxRecord)
                    Preferences(s, vm, onLocate) { connection = true }
                } else if (exchange.owned) {
                    ExchangeActivePanel(exchange, vm)
                } else {
                    OutlinedButton(
                        onClick = { exchangeSetup = true },
                        enabled = s.connected && !s.busy && !s.recording && !s.transcribing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("exchange-setup"),
                    ) { Text("开始现场交流") }
                    if (exchange.phase == "unavailable") Info(exchange.message, true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TextButton(
                                onClick = { placePicker = true },
                                enabled =
                                    !s.recording &&
                                        !s.transcribing &&
                                        !s.busy &&
                                        s.draft?.frozen != true,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("choose-place"),
                            ) {
                                Text(
                                    s.draft?.targetLabel
                                        ?: s.question
                                            ?.takeIf { it.status == "waiting" }
                                            ?.targetLabel
                                        ?: s.selectedPlace?.label
                                        ?: "定位附近 / 手选地点",
                                    color = Sea,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Text(
                                s.locationStatus,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Sea,
                            )
                        }
                        TextButton(
                            onClick = vm::toggleQuiet,
                            enabled = s.profile != null,
                            modifier = Modifier.testTag("quiet"),
                        ) {
                            Text(if (s.quiet) "恢复收听" else "安静一下")
                        }
                    }
                    if (s.config.mock) Info("模拟体验 · 回复和音频均为测试内容", true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onLocate,
                            enabled =
                                !s.locating && !s.recording && !s.transcribing && s.draft == null,
                            modifier = Modifier.weight(1f).testTag("real-location"),
                        ) {
                            Text(if (s.locating) "正在定位…" else "手机真实位置")
                        }
                        OutlinedButton(
                            onClick = { placePicker = true },
                            enabled = !s.recording && !s.transcribing && s.draft == null,
                            modifier = Modifier.weight(1f).testTag("test-location"),
                        ) {
                            Text("模拟位置")
                        }
                    }
                    if (s.testClips.isNotEmpty() && !s.recording && s.draft == null)
                        TestVoices(s, vm)
                    when {
                        s.recording -> RecordingCard(s, vm, onRecord)
                        s.draft != null -> DraftCard(s, vm)
                        s.question?.status == "waiting" -> QuestionCard(s, vm)
                        s.question != null -> {
                            QuestionCard(s, vm)
                            OutlinedButton(
                                onClick = { onRecord(null) },
                                enabled = s.connected && !s.busy && !s.transcribing,
                                modifier =
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("record"),
                            ) {
                                Text("再问一句")
                            }
                        }
                        s.deliveries.any { it.type == "invitation" } -> Unit
                        else -> RecordingCard(s, vm, onRecord)
                    }
                    s.deliveries
                        .filter { it.type == "invitation" && !s.recording && s.draft == null }
                        .forEach { d ->
                            VoiceCard {
                                Text("有人想问你", color = Teal, fontSize = 14.sp)
                                Text(
                                    "询问地点：${d.payload.text("place_name")}",
                                    color = Sea,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    d.payload.text("transcript"),
                                    fontSize = 21.sp,
                                    lineHeight = 30.sp,
                                )
                                Text(
                                    "回复给 ${d.payload.text("author_label", "提问者")} · 确认后公开",
                                    color = Sea,
                                    fontSize = 14.sp,
                                )
                                Button(
                                    onClick = { onRecord(d) },
                                    enabled =
                                        !s.busy &&
                                            !s.recording &&
                                            !s.transcribing &&
                                            s.draft == null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) {
                                    Text("说一句回应")
                                }
                                d.payload
                                    .text("operator_reply_channel")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { cid ->
                                        OutlinedButton(
                                            onClick = {
                                                onRecord(
                                                    d.copy(
                                                        payload =
                                                            org.json
                                                                .JSONObject(d.payload.toString())
                                                                .put("reply_channel_id", cid)
                                                    )
                                                )
                                            },
                                            enabled =
                                                !s.busy &&
                                                    !s.recording &&
                                                    !s.transcribing &&
                                                    s.draft == null,
                                        ) {
                                            Text("以授权运营身份回应")
                                        }
                                    }
                                TextButton(onClick = { vm.decline(d) }, enabled = !s.busy) {
                                    Text("这次不了")
                                }
                            }
                        }
                    s.deliveries
                        .filter { it.type == "audio" && it.questionId != s.question?.id }
                        .forEach { d ->
                            VoiceCard {
                                Text(
                                    when (d.payload.text("kind")) {
                                        "announcement" -> "地点的有效运营公告"
                                        "scene" -> "此地的一段声音"
                                        else -> "还有一条回复等你听"
                                    },
                                    color = Sea,
                                    fontSize = 18.sp,
                                )
                                Text(d.payload.text("transcript"))
                                DeliveryControls(s, vm, d)
                            }
                        }
                    if (!s.recording && s.draft == null) PlaceVoices(s, vm, onRecord)
                    if (s.quiet) Info("已安静收听。回复会替你留着，恢复收听后再播放。")
                    if (s.playing != null)
                        TextButton(
                            onClick = { vm.stopPlayback(true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("停止播放")
                        }
                    Text(
                        s.message,
                        color = if (s.connected) Sea else Amber,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.testTag("message"),
                    )
                    Text(
                        "人来回答，声呐帮你找到愿意回应的人。",
                        color = Sea.copy(alpha = .6f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (s.conversationId != null) ConversationDialog(s, vm, onRecord)
        if (placePicker)
            PlacePicker(
                s,
                vm,
                onLocate = {
                    onLocate()
                    placePicker = false
                },
                onDismiss = { placePicker = false },
            ) {
                vm.selectPlace(it)
                placePicker = false
            }
        if (connection)
            SettingsDialog(s.config, onDismiss = { connection = false }) {
                vm.configure(it)
                connection = false
            }
        if (exchangeSetup) {
            var publish by remember { mutableStateOf(false) }
            var play by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { exchangeSetup = false },
                title = { Text("停车时开启本次交流") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("交流地点：${s.selectedPlace?.label ?: "请先选择地点"}")
                        Text("开启后正常说现场情况，无需逐条确认；相关原声在空隙自动播放。私人助手和闲聊不应公开。")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(publish, { publish = it }, modifier = Modifier.testTag("exchange-auto-publish"))
                            Text("允许本次自动公开现场原声")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(play, { play = it }, modifier = Modifier.testTag("exchange-auto-play"))
                            Text("允许自动收听相关回复")
                        }
                        Text("使用真实定位与灵矽识别；退出模拟模式。可随时安静或结束。", fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { exchangeSetup = false; onExchangeStart(publish, play) },
                        enabled = (publish || play) && s.selectedPlace != null,
                        modifier = Modifier.testTag("exchange-start")) { Text("开启本次交流") }
                },
                dismissButton = { TextButton(onClick = { exchangeSetup = false }) { Text("暂不开启") } },
            )
        }
    }
}

@Composable
private fun VoiceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun RecordingCard(s: ScreenState, vm: SonarViewModel, onRecord: (Delivery?) -> Unit) {
    VoiceCard {
        Text(
            if (s.recording) "在听，你说" else "附近怎么样？",
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
            color = Sea,
        )
        Text(
            if (s.recording) "说完再点一下，最长 8 秒。" else "问一句，让现场的人告诉你。",
            fontSize = 16.sp,
            color = Sea.copy(alpha = .75f),
        )
        Box(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(208.dp)) {
                drawCircle(
                    Teal.copy(alpha = .1f),
                    size.minDimension * .49f,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    Teal.copy(alpha = .18f),
                    size.minDimension * .39f,
                    style = Stroke(1.dp.toPx()),
                )
            }
            Button(
                onClick = { if (s.recording) vm.finishRecording() else onRecord(null) },
                enabled =
                    s.recording || (s.connected && !s.busy && !s.transcribing && s.draft == null),
                shape = CircleShape,
                contentPadding = PaddingValues(12.dp),
                colors =
                    ButtonDefaults.buttonColors(containerColor = if (s.recording) Amber else Teal),
                modifier = Modifier.size(132.dp).testTag("record"),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Canvas(
                        Modifier.size(28.dp).semantics {
                            contentDescription = if (s.recording) "停止录音" else "麦克风"
                        }
                    ) {
                        if (s.recording)
                            drawRoundRect(
                                Color.White,
                                cornerRadius =
                                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                            )
                        else {
                            drawRoundRect(
                                Color.White,
                                topLeft =
                                    androidx.compose.ui.geometry.Offset(size.width * .36f, 0f),
                                size =
                                    androidx.compose.ui.geometry.Size(
                                        size.width * .28f,
                                        size.height * .62f,
                                    ),
                                cornerRadius =
                                    androidx.compose.ui.geometry.CornerRadius(size.width * .14f),
                            )
                            drawArc(
                                Color.White,
                                0f,
                                180f,
                                false,
                                topLeft =
                                    androidx.compose.ui.geometry.Offset(
                                        size.width * .15f,
                                        size.height * .12f,
                                    ),
                                size =
                                    androidx.compose.ui.geometry.Size(
                                        size.width * .7f,
                                        size.height * .7f,
                                    ),
                                style = Stroke(2.dp.toPx()),
                            )
                            drawLine(
                                Color.White,
                                androidx.compose.ui.geometry.Offset(center.x, size.height * .82f),
                                androidx.compose.ui.geometry.Offset(center.x, size.height),
                                2.dp.toPx(),
                            )
                        }
                    }
                    Text(if (s.recording) "结束录音" else "点一下说话", fontSize = 16.sp)
                }
            }
        }
        Text(
            "录完先确认，再发给别人。",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = Sea.copy(alpha = .65f),
        )
        if (!s.connected) Info("还没连上声呐。请在「我的」查看连接。", true)
    }
}

@Composable
private fun Info(text: String, warning: Boolean = false) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = if (warning) Amber else Sea,
        modifier =
            Modifier.fillMaxWidth()
                .background(if (warning) Color(0xFFF8EEDD) else Mist, RoundedCornerShape(12.dp))
                .padding(14.dp),
    )
}

@Composable
private fun DraftCard(s: ScreenState, vm: SonarViewModel) {
    val d = s.draft ?: return
    VoiceCard {
        Text(
            if (d.replyTo == null) "把这句话发出去？" else "把回答发给他？",
            fontSize = 25.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = Sea,
        )
        when {
            s.transcribing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在把你的话转成文字…", color = Sea)
            }
            s.config.debugTranscript -> {
                Info("调试转写已开启：请手动补充刚才说的话；本条文字不是语音识别结果。", true)
                OutlinedTextField(
                    value = d.transcript,
                    onValueChange = vm::editTranscript,
                    label = { Text("刚才说了什么？") },
                    enabled = !d.frozen && !s.busy,
                    modifier = Modifier.fillMaxWidth().testTag("transcript"),
                    minLines = 2,
                )
            }
            d.transcript.isNotBlank() -> Text(d.transcript, fontSize = 22.sp, lineHeight = 32.sp)
            else -> Info("没能识别这段录音。可以重录，或在「我的」检查语音连接。", true)
        }
        Text("适用地点：${d.targetLabel}", color = Teal, fontSize = 14.sp)
        d.voiceMetadata?.let { metadata ->
            val fields = org.json.JSONObject(metadata)
            val purpose =
                when (fields.text("purpose")) {
                    "reflection" -> "历史感慨"
                    "experience" -> "体验分享"
                    "observation" -> "即时观察"
                    "explanation" -> "知识解说"
                    "notice" -> "运营公告"
                    else -> if (fields.text("kind") == "question") "频道提问" else "定向回复"
                }
            Text("用途：$purpose · 确认后公开", color = Sea, fontSize = 14.sp)
            if (fields.text("channel_id").isNotBlank())
                Text(
                    when (fields.text("kind")) {
                        "question" -> "向此频道公开提问 · 个人身份"
                        "answer",
                        "reply" -> "以授权运营身份公开回应"
                        else -> "以授权运营身份公开发布"
                    },
                    color = Sea,
                )
            if (fields.text("kind") in setOf("reply", "answer"))
                Text(
                    "回复给：${d.replyRecipient ?: fields.text("recipient_id", "提问者")}\n回复原句：${d.replyQuote.orEmpty()}",
                    color = Sea,
                )
            fields
                .text("valid_until")
                .takeIf { it.isNotBlank() }
                ?.let { Text("有效至 $it", color = Sea) }
        }
        Text("确认后，这段原声会公开给相关的人。", color = Sea, fontSize = 14.sp)
        if (
            d.replyTo == null &&
                d.voiceMetadata == null &&
                d.transcript.trim().trimEnd('。', '？', '?') in setOf("听听这里的故事", "听听这里的公告")
        ) {
            TextButton(onClick = vm::browseFromDraft, enabled = !s.busy && !s.transcribing) {
                Text("查看此地声音与公告")
            }
        }
        Button(
            onClick = vm::publish,
            enabled =
                d.transcript.isNotBlank() &&
                    !s.busy &&
                    !s.recording &&
                    !s.transcribing &&
                    s.playing == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("publish"),
        ) {
            Text(if (s.busy) "正在发送…" else if (d.frozen) "重试发送" else "确认发出", fontSize = 17.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = vm::preview,
                enabled =
                    !s.quiet && !s.busy && !s.recording && !s.transcribing && s.playing == null,
            ) {
                Text("听一下录音")
            }
            TextButton(
                onClick = vm::discardDraft,
                enabled = !s.busy && !s.recording && !s.transcribing,
            ) {
                Text("重新说")
            }
        }
    }
}

@Composable
private fun QuestionCard(s: ScreenState, vm: SonarViewModel) {
    val q = s.question ?: return
    val d = s.deliveries.firstOrNull { it.type == "audio" && it.questionId == q.id }
    VoiceCard {
        Text(
            when (q.status) {
                "waiting" -> "正在替你等回复"
                "answered" -> "现场有人回应了"
                "expired" -> "暂时无人回应"
                "cancelled" -> "这次提问已结束"
                else -> "正在更新"
            },
            fontSize = 26.sp,
            lineHeight = 35.sp,
            color = Sea,
            fontWeight = FontWeight.SemiBold,
        )
        q.targetLabel?.let { Text("询问地点：$it", color = Teal, fontSize = 14.sp) }
        if (q.status == "waiting") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("有回复就告诉你，不用一直看着屏幕。", fontSize = 16.sp, lineHeight = 26.sp, color = Sea)
            Text(
                "请保持应用打开 · 最多再等 ${s.remainingSeconds ?: 0} 秒",
                fontSize = 12.sp,
                color = Sea.copy(alpha = .7f),
            )
            TextButton(
                onClick = vm::cancelQuestion,
                enabled = !s.busy,
                modifier = Modifier.testTag("cancel-question"),
            ) {
                Text("不等了")
            }
        }
        if (q.answer != null) {
            Text(q.answer.text("transcript"), fontSize = 23.sp, lineHeight = 34.sp, color = Ink)
            Text(
                "${q.answer.text("author_label")} · ${displayTime(q.answer.text("published_at"))}",
                fontSize = 12.sp,
                color = Sea,
            )
            Text(
                when {
                    s.audioOutcome == "played" -> "原声已播放"
                    s.audioOutcome == "skipped" -> "已停止播放"
                    s.quiet -> "原声已留好，恢复收听就能听见"
                    s.playing == d?.id && d != null -> "正在播放原声…"
                    else -> "回答已收到"
                },
                fontSize = 14.sp,
                color = Teal,
                modifier = Modifier.testTag("audio-outcome"),
            )
            if (d != null && s.audioOutcome !in setOf("played", "skipped"))
                Button(
                    onClick = { vm.playDelivery(d) },
                    enabled = !s.quiet && !s.busy && s.playing == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("play-answer"),
                ) {
                    Text("听现场原声")
                }
        }
        if (q.status == "answered")
            TextButton(onClick = { vm.openConversation(q.id) }, enabled = !s.busy) {
                Text("查看上下文并继续回复")
            }
        if (d != null) DeliveryControls(s, vm, d)
        if (q.status == "expired") Text("这次没有等到回答，可以稍后再问。", color = Sea, lineHeight = 24.sp)
    }
}

@Composable
private fun Preferences(
    s: ScreenState,
    vm: SonarViewModel,
    onLocate: () -> Unit,
    onConnection: () -> Unit,
) {
    Text("我的声呐", color = Sea, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    ListeningPreferences(s, vm)
    VoiceCard {
        Text("现场互助", color = Sea, fontSize = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("附近有人提问时，提醒我回应。", Modifier.weight(1f), fontSize = 15.sp, lineHeight = 24.sp)
            Switch(
                checked = s.profile?.assist == true,
                onCheckedChange = { vm.toggleAssist() },
                enabled = s.profile != null && !s.busy,
            )
        }
        Text("是否回答，由你决定。安静时不会打扰。", fontSize = 13.sp, color = Sea.copy(alpha = .7f))
    }
    VoiceCard {
        var focus by
            remember(s.profile?.userId, s.profile?.focus) {
                mutableStateOf(s.profile?.focus.orEmpty())
            }
        Text("我关心什么", color = Sea, fontSize = 20.sp)
        OutlinedTextField(
            value = focus,
            onValueChange = { focus = it.take(1000) },
            placeholder = { Text("例如：附近路况、同目的地停车") },
            enabled = s.profile != null && !s.busy,
            modifier = Modifier.fillMaxWidth().testTag("personal-focus"),
            minLines = 2,
        )
        Text("声呐按这句话筛选值得提醒你的声音。", color = Sea, fontSize = 13.sp)
        OutlinedButton(
            onClick = { vm.setFocus(focus) },
            enabled =
                s.profile != null &&
                    !s.busy &&
                    focus.isNotBlank() &&
                    focus.trim() != s.profile?.focus,
            modifier = Modifier.testTag("save-focus"),
        ) {
            Text("保存关注")
        }
    }
    VoiceCard {
        Text("位置", color = Sea, fontSize = 20.sp)
        Text(s.selectedPlace?.label ?: "还未选择询问地点")
        Text(s.locationStatus, color = Sea, fontSize = 13.sp)
        if (s.config.testPlaceId.isNotBlank()) {
            Text("模拟位置仅供测试浏览，不会获得真实在场资格。", fontSize = 13.sp, color = Sea)
            OutlinedButton(onClick = onLocate, enabled = !s.locating && s.draft == null) {
                Text("切换到手机真实位置")
            }
        } else if (s.config.mock || s.config.simulatedLocation) {
            OutlinedButton(onClick = vm::confirmPresence, enabled = s.connected && !s.busy) {
                Text("确认演示位置")
            }
        } else {
            Text("仅在应用打开时更新；精度不足时不接收附近邀请。", fontSize = 13.sp, color = Sea)
            OutlinedButton(onClick = onLocate, enabled = !s.locating) { Text("更新当前位置") }
        }
    }

    VoiceCard {
        Text(if (s.connected) "声呐已连接" else "声呐尚未连接", color = Sea, fontSize = 20.sp)
        if (s.config.debugTranscript) Text("体验版：录音后需要补充文字。", color = Amber, fontSize = 13.sp)
        Text("连接只需配置一次。", color = Sea, fontSize = 14.sp)
        TextButton(onClick = onConnection, modifier = Modifier.testTag("developer-connection")) {
            Text("开发者连接")
        }
    }
}

@Composable
private fun PlacePicker(
    s: ScreenState,
    vm: SonarViewModel,
    onLocate: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (PlaceChoice) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PlaceChoice>()) }
    var error by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        error = ""
        results = emptyList()
        if (query.trim().length >= 2) {
            searching = true
            try {
                kotlinx.coroutines.delay(400)
                results = vm.searchPlaces(query.trim())
                if (results.isEmpty()) error = "没找到，试试加上城市或道路名称。"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "地点搜索暂不可用，请重试。"
            } finally {
                searching = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置与询问地点") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onLocate,
                    enabled = !s.locating && !s.config.mock && s.draft == null,
                ) {
                    Text(if (s.locating) "正在定位…" else "使用当前位置")
                }
                Text("模拟体验 · 3 个地点 / 36 条音频", fontWeight = FontWeight.Medium)
                Text("不用到现场即可试听；全部为合成测试内容。", fontSize = 12.sp, color = Sea)
                s.testPlaces.forEach { place ->
                    OutlinedButton(
                        onClick = {
                            vm.useTestPlace(place)
                            onDismiss()
                        },
                        enabled = s.draft == null && !s.busy && !s.config.mock,
                        modifier = Modifier.fillMaxWidth().testTag("test-place-${place.id}"),
                    ) {
                        Text(place.label)
                    }
                }
                HorizontalDivider()
                Text("手选询问目标", fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    label = { Text("路口、景区或停车场") },
                    modifier = Modifier.testTag("place-query"),
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Amber, fontSize = 13.sp)
                val choices = if (query.trim().length >= 2) results else s.nearbyPlaces
                choices.forEach { place ->
                    TextButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                        Text(place.label, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("选择询问目标，不会改变你的实际位置。", fontSize = 12.sp, color = Sea)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun TestVoices(s: ScreenState, vm: SonarViewModel) {
    var category by remember(s.config.testPlaceId) { mutableStateOf("全部") }
    var expanded by remember(s.config.testPlaceId) { mutableStateOf(false) }
    VoiceCard {
        Text("这里的测试声音", color = Teal, fontSize = 20.sp)
        Text("12 条 · 公告 / 路况 / 闲聊 · 自然语音", color = Sea, fontSize = 13.sp)
        Text("合成测试音频，不代表真实公告或实时路况。可离线手动试听。", color = Amber, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "公告", "路况", "闲聊").forEach { label ->
                FilterChip(
                    category == label,
                    {
                        category = label
                        expanded = true
                    },
                    { Text(label) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Mist,
                            selectedLabelColor = Teal,
                        ),
                )
            }
        }
        val clips = s.testClips.filter { category == "全部" || it.category == category }
        (if (expanded) clips else clips.distinctBy { it.category }).forEach { clip ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${clip.category} · ${clip.title}", Modifier.weight(1f), color = Sea)
                    TextButton(
                        onClick = {
                            if (s.playing == "test:${clip.id}") vm.stopPlayback()
                            else vm.playTestClip(clip)
                        },
                        enabled = !s.busy && !s.recording && !s.transcribing && !s.linx.busy,
                        modifier = Modifier.testTag("test-clip-${clip.id}"),
                    ) {
                        Text(
                            if (s.playing == "test:${clip.id}") "停止"
                            else "试听 ${kotlin.math.ceil(clip.duration).toInt()}秒"
                        )
                    }
                }
                Text(clip.transcript, color = Sea, fontSize = 14.sp, lineHeight = 21.sp)
                HorizontalDivider(color = Mist)
            }
        }
        if (category == "全部")
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "查看全部 12 条")
            }
    }
}

@Composable
private fun SettingsDialog(
    config: DeviceConfig,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    var url by remember { mutableStateOf(config.baseUrl) }
    var token by remember { mutableStateOf("") }
    var mock by remember { mutableStateOf(config.mock) }
    var debug by remember { mutableStateOf(config.debugTranscript) }
    var linxUrl by remember { mutableStateOf(config.linxUrl) }
    var linxDevice by remember { mutableStateOf(config.linxDevice) }
    var linxToken by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开发者连接") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务地址") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.testTag("base-url"),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (config.token.isBlank()) "设备凭证" else "设备凭证（留空保留）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.testTag("device-token"),
                )
                if (BuildConfig.DEBUG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = mock, onCheckedChange = { mock = it })
                        Text("模拟接口（非现场信息）", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = debug, onCheckedChange = { debug = it })
                        Text("调试转写（手动输入）", fontSize = 13.sp)
                    }
                }
                Text("Linx 设备配置", fontWeight = FontWeight.SemiBold)
                Text("需要已绑定的设备与连接凭证。未配置时不会自动公开没有转写的录音。", fontSize = 12.sp)
                OutlinedTextField(
                    value = linxUrl,
                    onValueChange = { linxUrl = it },
                    label = { Text("Linx WSS 地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = linxDevice,
                    onValueChange = { linxDevice = it },
                    label = { Text("Linx Device ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = linxToken,
                    onValueChange = { linxToken = it },
                    label = { Text("Linx 凭证（留空保留）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        config.copy(
                            baseUrl = url.trim(),
                            token = token.ifBlank { config.token },
                            mock = mock,
                            debugTranscript = debug,
                            linxUrl = linxUrl.trim(),
                            linxDevice = linxDevice.trim(),
                            linxToken = linxToken.ifBlank { config.linxToken },
                        )
                    )
                },
                modifier = Modifier.testTag("save-config"),
            ) {
                Text("保存并连接")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

private fun displayTime(value: String): String =
    runCatching {
            java.time.Instant.parse(value)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
        .getOrDefault("时间未知")
