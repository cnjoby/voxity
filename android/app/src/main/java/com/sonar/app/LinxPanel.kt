package com.sonar.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LinxPanel(s: ScreenState, vm: SonarViewModel, onRecord: () -> Unit) {
    val context = LocalContext.current
    val linx = s.linx
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("灵矽语音", style = MaterialTheme.typography.titleLarge)
            Text("把这台手机接入你的灵矽智能体。", style = MaterialTheme.typography.bodyMedium)
            Text(linx.status, modifier = Modifier.testTag("linx-status"))
            SelectionContainer {
                Text("本机设备：${s.config.linxDevice}", style = MaterialTheme.typography.bodySmall)
            }
            linx.activationCode?.let { code ->
                Text("本机激活码", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.testTag("linx-code"),
                    )
                }
                Text("在控制台为声呐绑定对应智能体。激活码过期可重新获取。", style = MaterialTheme.typography.bodySmall)
                linx.consoleUrl?.let { url ->
                    TextButton(
                        onClick = {
                            runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }
                                .onFailure { vm.notice("未能打开浏览器，请在灵矽控制台输入激活码") }
                        }
                    ) {
                        Text("打开灵矽控制台")
                    }
                }
                OutlinedButton(
                    onClick = { vm.activateLinx(true) },
                    enabled = !linx.busy && !s.busy,
                    modifier = Modifier.fillMaxWidth().testTag("linx-check"),
                ) {
                    Text("我已绑定，检查连接")
                }
            }
            TextButton(
                onClick = { vm.activateLinx() },
                enabled = !linx.busy && !s.busy && !s.recording && !s.transcribing,
                modifier = Modifier.testTag("linx-activate"),
            ) {
                Text(if (s.config.linxToken.isBlank()) "获取激活码" else "刷新连接配置")
            }
            if (s.config.linxToken.isNotBlank()) {
                Row(Modifier.fillMaxWidth()) {
                    Text("允许播放灵矽回复", Modifier.weight(1f))
                    Switch(
                        linx.soundEnabled,
                        vm::linxSound,
                        modifier = Modifier.testTag("linx-sound"),
                    )
                }
                if (s.profile?.mode == "quiet")
                    Text("个人静音已开启，本次只显示回复文字。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (linx.phase == "recording") vm.finishLinxRecording() else onRecord()
                    },
                    enabled =
                        linx.phase == "recording" ||
                            (!linx.busy &&
                                !s.busy &&
                                !s.recording &&
                                !s.transcribing &&
                                s.playing == null),
                    modifier = Modifier.fillMaxWidth().testTag("linx-record"),
                ) {
                    Text(if (linx.phase == "recording") "结束录音" else "试说一句")
                }
                if (linx.phase in setOf("recognizing", "playing"))
                    TextButton(onClick = vm::stopLinxVoice) { Text("停止本次语音") }
            }
            if (linx.transcript.isNotBlank()) {
                Text("我说的", style = MaterialTheme.typography.labelLarge)
                Text(linx.transcript, modifier = Modifier.testTag("linx-transcript"))
            }
            if (linx.reply.isNotBlank()) {
                Text("灵矽 AI 回复", style = MaterialTheme.typography.labelLarge)
                Text(linx.reply, modifier = Modifier.testTag("linx-reply"))
            }
            Text(
                "这里的语音只用于与灵矽助手交流，不发布到广场。公开发言仍须另行录音和确认。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (linx.format.isNotBlank())
                Text("已验证本轮连接：${linx.format}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
