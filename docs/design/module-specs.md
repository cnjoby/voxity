# 声呐 Sonar｜当前实现架构、模块规格与分工

核对日期：2026-10-26。范围为本仓库当前源码：Android 0.2.3/code9，以及包含会话优先与天气修复的服务端。本文件描述已落在代码中的边界；[产品架构](product-architecture.md)中的事件系统、治理与商业试点规划不因此视为已交付。运行入口见[运行指南](../RUNNING.md)。

## 1. 部署与调用关系

```mermaid
flowchart TB
    U[用户] --> A[Android Compose 界面]
    A --> R[ExchangeService 语音会话]
    R <-->|WSS / STT 与私人助手语音| L[灵矽 Linx]
    R <-->|HTTPS / 原声、会话、任务与回执| H[FastAPI 单进程]
    E[ESP32：同事维护的独立固件] <-->|相同业务接口| H
    E <-->|设备语音能力| L
    L <-->|受保护 MCP / 可信设备映射| H
    H --> S[Service 与业务规则模块]
    S --> DB[(SQLite WAL)]
    S --> WAV[原声文件]
    S <-->|POI、天气、路线| G[高德]
    S <-->|语义意图与模型标记| B[Bamboo / StartLux]
```

自有服务是单机单进程模块化单体，不为每个用户启动独立 Agent 服务。`Service` 通过规则类组合业务能力；复用方法不意味着共享用户任务、授权或私人上下文。Caddy 负责公开入口，systemd 或备选 Compose 管理进程。手机录放、云端语音和业务 API 是可独立失败的链路。

## 2. 服务端模块规格

下表“负责角色”为交接所需的责任划分，具体成员姓名未在本项目中确认。

| 模块 / 源码 | 输入与输出 | 持久状态与关键约束 | 负责角色 / 验证入口 |
| --- | --- | --- | --- |
| HTTP 边界：`main.py`、`models.py`、`errors.py` | Bearer、JSON/multipart → 类型校验后的业务调用、结构化错误或音频 | 有界上传；不记录凭证或语音正文；身份由服务端解析 | 后端 / `test_core.py`、`test_audio_trace.py` |
| 统一契约：`contracts.py`、`message_views.py` | 旧消息、会话能力与动作 → v1 六要素消息和统一结果 | 保留来源与原声；未知字段不补造；HTTP/MCP 复用合同 | 架构与后端 / `test_contracts.py`、`test_agent_commands.py` |
| 服务编排与身份：`service.py` | 可信用户、配置及业务动作 → 任务、消息与交付 | 用户 Token 存摘要；Alice/Bob 分离；事务中重查权限、版本和时效 | 后端 / `test_core.py`、`test_async_storage.py` |
| 会话与原声轮次：`exchange.py` | 分项授权、会话 revision、转写及原声 → 私有回应、公开发言或控制结果 | 会话、轮次、幂等请求；过期/撤销/安静规则；模型异常不自动公开 | 后端 / `test_exchange.py`、`test_conversation_first.py` |
| 语音优先动作：`voice_first.py` | 明确控制语句、地点及模型候选 → 可执行计划和音频项 | 目标地点消歧；私人查询、问人、分享、回复与收听能力分别核验 | 后端与交互 / `test_voice_first.py` |
| 个人 Agent 与工具路由：`agent.py` | 当前轮次、私有上下文、天气日期/地点 → 工具来源、澄清或私人聊天 | “我想闲聊”进入本人 Agent；“找车友聊聊”进入真人匹配；缺少事实不伪造现场回答 | 后端 / `test_agent_network.py`、`test_information_tools.py`、`test_conversation_first.py` |
| 自愿真人聊天：`chat.py` | 双方意愿、参与者、会话及短语音 → 配对、消息、交付及退出 | 仅参与者访问；使用返回的 chat 音频 URL；任一方退出/安静均要复核 | 后端与终端 / `test_agent_network.py`；持续设备聊天另验 |
| 有效信息检索：`information.py` | 用户、POI、用途及查询 → 有权限且仍适用的公告/观察/知识 | 查询不公开私人输入、不制造到场；播放前可再次验证来源有效性 | 后端 / `test_retrieval_tasks.py`、`test_standing_notices.py` |
| 个人任务：`tasks.py` | 原请求 UUID、任务 ID、revision、明确动作 → 等待/回应/解决/取消状态 | 一人一份任务；有限邀请；未知结果用原 UUID 核对；听完不自动解决 | 后端 / `test_retrieval_tasks.py` |
| 地点与远程范围：`geo.py`、`remote_places.py`、`place_migration.py` | GPS 样本或明确远程 POI → 地点、资格、园区范围及工具结果 | GPS 与远程身份分开；坐标时效和精度校验；迁移保留历史 | 后端与定位 / `test_location.py`、`test_remote_places.py`、`test_place_migration.py`、`test_park_questions.py` |
| 声音与公告：`voices.py` | 录音、用途、频道授权及有效期 → 公开声音、替代/撤回、收听项 | 运营权限按频道核验；长期须知需明确 validity_policy；合成来源单独标记 | 后端与运营 / `test_voice_matching.py`、`test_standing_notices.py` |
| 注意力与模型：`decision.py`、`attention_worker.py` | 合法候选与本人关注 → 模型 notify/defer/ignore 标记 | 当前有效 question 邀请采用 conversation_first，模型不阻断；安静、资格、权限和期限仍是硬约束；scene 保留相关性筛选 | 后端与模型接入 / `test_attention_flow.py`、`test_decision.py`、`test_conversation_first.py` |
| 音频与持久化：`audio.py`、`storage.py`、`schema.sql` | 完整 WAV 与事务操作 → 不可变音频及一致数据库 | 16kHz/单声道/16-bit PCM、最长 30 秒、文件最多 1000 KiB，multipart 总体 1 MiB；SQLite WAL 与外键，新增迁移保留旧数据 | 后端 / `test_core.py`、`test_async_storage.py` |
| MCP：`mcp.py` | 独立 Bearer + 可信 Device/Agent/Session → 受限业务工具 | Streamable HTTP 与旧 SSE；每次核验身份和归属；无设备上下文不能操作个人状态 | 平台接入与后端 / `test_mcp.py`、[平台记录](../development/linx-platform.md) |
| 配置、导入与运维：`config.py`、`cli.py`、`operator_cli.py`、`demo_audio.py`、`poi_notices.py` | 本地配置、授权命令与合成素材清单 → 初始化、授权或幂等导入 | 凭证不入库明文、不输出；素材哈希核对；导入须显式执行 | 后端与发布 / `test_demo_audio.py`、`test_standing_notices.py` |

