package com.sonar.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
fun SonarScreen(vm: SonarViewModel, onRecord: (Delivery?) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Teal,
                onPrimary = Color.White,
                secondary = Sea,
                background = Paper,
                surface = Color.White,
                onSurface = Ink,
            ),
        typography =
            Typography(
                bodyLarge =
                    MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp)
            ),
    ) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "sonar",
                            fontFamily = FontFamily.Serif,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = Sea,
                        )
                        Text("声呐  /  听见同路的声音", fontSize = 12.sp, color = Sea)
                    }
                    TextButton(
                        onClick = { settings = true },
                        enabled = !s.busy && !s.recording && !s.transcribing && s.playing == null,
                        modifier = Modifier.testTag("settings"),
                    ) {
                        Text("连接设置")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(7.dp)
                            .background(if (s.connected) Teal else Amber, CircleShape)
                    )
                    Text(
                        if (s.config.mock) "模拟接口 · ${if(s.connected) "已连接" else "未连接"}"
                        else if (s.connected) "现场互助 · 已连接" else "尚未连接",
                        color = Sea,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        s.profile?.userId ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Sea,
                    )
                }
                if (s.config.mock) Info("模拟模式：回复与静音音频用于预览，不代表现场信息。", true)
                if (s.config.debugTranscript) Info("调试转写已开启：文字由手动输入，未经过语音识别。", true)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "问问前面的人",
                            color = Sea,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("中环路 · 金科路口", fontSize = 16.sp, color = Ink)
                        Text("说一句你想知道的，由现场的人回应。", fontSize = 14.sp, color = Sea.copy(alpha = .75f))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Canvas(Modifier.size(54.dp)) {
                                val c = center
                                drawCircle(
                                    Teal.copy(alpha = .18f),
                                    size.minDimension * .46f,
                                    c,
                                    style = Stroke(2.dp.toPx()),
                                )
                                drawCircle(
                                    Teal.copy(alpha = .5f),
                                    size.minDimension * .31f,
                                    c,
                                    style = Stroke(2.dp.toPx()),
                                )
                                drawCircle(
                                    if (s.recording) Amber else Teal,
                                    size.minDimension * .13f,
                                    c,
                                )
                            }
                            Button(
                                onClick = {
                                    if (s.recording) vm.finishRecording() else onRecord(null)
                                },
                                enabled =
                                    s.recording ||
                                        (s.connected &&
                                            !s.busy &&
                                            !s.transcribing &&
                                            s.draft == null &&
                                            s.question?.status != "waiting"),
                                modifier = Modifier.weight(1f).height(54.dp).testTag("record"),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(if (s.recording) "结束录音" else "录音提问", fontSize = 17.sp)
                            }
                        }
                        Text(
                            if (s.recording) "正在录音，最长 8 秒…" else "最长 8 秒 · 核对后才公开",
                            fontSize = 12.sp,
                            color = Sea.copy(alpha = .7f),
                        )
                    }
                }
                if (s.draft != null) DraftCard(s, vm)
                if (s.question != null) QuestionCard(s, vm)
                s.deliveries
                    .filter { it.type == "invitation" }
                    .forEach { d ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("你恰好在现场", color = Teal, fontWeight = FontWeight.SemiBold)
                                Text(d.payload.text("transcript"), fontSize = 18.sp)
                                Text("愿意的话，回应一句；也可以保持安静。", fontSize = 13.sp, color = Sea)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { onRecord(d) },
                                        enabled =
                                            !s.busy &&
                                                !s.recording &&
                                                !s.transcribing &&
                                                s.draft == null,
                                    ) {
                                        Text("录音回应")
                                    }
                                    TextButton(onClick = { vm.decline(d) }, enabled = !s.busy) {
                                        Text("这次不了")
                                    }
                                }
                            }
                        }
                    }
                s.deliveries
                    .filter { it.type == "audio" && it.questionId != s.question?.id }
                    .forEach { d ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("还有一条待听原声", color = Teal)
                                Text(d.payload.text("transcript"))
                                TextButton(
                                    onClick = { vm.playDelivery(d) },
                                    enabled = !s.quiet && !s.busy && s.playing == null,
                                ) {
                                    Text("播放原声")
                                }
                            }
                        }
                    }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.toggleQuiet() },
                        enabled = s.profile != null,
                        modifier = Modifier.weight(1f).testTag("quiet"),
                    ) {
                        Text(if (s.quiet) "恢复收听" else "现在安静")
                    }
                    OutlinedButton(
                        onClick = { vm.toggleAssist() },
                        enabled = s.profile != null && !s.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (s.profile?.assist == true) "关闭互助" else "开启互助")
                    }
                }
                if (s.playing != null)
                    TextButton(
                        onClick = { vm.stopPlayback(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止本次播放")
                    }
                Text(
                    s.message,
                    color = if (s.connected) Sea else Amber,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.testTag("message"),
                )
                HorizontalDivider(color = Mist)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模拟位置", fontSize = 11.sp, color = Sea.copy(alpha = .7f))
                        Text(
                            if (s.profile?.presence?.text("place_id") == "zhonghuan_jinke")
                                "中环路金科路口"
                            else "驶近路口的区域",
                            fontSize = 13.sp,
                            color = Sea,
                        )
                    }
                    TextButton(
                        onClick = { vm.confirmPresence() },
                        enabled = s.connected && !s.busy,
                    ) {
                        Text("确认位置")
                    }
                }
                Text(
                    "人提出问题，也由人回答。",
                    fontSize = 12.sp,
                    color = Sea.copy(alpha = .6f),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        if (settings)
            SettingsDialog(s.config, onDismiss = { settings = false }) {
                vm.configure(it)
                settings = false
            }
    }
}

