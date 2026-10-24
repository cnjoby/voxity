# Sonar Android 交接

2026-10-25 发布审查：[自然交流 v0.1.1 审查与回归](../docs/development/review-20261025.md)。真实手机与车内验收边界仍保留。

## 自然现场交流增量（2026-10-25）

本轮已实现独立会话授权、现场原声自动发布、真实 scene 根回复与相关原声自动播放，正常路径没有逐条公开确认或系统控制播报。手机使用单麦克风前台服务与本机扬声器；安静本地优先，打断后不自动重播。服务端独立决策、事务外模型调用及提交前版本/位置/关系复核。接口、失败边界、复跑方法见[自然交流实现](../docs/development/natural-exchange.md)。

本轮软件与隔离模拟器验证单独记录在该文档；已更新公网服务器；[部署记录](../deploy/deployment-20261025.md)包含数据和重启证据。本次未安装到实体手机，历史真机记录不代表本轮验收。缺少可用的本机离线中文识别时，断网语音指令不作完整能力承诺。ESP32 固件不在本轮修改范围。


当前手机接入状态（2026-10-24）：vivo V2405A 已绑定真实 Linx，内置录音的真实 STT/TTS 自动检查通过；用户明确确认“我的 → 灵矽语音 → 试说一句”收到对话回复。另一次真人自动检查响应超时，保留为失败，未宣称稳定性通过。旧公开草稿保留，业务 MCP 仍待联调。详见[手机接入记录](../docs/development/linx-android.md)。以下保留历次验证范围。

日期：2026-10-24。基线 revision：110516f，当前简化交互的修改尚未提交。ESP32 继续由硬件成员负责，本轮未修改其工程或协议。

## 本轮个人决策

服务端启用真实 Bamboo，Android 二级页面增加“我关心什么”，静音/互助切换保留关注。完整联调使用隔离库、真实高德与模型、两个 Android 用户会话：录音提问 → notify 后提示 → 录音回应 → 切回提问者播放 → 回执；设备检查 OK (2 tests)，并验证静音没有候选、无关话题被模型忽略。报告为 build/reports/decision-flow/，截图已实际查看；GPS 注入、手动转写及自动确认仍是测试输入。

构建、11 项 JVM 检查与 Lint 通过（0 errors / 14 warnings）。本机服务已开启后台筛选，正常设备仍经原 HTTP 接口，不持有模型 Key。新版 APK 尚未更新到实体手机，真人 STT、真机 GPS/扬声器及 ESP32 仍待验收。

## 本轮定位与附近问答

已实现 Android 前台 GPS/网络定位与权限、服务端真实高德坐标转换/道路及POI关联、询问目标选择和搜索、私有录音位置快照、附近邀请。首页按录音/确认/等待/回答切换，收到邀请优先显示回应卡。修复提示播完后邀请消失的问题，prompted 继续保留回应入口。老模拟协议保留，正常 Debug 默认真实定位；模拟器位置标记 android_mock。

本轮构建和 11 个 JVM 检查通过，Lint 0 errors / 14 warnings。SDK ADB 5038 的模拟器 `127.0.0.1:5555` 执行真实高德与两个 Android 用户会话：录音提问 → 位置候选 → 系统提示后保留回应 → 录音回答 → 切回提问者播放 → 回执，`OK (2 tests)`。坐标为显式注入，文字为手动测试输入，AudioRecord 无真人发言。报告与实际查看的截图：`build/reports/nearby-flow/{instrumentation.txt,peer.json,invitation.png,answered.png}`。

接口与复跑见[定位接入](../docs/development/location.md)。Bamboo 已接管主动邀请的个人筛选；客户端保留播前复核。本人期待的回答原声不重复筛选。

旧版此前已安装到 vivo V2405A；本轮无线调试不可达，新版未安装到实体手机。两台真机 GPS、真人 STT 与实际扬声器仍待验收；ESP32 继续由硬件成员负责。

## 当前能力

| 任务 | 已实现并验证 | 未完成或未验收 |
| --- | --- | --- |
| A1 | Kotlin/Compose工程、私有凭证配置、Sonar me连接、明确区分模拟接口与真实API、麦克风授权、模拟器安装 | 真实Linx激活/注册与语音往返；旧版手机已安装，本轮新版待安装 |
| A2 | 模拟器AudioRecord生成16kHz单声道PCM WAV、八秒上限、草稿核对、本人确认入口、固定录音请求ID与草稿ID、真实HTTP上传发布 | 本轮文字由手动调试输入；真实STT与录音配对未验收 |
| A3 | 真实API问题等待/回答/超时/取消、来源与时间、下载及播放前复核、MediaPlayer完成回执；邀请/拒绝/录音回应代码已提供 | 回答来自本地HTTP测试程序的静音WAV，未与ESP32实测；邀请提醒/回应及扬声器需设备验收 |
| A4 | 本地立即安静、服务端确认恢复、个人互助开关、前台轮询、音频互斥、持久尝试和回执、模式变更后去重、进程恢复 | Linx自然语言修改意愿、平台TTS播放、网络故障全场景实机检查 |
| A5 | Debug可安装APK、Release未签名构建、11个单元检查、2个模拟器检查、Lint、截图审阅 | 真实手机麦克风/扬声器、权限拒绝、后台/网络切换及完整用户验收 |

