package com.sonar.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ListeningPreferences(s: ScreenState, vm: SonarViewModel) {
    val p = s.profile ?: return
    val settings = p.listening
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("我想听到的声音", style = MaterialTheme.typography.titleLarge)
            for ((key, label) in
                listOf(
                    "replies_enabled" to "自己的问题回复",
                    "channel_enabled" to "地点运营公告",
                    "scene_enabled" to "到达时的情境声音",
                )) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f))
                    Switch(
                        settings.optBoolean(key, key != "scene_enabled"),
                        { vm.saveListening(json(key to it)) },
                        enabled = !s.busy && !s.config.mock,
                    )
                }
            }
            Text("安静一下会统一暂停自动出声。关闭现场互助只影响回答邀请。", style = MaterialTheme.typography.bodySmall)
            var interests by
                remember(p.userId, settings.toString()) {
                    mutableStateOf(
                        settings.optJSONArray("interests")?.strings()?.joinToString("，").orEmpty()
                    )
                }
            OutlinedTextField(
                interests,
                { interests = it },
                label = { Text("情境兴趣，用逗号分隔；留空听各类") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    vm.saveListening(
                        json(
                            "interests" to
                                JSONArray(
                                    interests
                                        .split(',', '，')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .take(20)
                                )
                        )
                    )
                },
                enabled = !s.busy && !s.config.mock,
            ) {
                Text("保存兴趣")
            }
            if (
                settings.optJSONArray("muted_topics")?.length() ?: 0 > 0 ||
                    settings.optJSONArray("muted_channels")?.length() ?: 0 > 0
            ) {
                TextButton(
                    onClick = {
                        vm.saveListening(
                            json("muted_topics" to JSONArray(), "muted_channels" to JSONArray())
                        )
                    },
                    enabled = !s.busy,
                ) {
                    Text("恢复已减少的情境与频道")
                }
            }
        }
    }
}

