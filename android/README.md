# 声呐 Sonar Android

2026-10-25 发布审查：[自然交流 v0.1.1 审查与回归](../docs/development/review-20261025.md)。真实手机与车内验收边界仍保留。

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

App 默认服务地址为 `https://122.195.131.106`。覆盖安装后首次启动会一次性把旧配置中的
`http://127.0.0.1:8765`、`http://localhost:8765`、`http://10.0.2.2:8765`（可带末尾 `/`）
迁到该地址，并加密保存；Token、灵矽绑定和其他配置保留。自定义服务器、模拟模式及显式 Debug bootstrap
地址保留，迁移后手动改回本地调试地址也不会被再次覆盖。旧任务缓存仍按原服务器隔离保存，不自动向新服务重提交草稿或回执。

在手机“连接设置”填写 alice 的设备凭证；凭证运行时进入 Android Keystore 加密的私有配置，不进入源码、资源或 APK。不要使用 bob 的凭证冒充提问者。Linx 设备凭证与 Sonar 设备凭证分开。

本机调试可通过 USB/模拟器反向端口：

```sh
adb -s YOUR_SERIAL reverse tcp:8765 tcp:8765
python3 tools/configure_device.py --serial YOUR_SERIAL --debug-transcript
```

脚本仅为 Debug 应用，从环境 SONAR_ALICE_TOKEN 或同仓库 server/.env 读取 alice Token，以 stdin 写入应用私有的一次性 bootstrap；应用启动后加密保存并删除 bootstrap。凭证不出现在命令行、输出和 APK 中。脚本会停止并重启该 Debug 应用，不清除业务任务。默认地址 http://127.0.0.1:8765；模拟器也可用10.0.2.2。手机无法用127.0.0.1直接访问电脑，必须完成 reverse。

Debug HTTP只允许上述本机域名；其他地址及 Release 均要求 HTTPS。公网部署与服务端验证见[部署记录](../deploy/deployment-20261024.md)。

`--debug-transcript` 明确启用手动转写，仅用于先验证业务链路；页面持续显示标识。去掉该参数则要求真实 Linx 配置和识别候选，不能在缺少转写时发布。

## 提问、等待与播放

1. 录音提问，再按一次结束，最长8秒；首次操作申请麦克风权限。
2. 核对本次录音和转写。Debug转写模式需手动输入；“听听原声”可试听。确认公开才上传并发布。
3. 客户端保存同一录音的请求ID、文件、冻结转写和服务器草稿ID；失败重试不重新生成发言。已开始上传的内容不能编辑，可丢弃后重录。
4. 任务卡查询等待、回答、取消或到期；回答保留时间与来源。等待时可以取消问题，也可以暂时安静。
5. 在线且允许收听时下载原声，实际出声前复核邀请/任务、request_id、revision及期限。完成播放后持久保存回执再上传；断连或进程重启不自动重播已经尝试过的交付，仍有效的待听可手动恢复。

安静立即在本机生效，同步服务器失败仍保持安静；恢复收听须服务端确认。静音保留自己的提问与待听回复。停止本次播放与取消提问是两个操作。模式变化造成请求版本轮换时，仅对同一交付与同一不可变音频补报已经完成的播放，不把旧回执套给新内容。

Android也提供邀请提示、拒绝和录音回应入口。只有当前有效邀请可进入回复，问题ID自动绑定；这部分尚未完成与ESP32的实测。

## Linx 手机接入

“我的 → 灵矽语音”已提供 OTA 激活码、控制台绑定状态检查、连接配置加密保存，以及独立的录音识别/AI 回复播放测试。无需先配置 Sonar 就能测试语音。PCM 与 Opus 在本机模拟协议下已跑通；vivo 真机已绑定，内置录音的真实 STT/TTS 检查通过，用户也确认“试说一句”收到对话回复。另一次真人自动检查超时，保留为失败。完整步骤与分项证据见[Android 接入灵矽](../docs/development/linx-android.md)。

