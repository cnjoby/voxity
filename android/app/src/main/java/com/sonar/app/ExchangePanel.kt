package com.sonar.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ExchangeActivePanel(state: ExchangeState, vm: SonarViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().testTag("exchange-active"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.place.ifBlank { "个人语音会话" }, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 14.sp)
        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SonarMark()
            Spacer(Modifier.height(28.dp))
            Text(when {
                state.quiet -> "已安静"
                state.playing -> when (state.speaker) {
                    "agent" -> "声呐正在回应"
                    "system" -> "系统提示"
                    else -> "正在听原声"
                }
                state.phase == "clarifying" -> "听你补充"
                state.phase == "waiting" -> "等现场回应"
                state.phase in setOf("ending", "ended", "off") -> "麦克风已关闭"
                state.phase == "unknown" -> "正在核对发送结果"
                state.phase == "starting" -> "正在准备语音"
                state.phase == "capturing" -> "正在收音"
                state.phase == "recognizing" -> "正在识别"
                state.phase == "sending" -> "正在发送"
                state.phase == "processing" -> "声呐正在处理"
                state.phase == "assistant_wait" -> "正在准备回复"
                state.phase == "assistant_unavailable" -> "语音回复失败"
                state.phase == "shared" -> "原声已发送"
                state.phase == "private" -> "这句话没有发送"
                state.phase == "playback_unavailable" -> "原声播放失败"
                state.phase == "unavailable" -> "语音暂不可用"
                else -> "正在听"
            }, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.testTag("exchange-phase").semantics { liveRegion = LiveRegionMode.Polite })
            if (state.phase in setOf("unavailable", "unknown", "private", "recognizing", "sending", "processing", "assistant_wait", "assistant_unavailable", "shared", "waiting", "playback_unavailable"))
                Text(state.message, Modifier.padding(top = 12.dp).testTag("exchange-message"), textAlign = TextAlign.Center)
            if (state.remote)
                Text("远程地点 · 非手机实到", Modifier.padding(top = 12.dp), fontSize = 14.sp)
            if (state.playing) Text(when (state.speaker) {
                "agent" -> "声呐回应 · 合成语音"
                "system" -> "系统提示"
                else -> "原声播放"
            },
                Modifier.padding(top = 12.dp).testTag("exchange-source-type"), fontSize = 14.sp)
        }
        Text(if (state.quiet) "说“继续”恢复；说“结束”关闭麦克风。" else "可以熄屏。你开口时，声音先让路。",
            Modifier.padding(bottom = 20.dp), fontSize = 14.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(vm::toggleQuiet, Modifier.weight(1f).heightIn(min = 56.dp).testTag("exchange-quiet")) {
                Text(if (state.quiet) "继续" else "安静")
            }
            OutlinedButton({ ExchangeService.command(context, ExchangeService.STOP) },
                Modifier.weight(1f).heightIn(min = 56.dp).testTag("exchange-stop")) { Text("结束") }
        }
    }
}