源码位于 `server/app/`，测试位于 `server/tests/`；文件均相对仓库根目录。完整字段以 [v1 Schema](../development/contracts/v1/schemas.json)、[契约说明](../development/contracts.md)及运行时 `/openapi.json` 为准。

## 3. Android 模块规格

源码目录为 `android/app/src/main/java/com/sonar/app/`。

| 模块 / 源码 | 职责与输入输出 | 状态、约束与负责角色 |
| --- | --- | --- |
| 界面：`MainActivity.kt`、`SonarScreen.kt`、`ExchangePanel.kt`、`VoicePanel.kt`、`LinxPanel.kt` | 准备、开始、活跃/安静状态、地点及设置 | 用户显式开始，不把配置过程混入行车交流；Android/体验 |
| 界面编排：`SonarViewModel.kt`、`Models.kt` | API/配置/定位结果 → UI 状态与用户动作 | 不把请求成功等同实际播放；Android |
| 前台语音运行：`ExchangeService.kt` | 采集轮次、STT、服务端动作、轮询交付 → 私有回应或原声播放 | 用户动作启动；通知/生命周期管理；非 sticky 重启；本地安静与终止优先；Android |
| 音频：`AudioCoordinator.kt`、`ExchangeCapture.kt`、`SpeechSegments.kt` | PCM、VAD 分句、音频焦点及 WAV → 有界录音或播放结果 | 录音/播放互斥、系统打断、单轮上限和静音边界；Android/音频 |
| 本地控制与回声：`LocalSpeechCommands.kt`、`PlaybackEchoGuard.kt` | 离线中文识别与播放上下文 → 控制动作或回声抑制 | Vosk 固定校验；网络失败仍需本地停止；真实车内 AEC/误触发单验；Android/音频 |
| 反馈与来源：`VoiceFeedback.kt`、`AgentSpeech.kt`、`SourcePresentation.kt` | 提交结果、Agent 文本、来源信息 → 回执提示、中文 TTS 和显示 | 真人回复保留原 WAV；Agent 使用合成语音；原声增益为播放派生，不覆盖服务器原文件；Android |
| 业务网络：`SonarApi.kt` | 业务 Token、冻结请求 ID/revision → HTTP 结果或已鉴权音频 | 不混用灵矽凭证；不凭旧版本播放；Android/后端共同维护契约 |
| 灵矽接入：`LinxGateway.kt`、`LinxSession.kt`、`LinxCodec.kt`、`LinxState.kt` | 设备绑定、WSS 和音频编解码 → STT/助手结果 | OTA/WSS 身份独立；重连与超时不自动公开发言；Android/平台接入 |
| 本地状态：`TaskStore.kt`、`AckReconciler.kt` | 私有设备配置、草稿、回执 → 加密配置和重试台账 | Keystore 保护配置；任务按服务器/用户范围隔离；未知结果核对、不盲目重发；Android |
| 定位：`PhoneLocation.kt` | 系统定位与精度 → 明确位置样本 | 权限、时效、真实位置/远程选择区分；Android/定位 |

