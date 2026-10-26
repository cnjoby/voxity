# 0.3.0：单次语音上行、流式播放与 Opus

当前状态见[主架构（2026-10-27）](../design/module-specs.md)：Android0.3.6、生产040；手机托管语音已核验放行。031的音频格式、OpenAPI与隔离curl验证见[接口核对](opus-interface-20261026.md)；下文保留030实现、门禁及复审的历史快照，不作为当前运行状态。

日期：2026-10-26，Asia/Shanghai。当前源码与候选 APK：Android **0.3.0/code11**。基于[低延迟架构方案](../design/low-latency-architecture-20261026.md)落实语音关键路径；按用户要求只维护最新版，不提供旧客户端的 WAV 下载转换分支。

2026-10-26复审后：服务端已部署`20261026-low-latency-opus-030`，软件检查更新为256项后端、60项JVM和20项模拟器测试。平台预生成回复核验名单为空，尚未放行；[修复和部署记录](review-deploy-opus-20261026.md)为最新状态。

## 已实现的链路

```text
说话开始 → 单个 Linx WSS 握手
每个采音帧 → 本地增量控制识别 + Opus 编码/实时上行 + 本地原声缓冲
分段结束 → 收敛 STT 候选 → 一次 Sonar HTTP 业务提交（Ogg Opus）
  private_chat → 接续同次 Linx 回复 → 增量解码 → AudioTrack 流式出声
  其他业务分支 → 中止/丢弃 Linx 回复 → 按业务结果回应或播放真人原声
```

- `CapturingTurn` 管理一轮的帧队列、控制识别、WSS 与业务回调。真人录音只向 Linx 识别一次；不会在 `private_chat` 分支再传第二遍录音。
- `SpeechSegments` 将同一组含预帧、尾静音的 PCM 分发给本地识别、云识别和原声缓冲。Vosk在录音期间增量处理；控制结果确定前不提交业务。
- 仍保留每连接一轮、候选冲突拒绝、TTS开始边界或1.2秒候选收敛窗口；没有把STT改名为供应商保证的final。原有1.2秒VAD尾静音保留，等待停顿/车内样本后再调参。
- `AudioCoordinator.stream` 在取得业务许可及音频焦点后才启动，约200ms起播缓冲；增量PCM/有状态Opus解码，不积攒完整回复WAV。`tts.stop`正常结束后等待播放排空；停止/安静/退出立即取消并清空待播数据。
- 回声保护接收流式参考帧，保留本机AEC与单路播放门禁。失去音频焦点同样中止本轮。
- WSS请求Opus 16kHz/20ms，实际参数遵循平台hello。上行按音频时间线发送，采音与传输重叠，没有假定平台支持无限速录后上传。

## 业务执行权与回复关系

服务端仅在`SONAR_MANAGED_VOICE_VERIFIED_AGENTS`明确包含本人实际映射的Agent时允许开启，默认空；未核验返回`VOICE_PLATFORM_NOT_VERIFIED`，不替换现有会话。最新App创建会话时声明 `client_managed_voice=true`，必须由服务端返回确认后才开启云语音。在该会话的有效期内，Sonar MCP的 `set_focus`、`queue_voice`、`set_task_state` 与 `agent_turn` 拒绝执行，返回 `CLIENT_MANAGED_VOICE`；只读查询仍可使用。暂停、结束后的迟到云端写请求也拒绝，避免与终端HTTP重复执行。它只约束本服务的MCP，不能禁用平台上其他服务的工具。

业务HTTP、幂等请求UUID、分项授权、用户隔离、事务提交前后复核和未知结果核对不变。提交期间不再长时间占用客户端位置更新锁。Linx OkHttp客户端复用连接池，但WSS轮次不复用。

“你帮我回复他说可以停车，可以过来”等礼貌前缀走明确回复路径；否定/引用/假设不视为动作。明确回复缺少有效已听对象时澄清，不改成新分享。录音开始时冻结 `captured_context_post_id`；如果提交时对象已变，返回 `CONTEXT_CHANGED`，录音保持私有。

## Opus 格式与体积

| 使用位置 | 格式 |
| --- | --- |
| 麦克风、本地Vosk、回声参考、播放 | PCM；本地录音缓冲为16kHz/单声道/16-bit |
| Linx WSS | hello协商的原始Opus包，优先20ms；不发送Ogg/WAV头 |
| 业务multipart上传 | `audio`字段，`voice.opus`，`Content-Type: audio/ogg`；标准Ogg Opus，目标24kbps、单声道 |
| 服务器持久化和下载 | 原样保存`.opus`，`GET /v1/audio/...`及聊天音频返回`audio/ogg`；不扩展存成WAV |