该页的测试不会发布到广场。首页公开录音仍须核对原声和识别候选；Debug 手动转写开关与 Linx 语音测试独立。后台唤醒、实时全双工及 Sonar 云端 MCP 尚未验收。

## 检查与复测

```sh
# 在 android 目录；使用可用 JDK17/21
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# 需现有本机服务器、专用演示账号、无等待中的旧问题
python3 tools/run_live_flow.py --serial YOUR_SERIAL --synthetic-peer
```

最后一个脚本会安装Debug应用及测试包、注入私有配置、授权麦克风、启动本地HTTP测试回答者、执行两个设备检查并保存报告。它启用手动调试转写，回复是生成的静音WAV，确认与回执由测试模拟。模拟器的AudioRecord、真实HTTP及MediaPlayer完成回调经过验证，不证明真人STT、实际扬声器出声、道路体验或ESP32通过。请在静止环境和专用演示账号执行。

这里直接使用 adb instrumentation，而非 Gradle connected任务，是为了避免安装/卸载过程清除私有配置。报告：`build/reports/live-flow/instrumentation.txt`、`peer.txt`、`answered.png`；单元报告：`app/build/reports/tests/testDebugUnitTest/`；Lint：`app/build/reports/lint-results-debug.html`。完整当前结果见 [HANDOFF](HANDOFF.md)。

核心文件：SonarApi（业务HTTP）、SonarViewModel（任务流程）、AudioCoordinator（录放互斥）、TaskStore（私有恢复和回执）、LinxGateway/LinxSession/LinxCodec（激活、语音协议与系统音频编解码，真实 PCM 语音已实测）。字段遵守[三端契约](../docs/development/contracts.md)，不另设广场筛选逻辑。

## 真实定位与 POI

正常 Debug 配置默认使用 Android 前台 GPS/网络定位。首次点击说话请求定位，地点解析后再点说话；点地点名可选附近 POI 或搜索远处目标。高德 Key 仅由服务端配置。定位粗略或拒绝授权时允许手选询问地点，不自动把用户标成现场。详见[定位契约与验证](../docs/development/location.md)。

`tools/configure_device.py --simulated-location` 仅供旧固定地点演示；默认不启用。双 Android 用户的隔离模拟器闭环：`uv run --project server python android/tools/run_nearby_flow.py --serial emulator-5554 --two-clients`，使用真实高德和注入坐标，包含双方录音、手动测试文字、提示后回应与原声播放。不能代替两台真机或真人 STT 验收。

## 个人 Agent 筛选

“我的 → 我关心什么”保存个人关注，静音与互助开关不会改写关注。服务端开启 Bamboo 时，邀请经过位置/时效/意愿规则、个人决策及再次复核后进入客户端。首页无需配置模型或 Key，原声回答继续按原任务收听。

真实模型完整检查在定位命令后增加 `--decisions`，报告写入 `build/reports/decision-flow/`；包含无关话题被忽略、静音没有候选，以及两个 Android 用户会话的提示和回应。仍使用注入坐标与手动测试文字，不能替代真机和真人转写验收。

## 三类声音扩展

本轮增加情境原声发布/浏览、POI 授权频道及版本公告、公开问答连续定向回复；静音、回复、运营频道、情境与互助各自按规则处理。接口、配置、授权命令、访问去重与复测见[声音匹配实现与验收](../docs/development/voice-matching.md)。实体到达、楼层与真人语音仍待验证。

## 张江测试音频与位置切换

首页「模拟位置」可直接选择三个张江测试地点，每处 12 条公告、路况与闲聊，随 APK 离线试听；「手机真实位置」退出测试并请求 GPS / 网络定位。模拟来源始终可见，不获得真实在场资格。素材、可选服务端导入和验收边界见[测试音频说明](../docs/development/demo-audio.md)。

测试素材现为用户确认的口语自然 TTS：三种表达分别用于提醒、路况和朋友闲聊。36 条均为正常语速的本地 WAV；页面保留合成测试标识。制作环境与版本升级见[测试音频说明](../docs/development/demo-audio.md)。
