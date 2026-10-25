package com.sonar.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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

@Composable
fun SonarScreen(vm: SonarViewModel, onLocate: () -> Unit, onVoiceStart: () -> Unit = vm::startVoiceSession) {
    val s by vm.state.collectAsStateWithLifecycle()
    val exchange by ExchangeRuntime.state.collectAsStateWithLifecycle()
    var preparation by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    BackHandler(enabled = preparation && !connection && !places) { preparation = false }
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
        secondary = Sea, background = Paper, surface = Color.White, onSurface = Ink)) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            when {
                exchange.owned -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("声呐", color = Sea, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    ExchangeActivePanel(exchange, vm)
                }
                preparation -> Preparation(s, vm, onBack = { preparation = false },
                    onPlaces = { places = true }, onConnection = { connection = true })
                else -> StartSurface(s, exchange, onVoiceStart, onPrepare = { preparation = true }, onPlaces = { places = true })
            }
        }
        if (places) RemotePlaces(s, onDismiss = { places = false }, onSelect = {
            vm.useRemotePlace(it); places = false
        }, onLocate = { onLocate(); places = false })
        if (connection) ConnectionDialog(s.config, onDismiss = { connection = false }) {
            vm.configure(it); connection = false
        }
    }
}

@Composable
private fun StartSurface(s: ScreenState, exchange: ExchangeState, onStart: () -> Unit, onPrepare: () -> Unit, onPlaces: () -> Unit) {
    val canStart = s.connected && s.config.token.isNotBlank() && s.config.linxToken.isNotBlank() &&
        !s.busy && !s.recording && !s.transcribing
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("声呐", Modifier.fillMaxWidth(), color = Sea, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(when {
            s.config.remotePlaceId.isNotBlank() -> "远程地点 · ${s.selectedPlace?.shortcutLabel ?: "正在读取地点"} · 非手机实到"
            s.selectedPlace != null -> "真实地点 · 当前设备位置 · ${s.selectedPlace.label}"
            else -> "真实地点 · 当前设备位置 · 尚未取得定位"
        }, Modifier.fillMaxWidth().clickable(onClick = onPlaces).padding(vertical = 8.dp).testTag("location-status"), color = Sea, fontSize = 14.sp)
        Column(Modifier.weight(1f).heightIn(min = 270.dp).padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SonarMark()
            Spacer(Modifier.height(28.dp))
            Text("关心的事，\n直接说。", color = Sea, fontSize = 32.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("找消息，问现场，听回应。", Modifier.padding(top = 12.dp), color = Ink)
        }
        val status = when {
            exchange.phase == "unavailable" -> exchange.message.ifBlank { "免提暂不可用，请检查语音连接" }
            !s.connected -> s.message
            s.config.linxToken.isBlank() -> "请先绑定灵矽语音"
            s.quiet -> "已保留安静状态；开始后说“继续”恢复"
            else -> "连接已就绪"
        }
        Text(status, Modifier.padding(bottom = 16.dp).testTag("message")
            .semantics { liveRegion = LiveRegionMode.Polite },
            color = if (exchange.phase == "unavailable" || !s.connected) Amber else Sea,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text("问人、转告或回应时，才把原声发给别人。\n相关原声自动播；有相关提问时，愿意再答。",
            Modifier.padding(bottom = 16.dp), color = Ink, fontSize = 13.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Center)
        Button(onClick = onStart, enabled = canStart, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("voice-start")) { Text("开始", fontSize = 18.sp) }
        TextButton(onClick = onPrepare, Modifier.padding(top = 8.dp).testTag("settings")) { Text("连接与隐私") }
    }
}

@Composable
internal fun SonarMark() {
    Canvas(Modifier.size(132.dp)) {
        drawCircle(Teal.copy(alpha = .2f), size.minDimension * .49f, style = Stroke(1.dp.toPx()))
        drawCircle(Teal.copy(alpha = .4f), size.minDimension * .33f, style = Stroke(1.dp.toPx()))
        drawCircle(Teal, size.minDimension * .12f, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun Preparation(s: ScreenState, vm: SonarViewModel, onBack: () -> Unit,
    onPlaces: () -> Unit, onConnection: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("连接与隐私", Modifier.weight(1f), color = Sea, style = MaterialTheme.typography.headlineSmall)
            TextButton(onBack, modifier = Modifier.testTag("preparation-back")) { Text("返回") }
        }
        PreparationCard("地点") {
            Text(if (s.config.remotePlaceId.isNotBlank()) s.selectedPlace?.shortcutLabel ?: "正在读取地点" else "当前设备位置")
            Text(if (s.config.remotePlaceId.isNotBlank()) "远程地点 · 非手机实到位置" else s.locationStatus,
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onPlaces, Modifier.fillMaxWidth().testTag("choose-place")) { Text("切换地点") }
        }
        PreparationCard("声呐连接") {
            Text(if (s.connected) "已连接服务器" else s.message)
            TextButton(onConnection, modifier = Modifier.testTag("developer-connection")) { Text("连接配置") }
        }
        PreparationCard("灵矽语音") {
            Text(s.linx.status, Modifier.testTag("linx-status"))
            Text("设备：${s.config.linxDevice}", style = MaterialTheme.typography.bodySmall)
            s.linx.activationCode?.let { code ->
                Text("激活码：$code", Modifier.testTag("linx-code"), style = MaterialTheme.typography.titleLarge)
                Text("在灵矽控制台完成绑定后检查连接。")
                TextButton({ vm.activateLinx(true) }, enabled = !s.linx.busy,
                    modifier = Modifier.testTag("linx-check")) { Text("我已绑定，检查连接") }
            }
            TextButton({ vm.activateLinx() }, enabled = !s.linx.busy && !s.busy,
                modifier = Modifier.testTag("linx-activate")) {
                Text(if (s.config.linxToken.isBlank()) "获取激活码" else "刷新连接配置")
            }
        }
        PreparationCard("隐私") {
            Text("点击开始才开启本次麦克风。查询默认仅本人；明确问人、转告或回应时发送对应原声。")
            Text("可以说“安静”暂停，“结束”关闭麦克风。已保存的参与意愿继续有效，连接凭证和历史不会因更新被清除。")
        }
    }
}

@Composable
private fun PreparationCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun RemotePlaces(s: ScreenState, onDismiss: () -> Unit, onSelect: (PlaceChoice) -> Unit, onLocate: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择地点") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("远程地点", style = MaterialTheme.typography.titleMedium)
            Text("远程选择不代表你人在现场，也不会改变其他设备的位置。", style = MaterialTheme.typography.bodySmall)
            if (s.placeShortcuts.isEmpty()) Text("连接服务器后读取地点，请稍后重试。")
            s.placeShortcuts.forEach { place ->
                OutlinedButton({ onSelect(place) }, Modifier.fillMaxWidth().testTag("remote-place-${place.id}")) {
                    RadioButton(selected = s.config.remotePlaceId == place.id, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(place.shortcutLabel, Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            Text("真实地点", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onLocate, enabled = !s.locating, modifier = Modifier.fillMaxWidth().testTag("real-location")) {
                RadioButton(selected = s.config.remotePlaceId.isBlank(), onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(if (s.locating) "正在获取设备位置…" else "当前设备位置", Modifier.weight(1f))
            }
            Text("使用设备定位，需要开启定位权限和系统定位。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("返回") } })
}

private val PlaceChoice.shortcutLabel: String
    get() = if (id == "B0FFJSBSGR") "七牛" else label

@Composable
private fun ConnectionDialog(config: DeviceConfig, onDismiss: () -> Unit, onSave: (DeviceConfig) -> Unit) {
    var url by remember { mutableStateOf(config.baseUrl) }
    var token by remember { mutableStateOf("") }
    var linxUrl by remember { mutableStateOf(config.linxUrl) }
    var linxDevice by remember { mutableStateOf(config.linxDevice) }
    var linxToken by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接配置") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.testTag("base-url"))
            OutlinedTextField(token, { token = it }, label = { Text("设备凭证（留空保留）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.testTag("device-token"))
            OutlinedTextField(linxUrl, { linxUrl = it }, label = { Text("灵矽 WSS 地址") }, singleLine = true)
            OutlinedTextField(linxDevice, { linxDevice = it }, label = { Text("灵矽设备 ID") }, singleLine = true)
            OutlinedTextField(linxToken, { linxToken = it }, label = { Text("灵矽凭证（留空保留）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = {
        TextButton({ onSave(config.copy(baseUrl = url.trim(), token = token.ifBlank { config.token },
            linxUrl = linxUrl.trim(), linxDevice = linxDevice.trim(), linxToken = linxToken.ifBlank { config.linxToken })) },
            modifier = Modifier.testTag("save-config")) { Text("保存并连接") }
    }, dismissButton = { TextButton(onDismiss) { Text("返回") } })
}