A1–A5 各自包含尚待外部验收的事项，未整体标为完成；第一天 G1 的真实ESP32提示仍待联调。

## 制品与运行

- 可安装：`app/build/outputs/apk/debug/app-debug.apk`（约11MB）。SHA256：`3e1d1e938b250c0dbc4e9a6738ebddbb01b62a8ad983642edf6cc862693937e4`。制品运行时注入Token，不包含实际设备凭证。
- Release：`app/build/outputs/apk/release/app-release-unsigned.apk`，只验证构建，不是已签名发布或直接安装制品。
- 安装、私有配置注入和本机端口映射见 [README](README.md)。电脑本机服务为 http://127.0.0.1:8765，Android通过adb reverse连接，尚无公网地址。
- 工具：`tools/configure_device.py` 注入Debug配置；`tools/run_live_flow.py` 安装并测试；`tools/respond_once.py` 是显式标注的本地测试回答者，不是ESP32实现。

## 原有实现验证记录（非本轮重新执行的全部检查）

环境：macOS/arm64；Android Studio JBR21.0.9；SDK/build-tools35；AGP8.9.1；Gradle8.11.1；Kotlin2.1.20。已安装Android Emulator37.2.12并创建 Sonar_API36；system image为Android36.1、google_apis_playstore、arm64，设备serial emulator-5554。

已执行：

1. `assembleDebug assembleDebugAndroidTest assembleRelease testDebugUnitTest lintDebug`：构建通过，单元检查 **10 tests / 0 failures / 0 errors**；包含共享契约解析、音频互斥、时效、WAV格式、问答元信息、HTTP鉴权/同请求重试、禁止跨站凭证与重定向、401、播放完成回执的版本轮换与不同内容拒绝。
2. Lint：**0 errors，14 warnings**。剩余主要是依赖版本/目标SDK更新建议和同步SharedPreferences commit的KTX写法建议；保持已验证的构建组合，持久化仍检查commit结果。
3. `python3 tools/run_live_flow.py --serial emulator-5554 --synthetic-peer`：最新完整运行 **OK (2 tests)**。模拟器录音→手动调试转写→确认发布→真实HTTP等待→本地测试回答→音频下载→MediaPlayer完成→played回执→安静开关；另验证草稿/请求ID/尝试记录/待同步静音恢复，以及不同用户作用域隔离。
4. 相同完整流程已成功复跑，第二次从安静状态恢复后再发新问题。最新问题 `p_d0fc4f19ec289e98b84a3834`，回答 `p_965e3413dbb466b5f5ebdbda`。测试模式与合成回答均明确标记。
5. 实际force-stop并重新启动应用，UI检查已回答任务、已播放状态与安静模式恢复；不以ViewModel重建替代进程检查。
6. 真实Token扫描未在APK任何成员或明文配置中出现；bootstrap启动后加密保存并删除。实际Token未写入文档、截图或命令行。
7. 首页和回答状态已截图并实际查看，见 `build/reports/visuals/home.png`、`build/reports/live-flow/answered.png`。具体设备报告为 `build/reports/live-flow/instrumentation.txt`、`peer.txt`。这些均为忽略的本机运行制品。

测试采用 headless、no-audio 模拟器，实际通过录音API、网络和播放完成回调；没有真人说话、真实STT或物理喇叭出声证据。测试确认动作由UI自动化模拟，不能替代比赛的真人问答验收。

## 接续边界

服务端契约保持一致，未为Android另设中心匹配或公共状态。人录制并确认内容，客户端管理音频与交互，个人筛选与任务归服务器。

LinxSession 只提供每录音新会话的PCM识别候选适配，没有真实凭证；Opus协商、OTA激活/注册和平台TTS未完成。多个STT候选、超时或关联不明时拒绝发布。自然语言修改互助模式还需接真实Linx，不能以应用开关成功宣称语音助手已通。

下一步：恢复无线调试连接，在真实手机更新APK并确认alice连接；与朋友的ESP32/bob按同一契约联调，替换本地测试回答者；最后接真实Linx并关闭手动调试转写，完成真人提问、真人回答与实际播放。公网部署仍沿用服务端部署计划。