@Composable
private fun Info(text: String, warning: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = if (warning) Amber else Sea,
        modifier =
            Modifier.fillMaxWidth()
                .background(if (warning) Color(0xFFF8EEDD) else Mist, RoundedCornerShape(10.dp))
                .padding(12.dp),
    )
}

@Composable
private fun DraftCard(s: ScreenState, vm: SonarViewModel) {
    val d = s.draft ?: return
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (d.replyTo == null) "核对你的提问" else "核对你的回答",
                color = Sea,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            )
            Text(
                d.source.ifBlank { if (s.transcribing) "正在识别候选…" else "尚无可核对转写，可重录或在连接设置中启用调试转写" },
                color = Amber,
                fontSize = 12.sp,
            )
            if (s.config.debugTranscript)
                OutlinedTextField(
                    value = d.transcript,
                    onValueChange = vm::editTranscript,
                    label = { Text("调试转写") },
                    enabled = !d.frozen && !s.busy && !s.recording,
                    modifier = Modifier.fillMaxWidth().testTag("transcript"),
                    minLines = 2,
                )
            else Text(d.transcript.ifBlank { "识别尚未完成" }, fontSize = 17.sp, lineHeight = 26.sp)
            if (d.frozen) Text("内容已锁定。重试沿用同一录音，不会重复发言。", fontSize = 12.sp, color = Sea)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = vm::preview,
                    enabled =
                        !s.quiet && !s.busy && !s.recording && !s.transcribing && s.playing == null,
                ) {
                    Text("听听原声")
                }
                TextButton(
                    onClick = vm::discardDraft,
                    enabled = !s.busy && !s.recording && !s.transcribing,
                ) {
                    Text("丢弃重录")
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
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("publish"),
            ) {
                Text(if (s.busy) "正在处理…" else if (d.frozen) "重试确认公开" else "确认公开")
            }
        }
    }
}

@Composable
private fun QuestionCard(s: ScreenState, vm: SonarViewModel) {
    val q = s.question ?: return
    val d = s.deliveries.firstOrNull { it.type == "audio" && it.questionId == q.id }
    val title =
        when (q.status) {
            "waiting" -> "正在替你等回复"
            "answered" -> "现场有人回应了"
            "expired" -> "暂时无人回应"
            "cancelled" -> "提问已结束"
            else -> "正在更新任务"
        }
    Column(
        Modifier.fillMaxWidth().border(1.dp, Mist, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                fontSize = 19.sp,
                color = Sea,
                fontWeight = FontWeight.SemiBold,
            )
            if (q.status == "waiting")
                Text("${s.remainingSeconds ?: 0}s", fontFamily = FontFamily.Monospace, color = Teal)
        }
        if (q.status == "waiting") {
            Text("问题已进入广场。愿意互助的现场参与者会收到提示。", color = Sea, fontSize = 14.sp, lineHeight = 22.sp)
            TextButton(
                onClick = vm::cancelQuestion,
                enabled = !s.busy,
                modifier = Modifier.testTag("cancel-question"),
            ) {
                Text("取消这次提问")
            }
        }
        if (q.answer != null) {
            Text(q.answer.text("transcript"), fontSize = 18.sp, lineHeight = 28.sp, color = Ink)
            Text(
                "${q.answer.text("author_label")} · ${displayTime(q.answer.text("published_at"))}",
                fontSize = 11.sp,
                color = Sea.copy(alpha = .75f),
            )
            Text(
                when {
                    s.audioOutcome == "played" -> "原声已播放"
                    s.audioOutcome == "skipped" -> "本次原声已跳过"
                    s.quiet -> "回答已收到 · 原声待听（当前安静）"
                    s.playing == d?.id && d != null -> "正在播放原声…"
                    else -> "回答已收到"
                },
                fontSize = 13.sp,
                color = Teal,
                modifier = Modifier.testTag("audio-outcome"),
            )
            if (d != null && s.audioOutcome !in setOf("played", "skipped"))
                Button(
                    onClick = { vm.playDelivery(d) },
                    enabled = !s.quiet && !s.busy && s.playing == null,
                    modifier = Modifier.testTag("play-answer"),
                ) {
                    Text("播放现场原声")
                }
        }
        if (q.status == "expired") Text("可以稍后重新问一句。", color = Sea, fontSize = 14.sp)
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
        title = { Text("连接声呐") },
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
