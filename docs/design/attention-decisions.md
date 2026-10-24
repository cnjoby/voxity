# 声呐｜个人声音筛选与提醒

日期：2026-10-24。状态：Bamboo/StartLux 已参与实际邀请筛选，本机服务已启用。真实地图、模型和 Android 双用户会话的模拟器闭环通过；真机、真人 STT 与实际扬声器仍待验收。

## 分工与流程

人发起问题、提供回答、确认公开。广场保存原声、转写、询问目标及时间，按权限和位置召回合法候选。个人 Agent 结合本人的关注，通过结构化决策判断是否主动提醒。

```text
问题公开 / 用户到达附近
  → 位置、精度、时效、身份、互助意愿、静音检查
  → 本人的候选邀请（尚不可提示）
  → 后台 Bamboo 结构化决策：notify / defer / ignore
  → 重新核对配置版本、权限、位置与任务时效
  → notify：进入本人的 inbox，客户端出声前再复核
  → 人自愿录音回答并确认
  → 回答者原声交付给提问者 → 播放完成回执
```

本轮决策用于主动回应邀请。用户主动提问后期待的回答原声直接进入待听状态，不增加一次兴趣筛选；安静时仍保留，恢复后播放。模型不代替人回答、确认公开或执行车辆控制。

Android 首页仍以当前任务为主；“我的 → 我关心什么”保存个人关注。静音只修改 mode，保留 focus。服务端对旧 Android 把“现在安静”写进 focus 的已知旧值做一次性修复，保留 mode、互助意愿及其他关注；完成后不在每次启动重写用户偏好。

## 与自然语义交互的分工

本文件记录的已实现任务仅为邀请提醒 attention（notify/defer/ignore）。新的[车用自然交流代理](ux-designs/ux-sonar-2026-10-24/EXPERIENCE.md)设计允许模型结合当前轮次和系统问话，识别用户“发吧/就这样发/先别发”等意图；它不是模型代替用户同意。最新交互允许在本次已授权现场会话中自动分享和自动收听，正常路径不逐条确认或提示；审阅模式才使用自然确认。该自动动作/voice_intent任务及可信会话授权尚待实现，不能把现有提醒模型的结果、缓存或阈值用于发布授权。可复用推理适配，个人状态及业务门禁继续隔离。

## 决策输入与约束

采用原生 `POST /v1/decisions`，固定模型 `startlux-decision-27b-autothink`。输入为本人的 focus、候选转写、问题类型、已经确认公开的询问目标名称及 nearby/simulated 范围。位置、时效、静音和意愿先由确定规则检查；“这里”借助目标地点理解。模型不接收作者原始坐标、定位精度、Token、音频或其他用户的私有关注。

模型只返回 notify/defer/ignore，不接收任意工具调用或内容生成。空选项、none_probability ≥0.2、confidence <0.7 返回 defer；未知选项、畸形响应、鉴权失败和上游故障也返回 defer。阈值是应用起始设置，仍需更多场景评估；模型置信度不等于真实场景准确率。

使用 `think_mode=never`。它仍是训练后的模型判断；AutoThink 支持在不确定时生成推理，不能将本方案描述为传统决策树或完全不使用大模型。语音转写仍是独立能力。

## 后台执行与缓存

`AttentionWorker` 在 FastAPI lifespan 中启动单个后台任务。它按已通过硬规则的有效候选取快照，在数据库事务外异步调用模型；不在 confirm、inbox 等 HTTP 请求内等待推理。空闲时每 250ms 检查本地候选，不调用外部模型。自有服务继续单机、单应用进程，不新增队列服务。

默认总决策等待上限 4.5 秒，HTTP 自身超时 4 秒；取消会关闭异步请求。超时不阻塞其他用户设置、录音发布或原声收取，保存本次 defer，不在每次轮询重试。后台任务关闭时取消未完成请求；未落库结果可在重启且候选仍有效时重新评估。

每个 delivery 保存 attention_json：动作、原因、置信度、模型/策略版本、评估时间和输入摘要。缓存隔离到收件人、消息、个人关注、公开目标及策略版本。已落库的 notify/ignore/defer 在本次邀请有效期内复用；defer 不自动反复推理。旧候选随邀请过期，下一条问题重新评估。

响应落库前检查 recipient、post、request_id、profile_revision、输入摘要及当前硬规则；过期、移动失效、静音、改关注或取消导致迟到结果丢弃，不能重建已取消邀请。同一消息内容不因模型重试变成重复发言。

只开放已批准的邀请；直接请求原问音频、通过 MCP queue_voice 排队、预先上传回答或伪造 alerted 回执均不能绕过批准。客户端在播前仍做时效、版本、静音检查。prompted 邀请保留自愿回应入口，但不重复自动播提示。

## 配置与接口

仅服务端私有环境：

```dotenv
SONAR_DECISION_ENABLED=true
SONAR_BAMBOO_API_KEY=填写Bamboo推理Key
SONAR_BAMBOO_BASE_URL=https://api.bamboonode.dev/v1
SONAR_DECISION_TIMEOUT=4.5
```

为兼容旧演示环境，配置默认 decision_enabled=false；本机已显式开启。启用后缺 Key 或上游失败只会暂缓，不自动退回“全部提醒”。单机容器沿用 .env；不得启动多个应用 worker，否则可能重复推理。

设备和 ESP32 不连接 Bamboo、不持有 Key。既有 HTTP 状态机保留，已批准的 invitation.payload 增加：

```json
{"attention":{"action":"notify","provider":"bamboo","model":"startlux-decision-27b-autothink"}}
```

未批准邀请不进入 inbox；通过私有任务ID取预检时 actionable=false、reason_code=ATTENTION_NOT_APPROVED。该信息不等同于任务已取消或已提示。自己的回答交付保持原协议。更改个人关注仍使用 `PUT /v1/me/context`，省略 presence，不刷新物理位置。

## 验证

- 服务端单元及 HTTP 检查覆盖：正向完整问答、ignore/defer 缓存和重启、推理超时、数据库不被推理锁住、迟到结果、真实坐标移动、静音/关闭互助/改关注/任务过期、多个收件人的关注隔离、音频及回执不能绕过批准、后台任务启动和取消、旧 focus 一次性修复。
- 真实 API 与模拟器：`uv run --project server python android/tools/run_nearby_flow.py --serial emulator-5554 --two-clients --decisions`。脚本先验证无关冰淇淋话题被忽略、静音不生成模型候选，再验证两个 Android 用户会话的录音提问、Bamboo notify、提示后录音回应、提问者播放与回执。隔离库与临时凭证不改变正常服务数据。
- 报告：`android/build/reports/decision-flow/` 中的 instrumentation.txt、decisions.json、diagnostics.json 和实际查看的 invitation/answered/preferences 截图。坐标是明确注入的，文字及确认由测试输入，AudioRecord 无真人发言。

一次联调曾因模型缺少“这里”所指的目标而返回低置信度 defer；补充公开目标上下文后，保留原阈值完成正向及反例验证。这证明接入路径，不能替代语义质量或驾驶体验评估。

## 依据

- [Bamboo StartLux 接入文档](https://bamboonode.dev/docs/ai-model/decisions/startlux)：原生协议、类型、思考模式及 none 检测。
- [官方模型说明](https://huggingface.co/startlux-models/StartLux-Decision-v1.1-27B-AutoThink)：模型机制与局限。