### 2026-10-24 三类声音扩展

新增地点声音浏览与录音发布、公开定向连续回复、独立收听设置、频道播报开关、稍后/跳过/减少及按访问保存去重状态。13 项单元测试、Lint、Debug APK 与测试包构建通过；隔离模拟器完整流程通过，报告 `build/reports/voice-matching/`。测试使用注入位置、手动转写和合成回答，不能作为真人 STT、物理到达或扬声器验收。详见[实现与逐场景记录](../docs/development/voice-matching.md)。

### 2026-10-24 Linx 手机接入

新增 OTA 激活/绑定状态查询、稳定本机设备身份、动态 WSS 凭证保存、PCM/Opus、私有语音测试与 TTS 完成后播放。21 项 JVM 测试和本机 PCM/Opus 两个设备流程通过；另单独请求真实平台 OTA，已接受 sonar-android 注册并返回激活码。当前控制台未登录、设备待绑定；真实 Linx 识别/回复、实体麦克风/扬声器与业务 MCP 尚未验收。旧文“仅有识别适配/主动中止全部TTS”是历史状态；目前只在公开录音转写模式中中止 AI 回复。步骤与复跑见[手机接入记录](../docs/development/linx-android.md)。

### 2026-10-24 真机无线连接与真人确认

SDK ADB 36.0.2 无线连接 vivo V2405A（Android 16 / API36），覆盖安装且未清除数据。OTA 返回已绑定；真实平台准确识别内置录音并返回 PCM 16kHz 回复，自动检查通过。修复未发布公开草稿误阻止私人语音的入口判断，并阻止私人语音忙时试听公开草稿；原草稿保持不变，入口真机检查通过。安装包与当前构建 SHA-256 一致；21 项 JVM、Lint、APK 和测试包构建通过。

真人自动检查记录一次响应超时、无识别、无播放完成，保留失败记录；用户明确确认自己在“试说一句”里听到灵矽针对所说内容的回复，记为用户体验通过。两项证据分别记录，不以用户确认覆盖自动失败。真实 Opus、连续多轮稳定性、业务工具调用、ESP32 和完整公开问答仍待分别验收。报告 `build/reports/linx-phone/`，不含 Token。

## 2026-10-24 22:24 真机定位与测试音频补充

vivo V2405A 已覆盖安装张江音频新版及定位缓存修复版，设备 APK 摘要与交付包一致。真实定位样本约 4 秒、精度 30 米，服务端高德解析与页面正常；三类测试 WAV 均经内置扬声器路由自然完成。账号、灵矽身份及旧模拟草稿保留。修复 OEM 旧位置单调时间与采样时间不一致造成的旧缓存误采纳；22 项 Android 单元测试、Lint、构建、模拟器体验及一项最终真机检查通过。用户听感待确认。分项证据及保留的前次失败见[测试音频与位置切换](../docs/development/demo-audio.md)（以文档末节为准）。

## 2026-10-24 23:04 口语自然 TTS

按用户确认「这版可以，按这个方向」的样音风格，36 条测试素材已换成口语版 v3（Qwen3-TTS 本地生成，正常语速，无变速或截断），新版已覆盖安装 vivo。三类新音频在真机内置扬声器路由完成；真实定位仍通过。服务端已将本生成器旧版本撤回，目录每处 12 条，36 个 HTTP 音频摘要与清单一致。服务端 96 项、Android 22 项、Lint、构建及模拟器体验检查通过。详见[测试音频说明](../docs/development/demo-audio.md)末节；最新独立证据在忽略目录 `build/reports/natural-tts-phone/`。

## 2026-10-25 覆盖安装自动迁移服务地址

ConfigStore 首次读取旧版加密配置时，把三个既有本地 HTTP 8765 地址迁为
`https://122.195.131.106`，仅修改 baseUrl，原 Token、灵矽 Token、设备身份、客户端 ID
和其他设置保留。加密配置与一次性标记同一事务提交；后续手动修改地址、自定义服务器、
模拟模式及显式本地 Debug bootstrap 不会被再次覆盖。任务缓存仍按服务器隔离，旧缓存不删除，也不自动向新服务重提交草稿或回执。

24 项 JVM 测试、Lint、Debug APK 和测试 APK 构建通过。Android 16 / API36 只读模拟器上，
2 项实际 Android Keystore/SharedPreferences 测试通过，覆盖旧加密配置迁移、多次读取、
凭证与其他字段保留、旧任务缓存保留和显式调试地址保留。测试使用隔离偏好与测试凭证。
本轮没有连接的实体手机，未执行用户手机覆盖安装；交付包为 `app/build/outputs/apk/debug/app-debug.apk`。
