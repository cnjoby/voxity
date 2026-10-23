# Sonar Android 交接

日期：2026-10-23。基线 revision：9054f5c，本轮 Android 源码尚未提交。ESP32 继续由硬件成员负责，本轮未修改其工程或协议。

## 当前能力

| 任务 | 已实现并验证 | 未完成或未验收 |
| --- | --- | --- |
| A1 | Kotlin/Compose工程、私有凭证配置、Sonar me连接、明确区分模拟接口与真实API、麦克风授权、模拟器安装 | 真实Linx激活/注册与语音往返，真实手机 |
| A2 | 模拟器AudioRecord生成16kHz单声道PCM WAV、八秒上限、草稿核对、本人确认入口、固定录音请求ID与草稿ID、真实HTTP上传发布 | 本轮文字由手动调试输入；真实STT与录音配对未验收 |
| A3 | 真实API问题等待/回答/超时/取消、来源与时间、下载及播放前复核、MediaPlayer完成回执；邀请/拒绝/录音回应代码已提供 | 回答来自本地HTTP测试程序的静音WAV，未与ESP32实测；邀请提醒/回应及扬声器需设备验收 |
| A4 | 本地立即安静、服务端确认恢复、个人互助开关、前台轮询、音频互斥、持久尝试和回执、模式变更后去重、进程恢复 | Linx自然语言修改意愿、平台TTS播放、网络故障全场景实机检查 |
| A5 | Debug可安装APK、Release未签名构建、10个单元检查、2个模拟器检查、Lint、截图审阅 | 真实手机麦克风/扬声器、权限拒绝、后台/网络切换及完整用户验收 |

A1–A5 各自包含尚待外部验收的事项，未整体标为完成；第一天 G1 的真实ESP32提示仍待联调。

## 制品与运行

- 可安装：`app/build/outputs/apk/debug/app-debug.apk`（约11MB）。SHA256：`d79e5035ed5bdfccc4a084f37cd8c7463084c85db7123d06d871a144822d4379`。制品运行时注入Token，不包含实际设备凭证。
- Release：`app/build/outputs/apk/release/app-release-unsigned.apk`，只验证构建，不是已签名发布或直接安装制品。
- 安装、私有配置注入和本机端口映射见 [README](README.md)。电脑本机服务为 http://127.0.0.1:8765，Android通过adb reverse连接，尚无公网地址。
- 工具：`tools/configure_device.py` 注入Debug配置；`tools/run_live_flow.py` 安装并测试；`tools/respond_once.py` 是显式标注的本地测试回答者，不是ESP32实现。

## 实测证据

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

下一步：在真实手机安装APK并注入alice凭证；与朋友的ESP32/bob按同一契约联调，替换本地测试回答者；最后接真实Linx并关闭手动调试转写，完成真人提问、真人回答与实际播放。公网部署仍沿用服务端部署计划。
