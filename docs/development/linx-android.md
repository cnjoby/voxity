# Android 接入灵矽：激活、识别与回复

日期：2026-10-24。范围：现有 Android App 作为 Linx 设备，前台手动录音，一条录音一个语音会话。用户要求继续完成手机接入；ESP32、后台唤醒和云端 Sonar MCP 分别验收。

## 当前证据与边界

- Android 已实现 OTA 激活码、绑定状态查询、动态 WSS 地址与设备凭证保存；Linx 凭证与 Sonar 凭证分开，不向 OTA 发送 Sonar Token。
- **真机已接通真实 Linx**：2026-10-24 在 vivo V2405A（Android 16 / API 36）覆盖安装当前 APK，保留原配置和公开草稿。官方 OTA 返回已绑定及连接凭证；已通过真实 WSS 发送内置录音，收到准确 STT 和 PCM 16kHz TTS（解码后 307200 字节）。该自动检查不使用麦克风、不播放回复、不发布声音。
- **真人体验通过（用户确认）**：用户明确确认在“我的 → 灵矽语音 → 试说一句”里，灵矽回复了自己说的话。另一次自动真人录音检查保存的结果为响应超时、无 STT、无播放完成记录；该失败单独保留，不能将用户确认写成自动检查通过。现有证据不说明超时原因，也不代表连续多轮或网络切换稳定性已验收。
- 本地模拟服务已通过 Android 麦克风录音、WSS 协议等价的本机 WS、PCM 和 Opus、MediaPlayer 播放完成、关闭出声、个人静音、停止录音及公开转写待确认的完整检查；Opus 使用模拟器系统 MediaCodec 实际编码/解码，并检查往返信号能量。
- 模拟服务明确返回“协议模拟识别/回复”，不能作为真实 Linx STT/TTS 证据。模拟器为 no-audio，播放器回调不证明实体扬声器音量或音质。真实手机 Opus、ESP32 和 Sonar 业务工具调用尚未验收。

本次使用 SDK Platform Tools ADB 36.0.2 无线连接；系统路径旧版 ADB 33.0.3 连接失败，切换 SDK 版本并重启 daemon 后成功。使用 `install -r`，未清除应用数据。已核对真机安装包 SHA-256 与当前本地 Debug APK 一致；局域网地址和端口会变化，复跑以当前设备为准。

## 手机怎么用