JVM 规则测试位于 `src/test/`；Android runtime、界面和可选真实链路测试位于 `src/androidTest/`。测试资源与生产资源分开，旧 Mock 及内置地点演示素材已移除；服务端合成示例不赋予设备真实到场资格。

## 4. 接口与数据所有权

| 业务边界 | 主要接口 | 状态所有者与规则 |
| --- | --- | --- |
| 身份、位置、收听 | `GET /v1/me`、`PUT /v1/me/location`、`PUT /v1/me/remote-place`、`PUT /v1/me/listening` | 服务器核验本人；当前位置、远程地点与私人目标分开 |
| 会话、语音轮次 | `POST /v1/agent/sessions`、`POST /v1/exchange/sessions/{sid}/utterances`、`POST /v1/agent/sessions/{sid}/turns` | 授权属于本人会话；轮次保留请求 UUID、revision 和原声 |
| 信息、消息、任务 | `POST /v1/information`、`POST /v1/information/validate`、`GET /v1/messages/{id}`、`GET /v1/tasks`、`POST /v1/tasks/{id}/actions` | 公共来源按权限检索；个人等待和结束独立保存 |
| 结果核对 | `GET /v1/requests/{request_id}` | 原请求的结果不确定时先核对，不用新 UUID 重发 |
| 收件、音频、回执 | `GET /v1/exchange/sessions/{sid}/inbox`、`POST /v1/inbox/{did}/ack`、`GET /v1/audio/{pid}`、`GET /v1/chat/audio/{mid}` | 使用返回的音频 URL、delivery ID 与 request_id；播放前重查，听完才报告 |
| 手动发布与声音 | `POST /v1/posts/drafts`、`POST /v1/posts/{pid}/confirm`、`POST /v1/voices/{pid}/withdraw` | 手动草稿经 confirm 公开；自然会话采用独立明确授权；撤回/到期不能继续自动播放 |

消息六要素包含时间、地点、主体、事件、语音正文、状态。当前通过消息投影和契约适配提供这些信息；它不表示已经建成完整事件图谱。私人助手对话、个人任务、会话授权和每位收件人的 Delivery 相互独立。`已发布`、`已收到`、`已听完`、`需求已解决` 是不同事实。

网络或上游调用放在可控计划阶段，数据库事务负责原子状态更新；外部结果落地时再次核验会话有效性、任务版本和权限。服务重启后重新获取短期候选，持久化任务和回执用于核对，不能从旧内存快照继续执行。

## 5. 跨模块分工与交接

| 负责角色 | 交付物 | 对接方 / 验收责任 |
| --- | --- | --- |
| 产品与架构 | 产品定义、消息/动作/授权合同、当前与规划边界、验收场景 | 后端、Android、固件共同审阅合同；产品确认用户结果 |
| 后端 | HTTP/MCP、身份、状态规则、检索、任务、音频、模型/地图适配、数据迁移 | 给两端提供固定 Schema 与 fixture；完成隔离测试和部署证据 |
| Android | 可构建源码、APK、配置保护、语音运行与回执、设备诊断 | 与后端核对请求/交付；设备使用者确认实际听到与可控性 |
| ESP32/硬件同事 | 独立固件源码、板卡/音频参数、构建烧录命令、版本和设备日志 | 使用[当前交接书](../development/esp32-ai-handoff.md)；同事更新原仓库，本项目通过[固件子模块](../../firmware/README.md)固定版本 |
| 平台与模型接入 | 灵矽设备绑定、MCP 身份、真实 STT/TTS 与模型配置 | 凭证私下配置；平台成功不等于产品原声播放验收 |
| 发布与运维 | 版本清单、配置/数据备份、单进程服务、代理、健康检查、回滚 | 代码检查、部署核对与实机结果分别记录；不以历史发布状态代表当前在线状态 |
| 测试与参与者 | 隔离软件验证、双人原声、真实位置、断网/静音/重启、车内声学 | 记录环境、revision、持久状态、实际结果；真人与设备分别签收 |

## 6. 当前交付与已知边界

- 当前上传包含全部现有 Android/服务端应用源码、测试、脚本、依赖锁定、部署模板、协议与产品/工程文档，以及最新现有 PPT。
- ESP32 固件由同事更新；`firmware/esp32` 以子模块引用原私有仓库的 `feature/sonar-voice-client` 分支，获取源码需要对应权限；[模块与构建/更新方法](../../firmware/README.md)单列，固件应用与后端兼容性仍须联调。外部灵矽、高德、Bamboo 服务的实现源码不属于本仓库。
- 本轮只做源码交付与软件验证，不重新部署或触碰设备。此前生产/手机证据按日期保留于[会话优先修复记录](../development/conversation-first-20261026.md)；不能替代上传时重新验收。
- 长连接、实体喇叭、双向持续真人聊天、锁屏和车内声学仍按实际终端验收；多进程扩容、实时多人对讲、完整事件治理和商业试点不在当前实现范围内。
