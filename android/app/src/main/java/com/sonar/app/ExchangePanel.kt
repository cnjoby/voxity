package com.sonar.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ExchangeActivePanel(state: ExchangeState, vm: SonarViewModel) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().testTag("exchange-active"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(state.place, fontSize = 16.sp)
            Text(when {
                state.quiet -> "已安静"
                state.playing -> "正在听相关原声"
                state.phase == "shared" -> "已分享"
                state.phase == "starting" -> "正在开启交流"
                else -> "现场交流中"
            }, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("exchange-phase"))
            if (state.message != "现场交流中") Text(state.message, modifier = Modifier.testTag("exchange-message"))
            Text("正常交流不播控制声音。你开口时，原声播放先让路。", fontSize = 15.sp)
            if (!state.echoProtected && state.phase != "starting")
                Text("本机回声消除不可用；播放期间捕获的声音保持私有。", fontSize = 13.sp)
            if (!state.localCommands)
                Text("本机离线语音指令尚未就绪；开口仍会暂停原声。断网时可用通知中的安静/结束。", fontSize = 13.sp)
            if (state.privateCount > 0)
                Text("本机私有暂存 ${state.privateCount} 段，不会自动补发。", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::toggleQuiet, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("exchange-quiet")) {
                    Text(if (state.quiet) "恢复交流" else "安静一下")
                }
                OutlinedButton(onClick = { ExchangeService.command(context, ExchangeService.STOP) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("exchange-stop")) { Text("结束交流") }
            }
        }
    }
}