1. 安装 `android/app/build/outputs/apk/debug/app-debug.apk`。新安装不需要先填写 Sonar 服务器，就可打开“我的 → 灵矽语音”。
2. 点“获取激活码”。App 生成并保存本机 MAC 格式的设备标识及 Client UUID，向官方 OTA 上报，展示真实平台返回的码与控制台入口。
3. 登录 [灵矽智控台](https://xrobo.qiniu.com/)，把手机显示的码绑定到需要使用的声呐智能体。激活码可能过期，过期后在手机重新获取；模拟器与实体手机各有自己的设备码，不能混用。
4. 回到 App，点“我已绑定，检查连接”。待绑定设备仅在 `/ota/activate` 返回 HTTP 200 且文本为 `success` 后重新取 OTA；若 OTA 直接返回已绑定配置，则保存地址及 Token。凭证加密保存并清除激活码显示。绑定成功后仍需下一步语音实测。
5. 开启“允许播放灵矽回复”，点“试说一句”，说完点“结束录音”。等待识别与完整回复，界面分别显示“我说的”和“灵矽 AI 回复”。本测试不在广场发布声音；私有录音和回复缓存在本轮结束后删除。
6. 公开发言仍走首页“录音 → Linx 识别候选 → 人工核对原声和文字 → 确认公开”。若启用了手动调试转写，应关闭该选项，才能验证真实识别。

新生成的设备标识是 Android 应用范围标识和本地随机安装 ID 的 SHA-256 派生值，取前六字节并设为本地管理单播 MAC；不读取真实 Wi-Fi MAC、不上报 SSID/精确位置。设备标识与 Client UUID 保存在本机，同一次安装内稳定；清除应用数据或重装会产生新安装身份。已有手动配置的设备标识和 Client ID 保留。凭证仍进入 Android Keystore 加密配置，禁用备份和设备迁移；不进入 APK 或报告。

## 音频和发布边界

`LinxSession` 每次发送一条 16kHz/16-bit/单声道 WAV 的 PCM 数据部分，按服务端 hello 协商上传 PCM 或原始 Opus 帧；不会把 WAV 头当音频发送。session_id 可缺省，存在时校验消息归属。公开转写与私有聊天使用不同调用模式：

| 模式 | STT | TTS | 广场发布 |
| --- | --- | --- | --- |
| 公开录音的转写候选 | 必须是本轮唯一识别候选，多个不同片段失败 | 中止助手回复，不把 AI 声音混进真人原声 | 仅由用户核对后确认 |
| 我的 → 灵矽语音测试 | 显示本轮候选 | 收到未中断的 stop 后解码并播放，AI 来源明确 | 不创建草稿、问题或声音交付 |

协议未提供 STT final/业务 turn_id；单会话单录音防止跨录音串话，仍须人核对。纯转写若无 TTS 边界，在取得首条候选后使用 1.2 秒本地静默窗口收集冲突片段；这是实现的候选收敛策略，不是协议保证最终识别。

本版采用手动半双工、整轮缓冲回复。WSS 连上和上行音频发送后，私有测试最多等 45 秒，最多接收一分钟回复；录音最长 8 秒。PCM 依据协商采样率重采样；Opus 使用 MediaCodec（编码要求 Android 10+ 的相应编码器），按 RFC 6716 合并相同 TOC 的短帧，编码模式变化或尾帧保留较短包，按实际包时长发送。Opus 下行解码为 PCM，按解码器实际输出采样率构造 WAV 再由播放器播放。已实测 PCM 16kHz/20ms 和 Opus 24kHz/60ms 的本机协议；真实平台已验证 PCM 16kHz，真实平台 Opus 仍待验证。

录音、识别等待和播放期间保留统一音频门禁，广场提示排队等待；私有测试忙时不自动启动广场声音。个人静音和本地关闭回复出声均阻止 TTS 播放。取消、失去前台、改连接配置时中止会话并释放麦克风/播放器；不自动重发不确定是否处理过的录音。401/403 清除失效的私有测试缓存凭证，提示重新检查绑定。

未发布的公开草稿不占用音频设备，因此不阻止“试说一句”。私有录音使用独立缓存，不覆盖或丢弃原草稿；私有语音忙时也不能试听公开草稿。真机已检查按钮可用及原草稿保持不变。

目前不声明设备端 MCP 能力（`mcp=false`），因为尚未实现 initialize/tools/list/tools/call。现有 Sonar 云端 SSE MCP 保持独立，不把语音连接成功等同于业务工具已能调用。语音“现在安静”等是否真实修改 Sonar 配置，仍需要后续平台工具联调。

OTA 示例可能返回官方 `ws://` 地址，App 只对已知官方语音主机升级至 `wss://` 后发送凭证；拒绝跨站地址、重定向、URL 中的账号密码。明文 OTA/WS 仅在 Debug 的本机测试入口允许，Release 不接受测试 OTA 地址。OTA 的固件升级字段不触发下载、安装或执行。

## 验证与复跑

```sh
# 仓库根目录，使用 Android Studio 自带 JDK21 或本机 JDK17/21
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' android/gradlew -p android testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s YOUR_SERIAL install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_SERIAL install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 本机模拟 OTA/WS、系统音频编解码、录放互斥；不访问真实 Linx
adb -s YOUR_SERIAL shell am instrument -w -e linx_protocol true -e class com.sonar.app.LinxDeviceTest com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner

# 显式调用真实平台 OTA，不上传录音，不导出设备 Token
adb -s YOUR_SERIAL shell am instrument -w -e linx_live true -e class 'com.sonar.app.LinxDeviceTest#requestRealActivationWithoutPublishingOrSendingAudio' com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner

# 已绑定设备：真实 STT/TTS，使用内置录音，不启用麦克风或播放
adb -s YOUR_SERIAL shell am instrument -w -e linx_live_speech true -e class 'com.sonar.app.LinxDeviceTest#prerecordedAudioThroughRealLinxWithoutMicrophoneOrPublication' com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner

# 检查私人语音入口可用且保留旧公开草稿，不录音
adb -s YOUR_SERIAL shell am instrument -w -e linx_prepare true -e class 'com.sonar.app.LinxDeviceTest#boundPhoneVoiceEntryPreservesPendingPublicDraft' com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner

# 仅在人已准备好、媒体音量合适时运行：实际麦克风录音及允许出声的回复
adb -s YOUR_SERIAL shell am instrument -w -e linx_human true -e class 'com.sonar.app.LinxDeviceTest#humanMicrophoneThroughRealLinxPreservesPendingPublicDraft' com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner
```

本次 `LinxProtocolTest` 8 项 JVM 测试通过，连同现有 13 项，共 21 项；Lint、Debug APK 与测试包构建通过。`LinxDeviceTest` 按上述开关独立运行对应方法；真实平台和真人检查默认跳过。早期本机报告中的 `OK (3 tests)` 含跳过项，不能解读为三项真实平台检查。真机 OTA、内置录音真实 STT/TTS、保留草稿的入口检查各自 `OK (1 test)`；真人自动检查超时失败，用户随后明确确认私人语音收到了对话回复。公开录音也经本机 WS 实测取得识别候选，尚未确认时不会上传或公开；现有声音匹配模拟器完整流程回归通过，报告分开保存于 `android/build/reports/voice-matching/`。

本机协议报告与渲染截图在 `android/build/reports/linx/`。真机独立报告在 `android/build/reports/linx-phone/`：`live-activation.txt`、`live-speech.txt/json`、`phone-ready.txt/json`、`human-speech.txt/json`、`environment.json` 和截图；自动失败也保留。已查看真机绑定页与当前草稿页实际截图。激活码、设备标识与任何连接凭证不写入源码或长期文档；需要重跑时以当前 App 为准。

## 官方依据

- [OTA 网关](https://linx.qiniu.com/docs/xrobot/platform/OTA)：设备标识、激活码、绑定状态、动态 URL/Token 和 HTTP 200 业务错误。
- [WebSocket 协议](https://linx.qiniu.com/docs/xrobot/platform/websocket)：握手、音频参数、listen/start/stop、STT/TTS、abort 与 MCP 能力声明。
- [Android 媒体格式](https://developer.android.com/media/platform/supported-formats)、[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec#CSD)：系统 Opus 编解码及 CSD 格式。
- [RFC 6716](https://www.rfc-editor.org/rfc/rfc6716)：原始 Opus 包的 TOC、时长与帧封装。