@Composable
fun PlaceVoices(s: ScreenState, vm: SonarViewModel, onRecord: (Delivery?) -> Unit) {
    var composing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = vm::browseVoices,
            enabled = s.connected && !s.busy && s.selectedPlace != null && !s.config.mock,
            modifier = Modifier.fillMaxWidth().testTag("browse-voices"),
        ) {
            Text(if (s.config.testPlaceId.isNotBlank()) "刷新这个点位的服务器声音" else "听听这里的故事与公告")
        }
        if (s.catalogPlace != null) {
            Text(
                "${s.catalogPlace.label} · ${s.announcementStatus}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("主动浏览可查看远处地点；手选地点不代表已经到达。", style = MaterialTheme.typography.bodySmall)
            val subscriptions =
                s.profile?.listening?.optJSONArray("subscribed_places")?.strings().orEmpty()
            val subscribed = s.catalogPlace.id in subscriptions
            TextButton(
                onClick = {
                    vm.saveListening(
                        json(
                            "subscribed_places" to
                                JSONArray(
                                    if (subscribed) subscriptions - s.catalogPlace.id
                                    else (subscriptions + s.catalogPlace.id).take(20)
                                )
                        )
                    )
                },
                enabled = !s.busy,
            ) {
                Text(if (subscribed) "取消关注此地点" else "关注此地点")
            }
            OutlinedButton(
                onClick = { composing = true },
                enabled =
                    !s.busy &&
                        !s.recording &&
                        !s.transcribing &&
                        s.draft == null &&
                        s.selectedPlace?.id == s.catalogPlace.id,
                modifier = Modifier.fillMaxWidth().testTag("share-voice"),
            ) {
                Text("在此留下一段声音")
            }
            s.channels.forEach { channel ->
                TextButton(
                    onClick = {
                        runCatching { vm.prepareChannelQuestion(channel.text("id")) }
                            .onSuccess { onRecord(null) }
                            .onFailure { vm.notice(it.message ?: "暂不能提问") }
                    },
                    enabled =
                        !s.busy &&
                            !s.recording &&
                            !s.transcribing &&
                            s.draft == null &&
                            s.question?.status != "waiting" &&
                            s.selectedPlace?.id == s.catalogPlace.id,
                ) {
                    Text("向${channel.text("label")}提问")
                }
            }
            s.catalog.forEach { item ->
                Card(
                    Modifier.fillMaxWidth().testTag("catalog-${item.text("post_id")}"),
                    colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VoiceProvenance(item)
                        Text(item.text("transcript"), style = MaterialTheme.typography.bodyLarge)
                        val delivered =
                            s.deliveries.firstOrNull {
                                it.payload.text("post_id") == item.text("post_id")
                            }
                        Row {
                            TextButton(
                                onClick = { vm.hearVoice(item.getString("post_id")) },
                                enabled =
                                    !s.quiet &&
                                        !s.busy &&
                                        !s.recording &&
                                        !s.transcribing &&
                                        s.playing == null,
                            ) {
                                Text(if (delivered != null) "听待听原声" else "听原声")
                            }
                            if (
                                item.text("author_id") == s.profile?.userId ||
                                    s.channels.any {
                                        it.text("id") == item.text("channel_id") &&
                                            it.optBoolean("can_publish")
                                    }
                            ) {
                                TextButton(
                                    onClick = { vm.withdrawVoice(item.getString("post_id")) },
                                    enabled = !s.busy,
                                ) {
                                    Text("撤回")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (composing)
        VoiceComposer(s, onDismiss = { composing = false }) { fields, ttl ->
            runCatching { vm.prepareVoice(fields, ttl) }
                .onSuccess {
                    composing = false
                    onRecord(null)
                }
                .onFailure { vm.notice(it.message ?: "请检查发布设置") }
        }
}

@Composable
fun VoiceProvenance(item: JSONObject) {
    if (item.optBoolean("synthetic"))
        Text("合成测试内容 · 非实时公告、路况或真人发言", style = MaterialTheme.typography.labelMedium)
    Text(
        "${item.text("author_label")} · ${voiceTime(item.text("published_at"))}",
        style = MaterialTheme.typography.labelMedium,
    )
    if (item.optBoolean("demo_operator"))
        Text("封闭演示授权运营身份", style = MaterialTheme.typography.labelSmall)
    Text(
        "${item.text("place_name")} · ${item.text("location_scope")}",
        style = MaterialTheme.typography.bodySmall,
    )
    item
        .text("valid_until")
        .takeIf { it.isNotBlank() }
        ?.let { Text("有效至 ${voiceTime(it)}", style = MaterialTheme.typography.bodySmall) }
    item
        .text("recorded_at")
        .takeIf { it.isNotBlank() }
        ?.let { Text("录制时间 ${voiceTime(it)}", style = MaterialTheme.typography.bodySmall) }
    if (item.text("recorded_at").isBlank()) {
        Text(
            "录制时间未提供 · 收录于 ${voiceTime(item.text("collected_at"))}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    item
        .text("source_note")
        .takeIf { it.isNotBlank() }
        ?.let { Text("来源：$it", style = MaterialTheme.typography.bodySmall) }
    item
        .text("context_note")
        .takeIf { it.isNotBlank() }
        ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun DeliveryControls(s: ScreenState, vm: SonarViewModel, d: Delivery) {
    VoiceProvenance(d.payload)
    Text(
        d.payload.optJSONArray("reasons")?.strings()?.joinToString(" · ").orEmpty(),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { vm.playDelivery(d) },
            enabled = !s.quiet && !s.busy && !s.recording && !s.transcribing && s.playing == null,
        ) {
            Text("听原声")
        }
        TextButton(onClick = { vm.later(d) }, enabled = !s.busy) { Text("稍后听") }
        TextButton(onClick = { vm.skip(d) }, enabled = !s.busy) { Text("跳过") }
    }
    if (d.payload.text("kind") == "scene")
        TextButton(onClick = { vm.reduceScene(d) }, enabled = !s.busy) { Text("减少此类推荐") }
    if (d.payload.text("kind") == "announcement")
        TextButton(
            onClick = { vm.muteChannel(d.payload.getString("channel_id")) },
            enabled = !s.busy,
        ) {
            Text("关闭此频道自动播报")
        }
    if (d.payload.text("kind") in setOf("answer", "reply"))
        TextButton(onClick = { vm.openConversation(d.questionId) }, enabled = !s.busy) {
            Text("查看上下文并继续回复")
        }
}

@Composable
fun ConversationDialog(s: ScreenState, vm: SonarViewModel, onRecord: (Delivery?) -> Unit) {
    AlertDialog(
        onDismissRequest = vm::closeConversation,
        title = { Text("公开问答 · 原问题与回复链") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("回复对象明确，确认后仍按公开问答发布。")
                s.conversation.forEach { item ->
                    VoiceProvenance(item)
                    Text(item.text("transcript"))
                    if (
                        s.canReply &&
                            item.text("author_id") != s.profile?.userId &&
                            item.text("kind") != "question"
                    ) {
                        TextButton(
                            onClick = {
                                val target = vm.replyTarget(item)
                                vm.closeConversation()
                                onRecord(target)
                            },
                            enabled = !s.busy && !s.recording && !s.transcribing && s.draft == null,
                        ) {
                            Text("回复 ${item.text("author_label")} 的这句话")
                        }
                        item
                            .text("operator_reply_channel")
                            .takeIf { it.isNotBlank() }
                            ?.let { cid ->
                                TextButton(
                                    onClick = {
                                        val target =
                                            vm.replyTarget(
                                                JSONObject(item.toString())
                                                    .put("reply_channel_id", cid)
                                            )
                                        vm.closeConversation()
                                        onRecord(target)
                                    },
                                    enabled =
                                        !s.busy &&
                                            !s.recording &&
                                            !s.transcribing &&
                                            s.draft == null,
                                ) {
                                    Text("以授权运营身份回复这句话")
                                }
                            }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeConversation) { Text("收起") } },
    )
}

@Composable
private fun VoiceComposer(
    s: ScreenState,
    onDismiss: () -> Unit,
    onStart: (JSONObject, Int) -> Unit,
) {
    var kind by remember { mutableStateOf("scene") }
    var purpose by remember { mutableStateOf("reflection") }
    var ttl by remember { mutableStateOf("30") }
    var source by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf("") }
    var unknown by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("poi") }
    var scopeLabel by remember { mutableStateOf("") }
    var replaces by remember { mutableStateOf<String?>(null) }
    val operators = s.channels.filter { it.optBoolean("can_publish") }
    var useChannel by remember { mutableStateOf(false) }
    var channelId by remember { mutableStateOf(operators.firstOrNull()?.text("id")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认声音的地点与用途") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("适用地点：${s.selectedPlace?.label} · 确认后公开")
                for ((value, label) in
                    listOf(
                        "scene" to "个人感慨 / 体验",
                        "knowledge" to "知识解说",
                        "announcement" to "运营即时公告",
                    )) {
                    if (value != "announcement" || operators.isNotEmpty())
                        FilterChip(
                            selected = kind == value,
                            onClick = { kind = value },
                            label = { Text(label) },
                        )
                }
                if (kind == "scene") {
                    for ((value, label) in
                        listOf(
                            "reflection" to "历史感慨",
                            "experience" to "体验分享",
                            "observation" to "即时观察",
                        )) FilterChip(purpose == value, { purpose = value }, { Text(label) })
                    Row {
                        Text("适用条件未知，仅供主动浏览", Modifier.weight(1f))
                        Switch(unknown, { unknown = it })
                    }
                    OutlinedTextField(
                        topics,
                        { topics = it },
                        label = { Text("主题，用逗号分隔") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == "knowledge" && operators.isNotEmpty()) {
                    Row {
                        Text("以授权运营身份发布", Modifier.weight(1f))
                        Switch(useChannel, { useChannel = it })
                    }
                    if (useChannel)
                        operators.forEach { channel ->
                            FilterChip(
                                channelId == channel.text("id"),
                                { channelId = channel.text("id") },
                                { Text(channel.text("label")) },
                            )
                        }
                }
                if (kind == "knowledge")
                    OutlinedTextField(
                        source,
                        { source = it },
                        label = { Text("来源与核对日期（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                if (kind == "announcement") {
                    operators.forEach { channel ->
                        FilterChip(
                            channelId == channel.text("id"),
                            {
                                channelId = channel.text("id")
                                replaces = null
                            },
                            { Text(channel.text("label")) },
                        )
                    }
                    for ((value, label) in
                        listOf(
                            "poi" to "整个 POI 附近",
                            "entrance" to "指定入口",
                            "floor" to "指定楼层",
                            "area" to "指定区域",
                        )) FilterChip(
                        scope == value,
                        {
                            scope = value
                            replaces = null
                        },
                        { Text(label) },
                    )
                    if (scope != "poi") {
                        OutlinedTextField(
                            scopeLabel,
                            {
                                scopeLabel = it
                                replaces = null
                            },
                            label = { Text("范围名称，例如 B2") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("当前无法确认入口或楼层到达，仅供主动查询。")
                    }
                    Text("可选择替代一条当前公告：")
                    s.catalog
                        .filter {
                            it.text("kind") == "announcement" &&
                                it.text("channel_id") == channelId &&
                                it.text("scope") == scope &&
                                it.text("scope_label") == scopeLabel
                        }
                        .forEach { item ->
                            FilterChip(
                                replaces == item.text("post_id"),
                                {
                                    replaces =
                                        if (replaces == item.text("post_id")) null
                                        else item.text("post_id")
                                },
                                { Text(item.text("transcript")) },
                            )
                        }
                }
                if (kind == "announcement" || (kind == "scene" && purpose == "observation"))
                    OutlinedTextField(
                        ttl,
                        { ttl = it },
                        label = { Text("立即生效，有效分钟数（1～60）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                Text("下一步录音，核对原声和文字后再确认发布。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fields =
                        json(
                            "kind" to kind,
                            "target_place_id" to s.selectedPlace!!.id,
                            "visibility" to "public",
                            "purpose" to
                                when (kind) {
                                    "announcement" -> "notice"
                                    "knowledge" -> "explanation"
                                    else -> purpose
                                },
                            "applicability" to if (unknown) "unknown" else "unconditional",
                            "scope" to if (kind == "announcement") scope else "poi",
                            "topics" to
                                JSONArray(
                                    topics
                                        .split(',', '，')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .take(10)
                                ),
                        )
                    if (kind == "knowledge") {
                        fields.put("source_note", source)
                        if (useChannel) fields.put("channel_id", channelId)
                    }
                    if (kind == "announcement") {
                        fields.put("channel_id", channelId)
                        if (scope != "poi") fields.put("scope_label", scopeLabel)
                        replaces?.let { fields.put("replaces", it) }
                    }
                    onStart(fields, ttl.toIntOrNull() ?: 0)
                },
                enabled =
                    (kind != "knowledge" || source.isNotBlank()) &&
                        (scope == "poi" || scopeLabel.isNotBlank()),
            ) {
                Text("确认地点，开始录音")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun voiceTime(value: String): String =
    runCatching {
            java.time.Instant.parse(value)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
        }
        .getOrDefault("时间未知")
