# 声呐 Sonar Android

Kotlin + Compose + OkHttp 客户端，默认连接真实 Sonar API；显式模拟模式和手动调试转写仅在 Debug 开放。ESP32 工程不在本目录，仍由硬件成员实现。

## 构建与安装

需要 Android SDK 35、Build Tools 35、JDK 17 或21。已固定 AGP 8.9.1、Kotlin 2.1.20、Gradle Wrapper 8.11.1（含官方分发校验和）。本机默认 java 为24，使用 Android Studio 自带 JDK21：

```sh
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug testDebugUnitTest lintDebug
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

其他环境请把 JAVA_HOME 指向其实际 JDK，并在 local.properties 填写 SDK 路径。SDK路径、运行数据和制品均不提交。可安装制品为 `app/build/outputs/apk/debug/app-debug.apk`。Release构建是未签名APK，不能当作可直接安装的交付包。

## 连接现有服务

先按 [服务端说明](../server/README.md)启动现有服务，不需要修改服务端契约。

在手机“连接设置”填写服务地址和 alice 的设备凭证；凭证运行时进入 Android Keystore 加密的私有配置，不进入源码、资源或 APK。不要使用 bob 的凭证冒充提问者。Linx 设备凭证与 Sonar 设备凭证分开。

本机调试可通过 USB/模拟器反向端口：

```sh
adb -s YOUR_SERIAL reverse tcp:8765 tcp:8765
python3 tools/configure_device.py --serial YOUR_SERIAL --debug-transcript
```

脚本仅为 Debug 应用，从环境 SONAR_ALICE_TOKEN 或同仓库 server/.env 读取 alice Token，以 stdin 写入应用私有的一次性 bootstrap；应用启动后加密保存并删除 bootstrap。凭证不出现在命令行、输出和 APK 中。脚本会停止并重启该 Debug 应用，不清除业务任务。默认地址 http://127.0.0.1:8765；模拟器也可用10.0.2.2。手机无法用127.0.0.1直接访问电脑，必须完成 reverse。

Debug HTTP只允许上述本机域名；其他地址及 Release 均要求 HTTPS。需要远程访问时填写实际部署的 HTTPS 地址。本轮没有公网地址。

`--debug-transcript` 明确启用手动转写，仅用于先验证业务链路；页面持续显示标识。去掉该参数则要求真实 Linx 配置和识别候选，不能在缺少转写时发布。

## 提问、等待与播放

1. 录音提问，再按一次结束，最长8秒；首次操作申请麦克风权限。
2. 核对本次录音和转写。Debug转写模式需手动输入；“听听原声”可试听。确认公开才上传并发布。
3. 客户端保存同一录音的请求ID、文件、冻结转写和服务器草稿ID；失败重试不重新生成发言。已开始上传的内容不能编辑，可丢弃后重录。
4. 任务卡查询等待、回答、取消或到期；回答保留时间与来源。等待时可以取消问题，也可以暂时安静。
5. 在线且允许收听时下载原声，实际出声前复核邀请/任务、request_id、revision及期限。完成播放后持久保存回执再上传；断连或进程重启不自动重播已经尝试过的交付，仍有效的待听可手动恢复。

安静立即在本机生效，同步服务器失败仍保持安静；恢复收听须服务端确认。静音保留自己的提问与待听回复。停止本次播放与取消提问是两个操作。模式变化造成请求版本轮换时，仅对同一交付与同一不可变音频补报已经完成的播放，不把旧回执套给新内容。

Android也提供邀请提示、拒绝和录音回应入口。只有当前有效邀请可进入回复，问题ID自动绑定；这部分尚未完成与ESP32的实测。

## Linx 接入边界

连接设置可填已绑定设备的 WSS 地址、Device ID 和设备Token，Client ID由应用生成并私有保存。代码参考[官方设备协议](https://linx.qiniu.com/docs/xrobot/platform/websocket)。

当前适配采用每条录音一个新会话，手动listen/start/stop，上传本轮PCM片段并取得待核对的STT候选；同一时刻只处理一条录音。只支持协商为16kHz单声道PCM，平台返回Opus会明确失败，不混传编码。没有“最终STT”协议保证，多个识别候选或超时不发布。候选仍需本人听原声确认。

**真实设备激活/注册、STT配对、Opus编解码及Linx TTS语音对话未验收。** 目前不播放平台TTS，收到TTS开始即中止；不能称为完整Linx语音助手。自然语言修改互助/安静也需后续实测，当前通过应用开关对接服务端真实个人配置。

## 检查与复测

```sh
# 在 android 目录；使用可用 JDK17/21
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# 需现有本机服务器、专用演示账号、无等待中的旧问题
python3 tools/run_live_flow.py --serial YOUR_SERIAL --synthetic-peer
```

最后一个脚本会安装Debug应用及测试包、注入私有配置、授权麦克风、启动本地HTTP测试回答者、执行两个设备检查并保存报告。它启用手动调试转写，回复是生成的静音WAV，确认与回执由测试模拟。模拟器的AudioRecord、真实HTTP及MediaPlayer完成回调经过验证，不证明真人STT、实际扬声器出声、道路体验或ESP32通过。请在静止环境和专用演示账号执行。

这里直接使用 adb instrumentation，而非 Gradle connected任务，是为了避免安装/卸载过程清除私有配置。报告：`build/reports/live-flow/instrumentation.txt`、`peer.txt`、`answered.png`；单元报告：`app/build/reports/tests/testDebugUnitTest/`；Lint：`app/build/reports/lint-results-debug.html`。完整当前结果见 [HANDOFF](HANDOFF.md)。

核心文件：SonarApi（业务HTTP）、SonarViewModel（任务流程）、AudioCoordinator（录放互斥）、TaskStore（私有恢复和回执）、LinxSession（待实测的语音识别适配）。字段遵守[三端契约](../docs/development/contracts.md)，不另设广场筛选逻辑。