Opus是有损编码，保留真人录音来源，不能表述为PCM字节无损。现有WAV素材与合法WAV输入继续可读，但没有为旧客户端自动转换压缩下载；终端须根据实际格式解码。

HTTP文件编码使用Android MediaCodec及Ogg封装，编码采样率48kHz，以正确表达Ogg的48kHz granule/pre-skip；输入仍来自16kHz采音。为刷新编码尾部补一帧，再按原始样本数写结束granule，补帧不计入发言时长。处理Android封装器漏EOS或以包起点写granule的情况，依据实际Opus包时长重建页granule及CRC。

压缩字节按录音缓存并冻结，重试不改变服务器指纹。发送结果已处理或本轮结束时清理上传缓存；手动草稿在删除/确认后清理。上传前编码失败不会记录为已经发送。

服务端新增锁定依赖PyAV 16，用于有界解码校验；无需调用外部ffmpeg命令。校验文件大小、Ogg页顺序/CRC、单流/单声道Opus、EOS、granule时长及实际解码上限，拒绝截断、损坏、拼接流和超过30秒的内容。单文件1000KiB、multipart整体1MiB上限保留。

5秒合成音频样本经Android原生编码器生成：WAV **160,044字节 → Ogg Opus 22,503字节**，减少**85.94%**。Android与服务端分别解码均为160,044字节WAV，Ogg有效时长5.0秒；压缩字节重试保持一致。该样本不能代替中文车内音质验收，实际码率/容器开销会随音频变化。

## 计时与复跑

终端记录同一 `turn` 的采音开始、分段结束、最后VAD人声帧、连接、hello、上传结束、STT、业务路由、首音频、TTS结束和播放开始，并附单调时钟`at_ms`；服务端 `sonar.latency` 记录同一请求的校验、准备、计划、提交耗时。只关联ID，不直接相减终端和服务器墙钟，不记录语音正文或凭证。VAD帧时间与播放器启动时间是软件代理指标，实际声学起点另验。

```sh
cd server
uv sync --frozen
uv run pytest -q
uv run ruff check app tests
```

```sh
# 仓库根目录；Android 10/API29及以上
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' android/gradlew -p android testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest

# 仅对专用模拟器运行，不自动选中用户手机
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.sonar.app.LowLatencyAudioDeviceTest com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 验证状态与剩余验收

已完成以下验证，均为本轮重新运行：

| 层次 | 结果 |
| --- | --- |
| 服务端 | 256项pytest通过；Ruff检查与格式检查通过 |
| Android | 60项JVM测试通过（无跳过）；Lint、Debug APK及测试包构建通过 |
| API36专用模拟器 | 20项通过：5项低延迟/原生Opus、13项会话运行控制、2项界面状态回归，无跳过 |
| 编码交叉验证 | Android生成的实际Ogg字节经服务端CRC/时长/解码检查；两端解码均保留5秒样本长度 |
| 渲染检查 | 已查看模拟器“准备回复”和“合成语音/远程来源”截图，文案与来源呈现正常 |

候选APK：`output/sonar-0.3.0-low-latency-opus.apk`。SHA-256：`4e406a5df698f20a1747a81c98b848ff890baf572ad8e688a58d3536346de342`。复审/部署报告位于`output/review-deploy-opus-20261026/`；原始截图与合成音频位于忽略目录 `output/low-latency-opus-20261026/`。

复审后的服务端已部署，未修改平台配置或安装用户手机；平台预生成回复门禁未放行。真实Linx上行Opus协商、平台其他工具行为、移动网络、实体扬声器、中文音质、车内AEC和双端ESP32仍待实测。不能据此宣称私人会话已达到2.5秒目标。

SSE交付通知、后台scene筛选、独立STT/文本生成/TTS供应商适配器和VAD端点调参保留为后续优化；本轮交付关键路径与压缩格式，不改成多进程或微服务。

官方参考：[Linx WebSocket](https://linx.qiniu.com/docs/xrobot/platform/websocket)、[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)、[Android Ogg封装器](https://developer.android.com/reference/android/media/MediaMuxer.OutputFormat#MUXER_OUTPUT_OGG)。
