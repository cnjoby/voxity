# 声呐 Sonar｜ESP32 车用免提客户端开发交接书（独立版）

版本：2026-10-24 · v4（会话授权的自动交流）。交付对象：ESP32 硬件与固件开发同学。可将本文件单独转发并整体交给 AI 开发工具，无需先阅读其他项目文档。

**本轮目标：用户正常说现场情况，Agent在本次已授权的交流会话中自动发布；符合上下文的回复在合适空隙自动播放真人原声。系统平时不播控制话术，不逐条读回/确认，不逐条询问是否收听。App与ESP32均用自己的扬声器。**

本文包含产品约束、必要接口、样例、开发顺序、AI 提示词和验收表。它是现有接口的交接快照与新的免提设计要求，不代表已有免提实现。设备引脚、真实域名、Token 和绑定信息需按下表交接；未取得这些信息时可先开发协议模拟与状态机。

## 1. 做什么，由谁负责

Sonar 是一个真人声音问答产品：A 问某地点的情况，B 自愿提供现场原声，服务端组织问答和邀请，个人助手帮助管理关注与收听。助手不能编造现场答案或代替主人公开发言。

```text
用户声音 → ESP32 → Linx WSS：STT、私人助手、TTS
             │          └→ 云端 MCP → Sonar
             └→ Sonar HTTPS：本人配置、邀请、原声、草稿、确认、回执
```

| 负责人 | 本轮工作 |
| --- | --- |
| ESP32 同学 | 板级录放、联网、本地唤醒/停/静音、语音轮次、Linx 和 Sonar 两条通道、确认发布、恢复和固件交付 |
| Android 同学 | 相同语音词汇与业务状态、系统权限/音频焦点、手机扬声器、停车设置和状态镜像 |
| 服务端同学 | 提供 HTTPS 服务、本人凭证及映射；维护邀请/时效/原声；云端 MCP、个人筛选和部署 |
| 产品/联调负责人 | 确认板卡和交接参数、配对两端、组织静止环境体验验收 |

只接 Linx 可以做私人语音对话；声呐现场问答必须再接 Sonar。设备 Token 与 Linx Token 独立，云端 MCP 管理密钥不下发终端。ESP32 不实现广场匹配、大模型、地图服务或 Bamboo 决策接口。

新交互范围包含question/answer与现场scene观察；普通scene发起的通用回复线程仍需服务端扩展，不能把它硬塞进现有question关系。真实GPS、全城内容、运营公告自动发布、车机控制和双设备同车仲裁不在本轮自动开放范围。每个用户只启用一个主终端；不能让 App 和 ESP32 同时监听后假定天然去重。

## 2. 开工交接表

| 项目 | 谁填写 | 内容/默认值 |
| --- | --- | --- |
| 板卡 | 硬件 | 具体 ESP32 型号、板卡/硬件版本、原理图、外部工程路径 |
| 工具链 | 硬件 | SDK/依赖锁定版本、源码 revision、实际构建/烧录/回退命令 |
| 资源 | 硬件 | Flash/PSRAM、可用分区、最小空闲堆、最大可分配块 |
| 音频输入 | 硬件 | 麦克风/Codec 型号、供电、接口、引脚、有效位/槽宽/声道 |
| 音频输出 | 硬件 | 自带扬声器、Codec/功放、负载、时钟、使能脚、音量范围 |
| 声学链路 | 硬件 | 拾音距离、麦克风/喇叭布局、AEC 播放参考、降噪/VAD/唤醒模型的支持情况 |
| 交互 | 硬件 | 指示灯/屏幕、隐私关麦/静音实体键；不作为行车主流程依赖 |
| Wi-Fi | 联调 | 本机安全注入 SSID/密码；不提交源码 |
| Sonar | 服务端 | `SONAR_BASE_URL`（设备可达 HTTPS，无末尾斜杠）、`SONAR_DEVICE_TOKEN` |
| 身份 | 服务端 | Sonar 用户与逻辑 device_id；本机 Linx Device-Id/Client-Id/Agent 的对应关系 |
| 平台绑定 | 联调 | Linx 控制台登录/绑定负责人；设备激活码由设备真实请求取得 |
| 有效期 | 服务端 | 默认 question 120s、invitation 30s、模拟位置 600s、回答待听 86400s；以实际服务配置为准 |

默认演示角色：A=alice/Android，B=bob/ESP32。B 的模拟位置 `zhonghuan_jinke`，方向 `demo_exit`，参与互助且 `mode=listen`。A 可在 `demo_approach` 发问，目标仍是 `zhonghuan_jinke`。人所在地点和询问目标是两回事。

引脚、电压、功放/喇叭连接必须根据实际原理图和器件资料核对。没有板卡资料时 AI 不得猜接线；没有实物时不得声称已烧录/能出声。已有工程保留其框架；无工程再选所用板卡支持且已锁定的 SDK，不能随意追最新版。

## 3. 两端共用的自然交流约束

### 3.1 一次授权，正常路径不逐条确认

停车准备时开启本次现场交流，分别确认“允许自动发布本人现场语音”和“允许自动播放相关回复”，明确公开范围、地点/话题限制和有效会话。进入后不再每句话唤醒、不要求先说“我想提问”，也不自动读回/问“发出去吗”。用户可选择审阅模式，才逐条核对；少量必要澄清不是每条必经步骤。

这是新的产品授权模式，**现有assist_enabled、mode=listen或replies_enabled不能证明已授予自动公开权限**。服务端和客户端需新增可信授权快照、撤销和动作校验机制；本文不虚构已经上线的新字段或URL。完成之前，既有发布接口只能用于已符合现有授权的开发联调，不能靠客户端自行跳过确认当作新功能已完成。

同一会话内正常说话即可，VAD有界收束原声；仍要区分交流发言、私人助手、车内闲聊、乘客、广播和系统自播音。普通待命不持续上传全车对话；语音前导缓冲只保留在本地，并按本次采集授权决定哪些短片段进入STT。声源/意图不确定时静默忽略或私有暂存，不反复问是否发送。没有驾驶员身份识别时不宣称已具备。

### 3.2 用户自然说，Agent选择合法动作

```text
停车时：本次已开启现场信息自动分享、相关回复自动收听。
用户：金科路中环路下面堵死了。
Agent：（保存原声，判断现场分享意图及授权，合法则发布；不播控制声音。）
另一位参与者：（与当前交流有真实关系的原声）我刚过那儿，右边已经能走了。
Agent：（用户未说话且当前可播放时，直接播放这条真人原声。）
用户：我说的是下面那条辅路。
Agent：（先让路，依据真实上下文处理新发言，不播流程指挥。）
```

发布的内容仍来自真人原声，不由模型补写现场事实。发言回应唯一有效邀请时可为answer；没有明确问题/邀请关系时是scene/observation，不因提到路口就造reply_to。地点名称不自动证明作者物理在场，不补造车道、精确坐标、运营身份或其他人的权限。

语义决策根据本人的会话授权、当前用户语音、活跃问题/线程、地点与允许动作，选择question/answer/reply/scene/private/ignore/defer；执行器重新检查身份、轮次、内容指纹、授权版本、对象/邀请与时效后提交。模型高置信度不能替代缺少的授权或真实关系。

用户明显等待处理而关键地点/对象不明时，最多一句澄清；普通成功不说“请说问题”“确认公开”“已发送”“有人回复，要听吗”。发送结果、待听和错误留在状态镜像，或用户主动询问时回答。

### 3.3 符合上下文的原声自动接续

本人问题的有效回答、已建立会话中的定向回复优先；当前用户未说话、设备未采集、无电话/焦点等高优先级事件、本人允许自动收听、未静音、权限/期限/版本复核通过时，直接播放正文，不等“播放回答”。必要来源和时间可用简短前缀或元信息表达，不加控制问句。

其他同地点声音只能作为补充候选；模型判断相关不能创建不存在的回复关系。多个回复有界串行、尊重当前语境，不能把候选列表无脑连播。本人期待的明确回复不额外加无关的兴趣筛选。

用户开口时当前出声让路。说“停一下”停止当前播放；“稍后听”保留待听并在本机延后，不被下一次轮询自动重启；“安静”停止普通出声并暂停自动发布。完整排空才played，中止原因决定skipped或保留pending。回执失败/重启/完成未知不自动重播。

### 3.4 用户控制和可观察状态

| 自然说法/事件 | 动作 |
| --- | --- |
| 正常现场陈述 | 已授权且语义/对象明确则静默发布原声 |
| “帮我问问现在堵不堵” | 明确问题与目标后按授权发问；不强制先说提问口令 |
| “我说的是辅路/我再说一句” | 依据真实当前会话处理，不随意改发给新问题 |
| “刚才那句别发/先别发” | 未提交则终止；已提交则按真实结果撤回或反馈，不假装事务可取消 |
| “停一下” | 本地停当前出声/捕获，不取消已公开问题 |
| “稍后听” | 当前内容延后，保留待听，不自动重启 |
| “安静” | 本地停声、暂停自动发布，撤销尚未提交动作，再同步quiet |
| “恢复交流” | 明确恢复并同步状态后重新调度，不自动补发旧观察 |
| “关闭麦克风/结束交流” | 释放麦克风、结束会话，停车手动重开 |
| “刚才发出去了没” | 简短报告真实已发布/暂存/结果未知，不编造成功 |

本地停/安静/关麦必须在播放中和断网时有效；云端模型不是核心停止的前置条件。手机根据实际音频焦点/电话事件让路；ESP32没有另一台手机的事件时不承诺自动感知其导航或车辆警报。两端都用自身扬声器。

### 3.5 原有接口与新增能力的边界

已有：question/answer、scene观察、基于已回答问题的reply、草稿/confirm、原声/回执。待新增：会话级自动发布授权、自然连续发言来源/意图判断、静默自动执行与播放策略、普通scene起始的通用回复关系。当前reply要求root_question_id且原问题已answered，不能拿scene ID充当它。

可复用Bamboo/StartLux推理适配，但自动发言与自动播放是独立任务，不复用提醒attention的notify/defer/ignore状态/缓存/阈值。客户端仍不持有Bamboo Key。可信授权与语义动作接口由服务端负责人另行冻结；不要调用虚构的新URL或把本地授权字段塞进旧请求。

本地动作结果建议绑定utterance_id、turn_id、session授权版本、原声Hash、冻结转写、类型和目标/会话。它们是待实现内部状态，不是现有HTTP字段。模型超时、null/异常/过期、来源不明无自动发布；断网后过时的现场观察不自动补发。用户选审阅模式或范围变化需要授权时，才使用准确读回及自然同意，控制声音不成为主流程。

## 4. 固件模块、状态与资源

建议模块：`board_audio`（采集/转换/播放/排空）、`board_input`（按键/灯）、`speech_frontend`（AEC/降噪/VAD/唤醒/本地停与静音）、`linx_client`、`sonar_client`、`audio_coordinator`、`app_state`、`config_store/journal`。

业务状态：`SESSION_READY → CAPTURING → RECOGNIZING → DECIDING → AUTO_PUBLISHING/PRIVATE_DEFERRED → WAITING_REPLY/AUTO_PLAYING`；必要歧义有`CLARIFYING`，用户选审阅模式才进入`READBACK/AWAITING_CONFIRM`。独立保存授权和`QUIET/OFF/UNAVAILABLE`，不把它们直接传给现有接口。

麦克风由一个采集服务分发给唤醒/关键字和业务轮次，不开多个争抢设备的采集器。网络回调只投递带 turn_id/会话代数的事件；过期回调不能影响新轮次。一个协调器独占普通出声，业务录音时不播其他声音。收到 TTS stop 后仍需等解码、软件队列和 DMA/I2S 排空；取消要清空并阻止迟到回调重启播放。

Sonar 原声为 PCM WAV：16kHz、16-bit signed little-endian、单声道，0 < 时长 ≤ 8 秒、≤128000 帧、文件 ≤300 KiB。标准 8 秒文件是 256000 字节 PCM + 44 字节头；解析应按 RIFF chunk 找 data，不假设任何 WAV 永远只有 44 字节头。

不在普通堆里同时复制录音、multipart、下载和 TTS。根据实机 PSRAM/文件分区选择存储，上传流式分段；TTS 有界缓冲，溢出/欠载有明确错误路径。小型配置与日志用 NVS，录音不做高频 NVS blob；给出总容量、保留期限和空间不足反馈。NVS 本身不代表凭证已经加密。

配置建议：`SONAR_BASE_URL`、`SONAR_DEVICE_TOKEN`、`LINX_OTA_URL`、`LINX_DEVICE_ID`、`LINX_CLIENT_ID`，以及绑定后得到的 Linx WSS URL/Token。Device-Id 使用稳定 MAC 格式身份，Client-Id 是持久化 UUID v4；重启不重建 UUID。Sonar 逻辑 device_id 不能直接代入 Linx MAC。

## 5. Sonar HTTP 接口共同规则

- 本文以下路径均相对 `SONAR_BASE_URL`。除 `/healthz` 外统一 `Authorization: Bearer <SONAR_DEVICE_TOKEN>`。
- JSON 为 UTF-8、snake_case。JSON 请求设置 `Content-Type: application/json`；multipart 由实际 boundary 决定。
- 时间为 UTC RFC3339；ID 是不透明字符串，不能解析含义或硬编码示例值。路径 ID 由客户端库做安全编码。
- 请求不要添加自定义 `user_id`、author 或本地确认字段，服务端按 Token 授权；未知请求字段可能返回 422。响应兼容额外字段，不能因为有新字段整包失败。
- 示例时间/ID 仅示意，实际测试用当前服务返回值；模拟服务重算时间并生成新 ID，不关闭过期校验。
- `server_time` 是字符串；与 Linx OTA 的毫秒 timestamp 对象不同。用响应时间和本地单调时钟估算剩余期，保守计入网络往返；播放前仍请求服务器复核。无可信有效性就不自动出声。
- 每人一个未结束提问、一次一个待处理语音轮次；审阅模式才有确认窗口。终端不负责跨设备同步。
- 下文成功 JSON 为核心字段示例；扩展 payload/profile 可有更多字段。

### 5.1 接口总表

| 方法 | 路径 | 成功 | 何时调用 |
| --- | --- | --- | --- |
| GET | `/healthz` | 200 | 只验证服务基本存活，不证明身份/业务在线 |
| GET | `/v1/me` | 200 | 启动、恢复、配置冲突、语音设置后同步 |
| PUT | `/v1/me/context` | 200 | 更新意愿/模式；明确模拟场景时刷新模拟位置 |
| GET | `/v1/inbox` | 200 | 会话存活时约每秒查询，失败退避 |
| GET | `/v1/inbox/{delivery_id}` | 200 | 提示/播放/开始回答前复核 |
| POST | `/v1/inbox/{delivery_id}/ack` | 200 | 提示/播放真实完成、明确拒绝/跳过、失败 |
| POST | `/v1/posts/drafts` | 201；幂等重试200 | multipart 上传私有原声与冻结 metadata |
| POST | `/v1/posts/{post_id}/confirm` | 200 | 现有发布提交；新自动路径须先完成可信会话授权设计 |
| GET | `/v1/questions/{question_id}` | 200 | 只查询本人发起的问题，每秒查至终态 |
| POST | `/v1/questions/{question_id}/cancel` | 200 | 明确确认取消本人公开问题 |
| GET | `/v1/audio/{post_id}` | 200 二进制 | 带授权下载私人草稿或有访问关系的公开原声 |

### 5.2 GET /healthz 和 GET /v1/me

health 成功：`{"status":"ok"}`。me 响应：

```json
{
  "profile": {
    "user_id": "bob",
    "presence": {
      "place_id": "zhonghuan_jinke",
      "direction": "demo_exit",
      "source": "simulated",
      "updated_at": "2026-10-24T02:00:00Z"
    },
    "assist_enabled": true,
    "mode": "listen",
    "focus": "愿意回答附近路口的拥堵问题",
    "revision": 1,
    "listening": {
      "scene_enabled": false,
      "channel_enabled": true,
      "replies_enabled": true,
      "interests": [],
      "subscribed_places": [],
      "muted_topics": [],
      "muted_channels": []
    }
  },
  "device_id": "device_bob_demo",
  "linx_agent_id": "agent_bob_placeholder",
  "recent_question_ids": [],
  "server_time": "2026-10-24T02:00:00Z"
}
```

`recent_question_ids` 最多十个，用于恢复本人问题；Token 不会由 me 回传。mode=`listen|quiet`；revision 是配置版本，不是问题版本。读取/保留 focus 和 listening，不在重启时覆盖用户设置。`replies_enabled=false` 可能使已有回答不获自动交付，应告知实际状态，不擅自打开。

### 5.3 PUT /v1/me/context

停车时明确启用模拟现场的请求：

```json
{
  "expected_revision": 1,
  "presence": {"place_id":"zhonghuan_jinke","direction":"demo_exit","source":"simulated"},
  "assist_enabled": true,
  "mode": "listen",
  "focus": "愿意回答附近路口的拥堵问题"
}
```

返回 `{ "profile": <更新后的完整 profile>, "server_time": <UTC时间> }`，profile 结构与 5.2 一致。只有语义变化递增 revision；同值 presence 刷新时间，不重复取消任务。确认仍在模拟场景时每 60 秒刷新；退出或失去场景确认则停止刷新。

只修改模式的请求可省略 presence，避免伪造位置刷新：

```json
{
  "expected_revision": 1,
  "assist_enabled": true,
  "mode": "quiet",
  "focus": "愿意回答附近路口的拥堵问题"
}
```

expected_revision、assist_enabled、mode、focus 均要提供；值来自最新 profile，只改变用户要求的字段。focus 去空白后 1–1000 字符。不能把“安静”写进 focus 覆盖真实关注。409 REVISION_CONFLICT 时 GET me 后重新确认意图，不盲目覆盖。

本地“安静”先立刻生效，即使同步失败仍不出声；恢复收听必须同步成功。配置改变会取消旧邀请，并可能更换待听原声的 request_id/revision；旧播放指令不能套到新版本。真实位置接入本轮不实现，不把模拟位置当 GPS。

### 5.4 GET /v1/inbox：完整邀请样例

```json
{
  "items": [{
    "id": "d_invite_demo",
    "type": "invitation",
    "question_id": "p_question_demo",
    "request_id": "r_invite_demo",
    "profile_revision": 1,
    "state": "pending",
    "expires_at": "2026-10-24T02:00:30Z",
    "actionable": true,
    "reason_code": "ELIGIBLE",
    "payload": {
      "transcript": "现在中环路金科路口堵不堵？",
      "author_id": "alice",
      "author_label": "参与者 alice",
      "visibility": "public",
      "place_id": "zhonghuan_jinke",
      "place_name": "中环路金科路口",
      "location_scope": "simulated",
      "direction": "demo_exit",
      "prompt_key": "onsite_question",
      "question_audio_url": "/v1/audio/p_question_demo"
    }
  }],
  "server_time": "2026-10-24T02:00:00Z"
}
```

最多十项；空时 `items=[]`。当前服务会优先排序部分音频，客户端不应依赖“第一项一定是邀请”或固定创建顺序。`type=invitation` 的状态为 pending/prompted/answered/declined/cancelled/expired；pending 才可首次自动短提示，prompted 保留回应入口，不重复提示。

只处理可操作、未到期、版本匹配且未尝试的通知。开启个人决策时可有 `payload.attention`；`actionable=false`、`ATTENTION_NOT_APPROVED` 不能绕过。无邀请时排查位置/方向/TTL、互助/静音、问题状态和服务端决策，而非客户端造一条邀请。

`prompt_key=onsite_question` 对应本地打包素材。免提版建议录制中性短句“附近有人想问路况，要听吗？”及开始录音的滴声；系统素材明确为合成/系统提示。用户要听具体问题时再下载原问，不用固定文案冒充任意问题内容。素材由开发者生成并随固件打包，不能假定服务器提供公共下载链接。

### 5.5 audio 交付样例

以下是提问者收到回答的示例，使用该提问者 Token 查询；不要拿 bob Token 查询 alice 私人任务：

```json
{
  "id": "d_audio_demo",
  "type": "audio",
  "question_id": "p_question_demo",
  "request_id": "r_audio_demo",
  "profile_revision": 1,
  "state": "pending",
  "expires_at": "2026-10-25T02:00:20Z",
  "actionable": true,
  "reason_code": "ELIGIBLE",
  "payload": {
    "post_id": "p_answer_demo",
    "kind": "answer",
    "transcript": "好堵啊，下匝道都要十五分钟。",
    "author_label": "参与者 bob",
    "published_at": "2026-10-24T02:00:20Z",
    "audio_url": "/v1/audio/p_answer_demo"
  }
}
```

inbox 的 audio 状态为 pending/played/skipped/cancelled/expired。quiet 时可保留 pending 且 actionable=false，恢复时重新取得有效 request_id/revision。新目标行为是在本次授权与上下文匹配、设备空闲时直接播放回答，不再先提示等待口令；该策略仍待实现。未知 type、明确不支持的 kind 不自动播放；忽略额外扩展字段，不盲目 ack 为已播放。

### 5.6 GET /v1/inbox/{delivery_id}：实际动作前复核

返回 `{ "delivery": <5.4或5.5中的完整delivery>, "server_time": <UTC时间> }`，失效项也可能 HTTP 200，但 state 已终结且 actionable=false。404 表示不存在/无权访问，不允许用旧缓存出声。

下载后、真正出声前再检查：ID、request_id、profile_revision、payload 对应的内容与准备播放一致，actionable=true、未过期、本地不静音、没有高优先级任务。复核版本变化则丢弃准备中的播放，重新同步。开始回答前同样确认 invitation 仍指向原 question。

远端在最后复核后才取消，不能保证收回已经发出的声音；本地停/静音始终立即执行。录音忙碌时先排队，轮到时重新复核，过期不补播。

### 5.7 POST /v1/inbox/{delivery_id}/ack

```json
{"event":"alerted","request_id":"r_invite_demo"}
```

返回 `{ "delivery": <最新delivery>, "server_time": <UTC时间> }`。

| event | 仅适用 | 何时写入 | 结果 |
| --- | --- | --- | --- |
| alerted | invitation | 邀请短提示完整播完且缓冲排空 | prompted，保留回答入口 |
| declined | invitation | 本人明确“不回答” | declined |
| played | audio | 真人原声完整播放且排空 | played |
| skipped | audio | 本人“停一下”中止或明确跳过原声 | skipped，不取消提问 |
| failed | 两者 | 下载/播放失败 | 不冒充成功，本地本会话不自动重复播放 |

本轮不依赖扩展 received/seen 回执。重复相同回执幂等；request_id 变更返回 409 REQUEST_CONFLICT，非法转换 409 INVALID_TRANSITION。静音中止原声时不写 played/skipped，保留待听；“停一下”和“安静”的业务结果不同。

出声前持久化 `(delivery_id,request_id,attempted)`，完整完成后先记本地结果，再发 ack。回执失败只重试回执，不能重新播放。已尝试但完成结果不明的项重启后不自动重播，也不虚构成功回执；用户可语音请求仍有效的内容后手动恢复。

### 5.8 POST /v1/posts/drafts：原声与 metadata

`multipart/form-data` 必须有两部分：

| 名称 | 类型 | 内容 |
| --- | --- | --- |
| audio | 文件，filename=voice.wav，Content-Type=audio/wav | 本轮真实 PCM WAV 二进制 |
| metadata | UTF-8 文本字段 | 下列 JSON 序列化的字符串，不是音频 URL/base64 |

问题 metadata：

```json
{
  "client_request_id": "11111111-1111-4111-8111-111111111111",
  "kind": "question",
  "transcript": "现在中环路金科路口堵不堵？",
  "target_place_id": "zhonghuan_jinke",
  "direction": "demo_exit"
}
```

回答 metadata：

```json
{
  "client_request_id": "22222222-2222-4222-8222-222222222222",
  "kind": "answer",
  "transcript": "好堵啊，下匝道都要十五分钟。",
  "reply_to": "p_question_demo",
  "invitation_id": "d_invite_demo"
}
```

client_request_id 是客户端生成并持久化的 UUID；新录音用新 UUID，重试沿用。transcript 去空白后 1–1000 字符；metadata 文本最多 4096 字符。本轮只发以上字段。answer 不发 target_place_id/direction，而从关联问题继承；reply_to 必须与本人的 invitation 匹配。

multipart 原始结构（尖括号为说明，不是可发送的实际值）：

```http
POST /v1/posts/drafts HTTP/1.1
Authorization: Bearer <SONAR_DEVICE_TOKEN>
Content-Type: multipart/form-data; boundary=sonar-boundary
Content-Length: <整个body的实际字节数>

--sonar-boundary
Content-Disposition: form-data; name="metadata"

<metadata的UTF-8 JSON文本>
--sonar-boundary
Content-Disposition: form-data; name="audio"; filename="voice.wav"
Content-Type: audio/wav

<真实WAV二进制>
--sonar-boundary--
```

实际序列化行尾使用 CRLF，每段长度计入 UTF-8 字节；整个请求体当前上限 350 KiB，音频文件上限 300 KiB。处理网络部分写入，不能把音频多复制一份拼成大字符串。

首次成功 201，幂等重试 200，核心响应：

```json
{
  "post_id": "p_answer_demo",
  "status": "draft",
  "transcript": "好堵啊，下匝道都要十五分钟。",
  "audio_url": "/v1/audio/p_answer_demo",
  "server_time": "2026-10-24T02:00:18Z"
}
```

草稿仅作者可访问，上传不等于公开。相同 user+client_request_id、相同原声和 metadata 返回原对象；内容不同 409 IDEMPOTENCY_CONFLICT。草稿内容不覆盖修改；需重录则新建录音和 UUID。已经 published 的对象重试创建可能返回 status=published，应按已有发布结果恢复，不重复宣布新发布。

现有审阅流程为草稿→核对→单条确认→confirm。新自动流程为本次可信会话授权+本轮原声/意图/关系校验→草稿→发布提交；授权机制尚待服务端实现，不能只删掉UI确认就宣称支持自动代理。

### 5.8.1 现场观察scene：对应“下面堵死了”

同一multipart接口支持scene。目标须是服务端已注册的真实POI ID；旧模拟`zhonghuan_jinke`不能直接假定在places表中。由服务端负责人预先交接目标ID，或停车时通过`GET /v1/places?query=<地点关键词>`取得并缓存候选。该GET使用同一本人Token，query为2–100字符；成功返回`{places:[{id,label,latitude,longitude,kind}],server_time}`，地点歧义不在行车中读长列表。候选只说明询问/分享目标，不证明作者物理在场。

```json
{
  "client_request_id":"33333333-3333-4333-8333-333333333333",
  "kind":"scene",
  "transcript":"金科路中环路下面堵死了。",
  "target_place_id":"REPLACE_WITH_REGISTERED_PLACE_ID",
  "purpose":"observation",
  "visibility":"public",
  "recorded_at":"2026-10-24T02:00:00Z",
  "valid_from":"2026-10-24T02:00:00Z",
  "valid_until":"2026-10-24T02:05:00Z",
  "applicability":"unknown",
  "topics":["路况"]
}
```

所有示例ID/时间必须换成真实本轮值。即时观察要求valid_from/valid_until，区间不超过服务端SONAR_OBSERVATION_MAX_TTL（默认1小时）；5分钟仅为本次示例。原声仍最长8秒。applicability=unknown的现有实现只支持主动查询，不能写完这份metadata就宣称别人能自动收听；自动上下文交付还需新设计的可靠关系和策略。不得为了自动播报把不确定适用性改成unconditional。

scene草稿响应与5.8相同；现有发布成功响应包含post_id、status、server_time，普通scene的question_id和question_status为null；不按问题任务恢复。没有合法问题关系时不填reply_to/invitation_id，不调用question查询。普通scene作为线程起点的通用回复机制尚待实现。

### 5.9 POST /v1/posts/{post_id}/confirm

Content-Type=application/json，请求体：`{}`。现有实现按已确认发布流程使用。新自动代理须由可信会话授权和本轮执行器校验允许后提交；这属于待实现的授权机制，不是模型可任意调用此接口。旧API不接收本地会话/授权字段。

```json
{
  "post_id": "p_answer_demo",
  "status": "published",
  "question_id": "p_question_demo",
  "question_status": "answered",
  "server_time": "2026-10-24T02:00:20Z"
}
```

问题确认后 question_id=自己的 post_id、question_status=waiting；回答确认后指向 reply_to、question_status=answered。首条有效回答结束等待并生成原声交付。重复同一 post_id 确认返回已有结果，不重复发布。

服务端重新检查邀请、本人位置/意愿、有效期及问题状态，pending/prompted 的邀请均可能合法；已拒绝/取消/到期则不允许。409/410 时告诉用户“这次请求已结束，没有发出”，不把回答转换成另一问题。

邀请默认30秒，覆盖真实语音采集、识别及动作提交；审阅模式还需计入读回/确认。真实体验若超出窗口，服务端负责人应显式调整 `SONAR_INVITATION_TTL`，必要时配合 `SONAR_QUESTION_TTL`，并记录演示配置；固件不能私自延长 expires_at 或关掉检查。

### 5.10 GET /v1/questions/{question_id}

仅提问者可查；回答者从 invitation 获取问题，不越权查对方私人任务。等待中样例：

```json
{
  "question_id": "p_question_demo",
  "target_place": {"id":"zhonghuan_jinke","label":"中环路金科路口"},
  "status": "waiting",
  "expires_at": "2026-10-24T02:02:00Z",
  "answer": null,
  "invite_outcome": "prompted",
  "server_time": "2026-10-24T02:00:08Z"
}
```

status=`waiting|answered|cancelled|expired`。answered 时 answer 含 post_id、transcript、author_label、published_at、audio_url，结构与 5.5 的核心 payload 一致；可有更多扩展字段。invite_outcome=`none|pending|prompted|answered|declined|cancelled|expired`。

每秒查到终态后停止；answered 只是已有回答，随后从 inbox 处理 audio 交付，不能直接记 played。quiet 时仍可查询，但不播音；无答案且到期才说明“暂时无人回应”。拒绝或没有邀请不推断现场没人。

### 5.11 POST /v1/questions/{question_id}/cancel

请求 `{}`，仅提问者；成功响应与 5.10 相同，status=cancelled。重复取消成功；已有回答返回 409 QUESTION_ANSWERED，已过期返回 410 QUESTION_EXPIRED。取消撤销相关待处理邀请，不能用停播放误触此接口。

### 5.12 GET /v1/audio/{post_id}

带本机 Sonar Token 请求；成功 `Content-Type: audio/wav`、Content-Length 和 WAV 二进制。只需完整 GET，不要求 Range。验证真实 WAV 参数和长度，短读/过长/格式不符停止并报告失败。

payload 的 audio_url/question_audio_url 为同源相对路径，仍需授权；不向外域/重定向转发 Token，不把凭证拼入 URL。只知道 ID 不获得下载权限；过期邀请不再授权原问题音频，回答另按待听期限保留。

用户主动“再听一遍”时可以按既有权限 GET 同一已发布原声并复核可访问性；已 played/skipped 的交付不伪造为 pending，不再自动排队或宣称新 played。用户主动重听与后台交付回执应分开记录。

### 5.13 HTTP 错误与恢复

```json
{"error":{"code":"QUESTION_EXPIRED","message":"问题已过期","retryable":false}}
```

| HTTP | 含义/常见 code | 行为 |
| --- | --- | --- |
| 400 | 参数/请求错误 | 修请求，不盲目重试 |
| 401 | 本机业务凭证无效 | 停该通道重试，停车检查配置 |
| 403/404 | 无权限或不可见/不存在 | 不用缓存绕过，不泄露其他用户内容 |
| 409 | REVISION_CONFLICT/REQUEST_CONFLICT/INVALID_TRANSITION/IDEMPOTENCY_CONFLICT/QUESTION_ACTIVE 等 | 区分配置、回执或内容冲突；重新同步，不换 ID 偷偷重发 |
| 410 | 任务或邀请过期 | 停止该轮，告知未发出/已结束 |
| 413 | AUDIO_TOO_LARGE/BODY_TOO_LARGE | 修长度；不重试同一超大文件 |
| 415 | INVALID_AUDIO | 重录/修 WAV，不把 Opus 改扩展名 |
| 422 | INVALID_METADATA/VALIDATION_ERROR | 修字段/转写；不要求驾驶者键盘补字 |
| 503 | 外部能力暂不可用 | 依据 retryable 和实际状态处理 |

仅网络错误或 retryable=true 自动重试，1/2/4 秒后报告失败；轮询退避上限 10 秒，恢复先同步。所有错误都有本地可观察状态，不在车内不断播报错误码或连接细节。

上传响应丢失：同一音频/metadata/client_request_id 重试。confirm 响应丢失：若持久记录证明该草稿已有合法提交授权，重试同一 post_id；结果未知先记录“待核对”，用户主动询问或确需处理时再反馈，不逐条播控制提示。没有有效授权的草稿重启后不能自动公开；旧会话授权不自动恢复发言队列。ack 响应丢失只重试同一回执，不重放声音。

## 6. Linx 注册、WSS 和语音协议

以下按官方 OTA/WSS 协议及已接入客户端路径整理，ESP32 仍需独立实测。使用 HTTPS/WSS 与证书校验；联网后先建立正确时间。Sonar Token 不发送到 Linx。未知平台返回字段可忽略，但错误体和音频参数必须校验。

### 6.1 注册/取连接配置

```http
POST https://xrobo.qiniuapi.com/v1/ota/
Activation-Version: 1
Device-Id: <本机稳定MAC格式ID>
Client-Id: <持久化UUID-v4>
User-Agent: sonar-esp32/0.1.0
Content-Type: application/json
Accept-Language: zh-CN
```

无有效 eFuse 序列号时用 Activation-Version=1；已有序列号方案沿用实际规则，不伪造序列号。请求体模板：

```json
{
  "version": 0,
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "mac_address": "02:00:00:00:00:01",
  "application": {
    "name": "sonar-esp32",
    "version": "0.1.0",
    "elf_sha256": "REPLACE_WITH_ACTUAL_FIRMWARE_SHA256"
  },
  "board": {"type":"REPLACE_WITH_ACTUAL_BOARD_TYPE","ssid":"","rssi":0}
}
```

示例 MAC/UUID/板型/Hash 需替换为本机值；mac_address 与 Header Device-Id 完全一致，uuid 与 Client-Id 一致。board 的 SSID/RSSI 按实际平台要求与设备隐私配置填，不猜。不要频繁重建身份导致重复激活。

未绑定响应示例：

```json
{
  "activation": {
    "code": "123456",
    "message": "请到灵矽智控台绑定本机激活码",
    "challenge": "02:00:00:00:00:01"
  },
  "websocket": {"url":"wss://xrobo-io.qiniuapi.com/v1/ws/"}
}
```

真实码由服务器生成，示例码不可绑定。停车时在 https://xrobo.qiniu.com/ 由负责人输入本机激活码。激活检查：

```http
POST https://xrobo.qiniuapi.com/v1/ota/activate
Device-Id: <本机ID>
```

无请求体。202=尚未绑定；仅 HTTP 200 且去空白文本恰为 `success` 视为检查成功，再 POST OTA 获取连接凭证；200 JSON code=500 等不算绑定。OTA 上报本身也可能 HTTP 200 返回 error，必须检查。

已绑定响应示例：

```json
{
  "server_time": {"timestamp":1792807200000,"timezone":"Asia/Shanghai","timezone_offset":480},
  "websocket": {
    "url": "wss://xrobo-io.qiniuapi.com/v1/ws/",
    "token": "REPLACE_WITH_PLATFORM_RETURNED_DEVICE_TOKEN"
  }
}
```

timestamp 为 Unix 毫秒，不与 Sonar UTC 字符串混用。优先使用 OTA 动态地址，校验可信域名；已知官方 `ws://xrobo-io.qiniuapi.com/` 按本项目策略升级成 WSS，不能降级发送凭证。安全连接不成立就报错。只取注册和连接配置，不自动下载/刷入返回 firmware 字段中的固件。

### 6.2 WSS Header 与 hello

WSS 握手携带：

```http
Authorization: Bearer <Linx设备Token>
Device-Id: <与OTA一致的本机ID>
Client-Id: <与OTA一致的UUID>
Protocol-Version: 1
```

连接建立后发送：

```json
{
  "type": "hello",
  "version": 1,
  "features": {"mcp":false},
  "transport": "websocket",
  "audio_params": {
    "format": "pcm",
    "sample_rate": 16000,
    "channels": 1,
    "bit_depth": 16,
    "endianness": "little",
    "frame_duration": 20,
    "frame_size": 320,
    "sample_format": "signed_int16",
    "play_buffer_duration": 1000
  }
}
```

play_buffer_duration 应与实机容量相符，不能声明有不存在的缓冲。未实现端侧 MCP 时 mcp=false；已有可用端侧 MCP 可保留，不能仅为“接入”改成 true。

服务器 hello 示例：

```json
{
  "type": "hello",
  "transport": "websocket",
  "session_id": "session_demo",
  "audio_params": {
    "format":"pcm","sample_rate":16000,"channels":1,
    "bit_depth":16,"endianness":"little","frame_duration":20,
    "frame_size":320,"sample_format":"signed_int16","play_buffer_duration":1000
  }
}
```

等待正确 hello 的建议超时 10 秒。session_id 可缺省，存在则保存并关联后续控制消息。服务器可能返回 Opus/其他采样率，按实际参数编解码/重采样；没有支持能力就明确失败。PCM 是本轮优先实测路径，不保证服务器始终返回 PCM；不能把压缩字节当 PCM 播放。

### 6.3 上行录音与 STT

开始/结束分别发送：

```json
{"session_id":"session_demo","type":"listen","state":"start","mode":"manual"}
```

在两者之间发送本轮二进制音频帧：

```json
{"session_id":"session_demo","type":"listen","state":"stop","mode":"manual"}
```

`manual` 是平台显式 start/stop 控制模式，不表示用户必须按键；免提版由本地唤醒/VAD 驱动这两个事件。PCM 16kHz/20ms 每帧为 320 采样=640 字节，只发数据区，不带 WAV/RIFF 头。Opus 必须是真实协商的原始音频包，不是 Ogg/WAV 容器。按采样时长节奏发送。

本轮一条录音一个会话、一个在途业务轮次；WS 回调可能分片，要按消息边界重组且设上限。帧/JSON 归属不明就终止，不让迟到结果进入下一轮。

STT 示例：

```json
{"session_id":"session_demo","type":"stt","text":"好堵啊，下匝道都要十五分钟。"}
```

没有业务 turn_id 或通用 final 字段保证。建议公开录音：同一会话只接受一个非空唯一候选；重复同文可去重，不同片段冲突则失败。取得候选后遇到 TTS 开始可中止助手回复并结束候选收集；无边界时可用 1.2 秒静默窗口，但这只是本地策略，不保证平台最终识别。建议公开转写等待上限 12 秒，私人回复上限 45 秒，均可配置且需实测。

空转写/多候选/断连/超时保留私有原声，不发布，不伪造转写。公开转写模式不播放灵矽自由回复；私人聊天模式可播放，但不会创建公共草稿。

### 6.4 TTS、必要时读回与打断

```json
{"session_id":"session_demo","type":"tts","state":"start"}
```

之后可有二进制音频及句子文本：

```json
{"session_id":"session_demo","type":"tts","state":"sentence_start","text":"本次回复文字"}
```

```json
{"session_id":"session_demo","type":"tts","state":"stop","is_aborted":false}
```

is_aborted=true 表示中断，清空本地队列；正常 stop 后仍需排空本地播放。未见本轮 start 的音频、会话 ID 不匹配、异常大帧均不能直接播放。error/异常断连结束本轮并释放门禁；config_updated 通知若实际收到则结束当前配置会话，重新同步，不串用前后结果。

设备主动中止：

```json
{"session_id":"session_demo","type":"abort","reason":"wake_word_detected"}
```

这使用平台已有 reason 值；本地停/静音仍先立即执行，不能等待 abort 被服务器处理。

**仅审阅模式/必要澄清需要受控TTS，正常自动发布不读回。** ESP32 可对官方文本播报能力做独立验证；候选消息形状如下：

```json
{
  "session_id":"session_readback_demo",
  "type":"listen",
  "state":"detect",
  "text":"待发布内容核对",
  "text_response":"将公开回复刚才的问题：好堵啊，下匝道都要十五分钟。这段会公开，发出去吗？"
}
```

在已握手的专用读回会话测试该能力是否准确完整播出指定文本；它不是 STT 录音轮次。使用返回的 TTS 音频时仍校验协商格式和完成边界。若平台/绑定配置不支持，则实现受控文本 TTS 适配并交接其真实协议；不虚构 Sonar 的 `/tts` 接口，也不让自由对话代替逐字读回。手机可使用系统文本 TTS，需同样检验准确性。审阅模式需要读回时不可用则保留私有；正常自动路径不以TTS读回为前置条件。

### 6.5 云端 MCP 分工与语音设置

云端工具由服务端提供，不要求 ESP32 为其再写端侧 JSON-RPC：

| 工具 | 输入 | 约束 |
| --- | --- | --- |
| get_feed | `{}` | 返回 request_id/profile_revision/expires_at/profile/tasks/candidates；候选最多十条 |
| set_focus | expected_revision + 可选 mode/assist_enabled/focus | 修改本人允许字段；不把临时“安静”当长期关注 |
| queue_voice | request_id/profile_revision/post_id | 仅选本轮合法候选并去重；quiet 不允许 |

云端 MCP 用可信专用凭证及平台 Device/Agent/Session 映射用户，配置与真实透传由服务端负责人完成。身份证明不能只依赖可伪造 Header。设备不要保存 MCP 专用密钥。

语音“安静”直接走本地动作并调用 Sonar context；不能依赖云端工具才停声。其他语音设置若走 MCP，固件通过 GET me 取得实际新状态，而非听见助手说“已设置”就认定成功。MCP 未联调不阻塞 HTTP/本地状态机开发，但独立记录未通过。

## 7. 一次完整实现的执行顺序

| 阶段 | 具体工作 | 可检查结果 |
| --- | --- | --- |
| P0 工程与资源 | 读现有工程、填交接表、构建、核对接线/供电 | 能复现构建；硬件未知项明确，不覆盖无关工作 |
| P1 板级录放 | 真实录音、WAV 校验、试听、停止/排空 | 实物输入输出有效，最大 8 秒；有实际接线/资源记录 |
| P2 本地免提 | 唤醒、VAD、播放中本地停/静音、隐私关麦 | 断网也可停；播音不会自唤醒/自确认；不依赖按键 |
| P3 Linx | OTA、绑定、hello、真实STT；必要TTS另测 | 本机实测，现场/私人/系统播音轮次隔离，不强制每条读回 |
| P4 自动业务 | 可信会话授权、语义动作、既有HTTP收发与回执 | 清楚现场发言自动提交、相关原声空隙自动播，不逐条询问 |
| P5 角色互换 | ESP32 主动提问、等回复、语音播放 | 不是写死 bob；正确查询/回执，来源与时间可听 |
| P6 异常与交付 | 去重、过期、取消、噪声、断网/掉电、三轮联调 | 不误公开/串问题；状态真实，另一人可复现 |

P4前实测发言至提交的总时延并与服务端核定TTL。自动路径须有可信会话授权，审阅路径按用户选择保留核对；都不能忽略时效。

## 8. 给 AI 的完整执行提示词

将本文件保存到固件工程根目录，例如 `ESP32-HANDOFF.md`，把真实板卡资料放进工程，再复制下面提示词。真实凭证用本地私有文件注入，不粘到共享聊天和报告。

### 8.1 总提示词

```text
你是声呐 Sonar 的 ESP32 硬件/固件工程师。以随附的 ESP32-HANDOFF.md
独立交接书为本轮产品和协议依据，无需取得其他 Sonar 文档即可开始。
先读本固件工程自身 AGENTS.md、README、构建配置、原理图与板级驱动。
如真实工程接口与交接书不一致，记录具体差异，不自行改云端字段。

目标：车用自然交流，ESP32用自己的扬声器，VAD有界收束发言，
本次现场交流授权内的清楚现场发言自动发布，相关回复合适时直接播真人原声；
不要求每句唤醒、说“我想提问”、读回、确认或发出播放命令。
必要歧义才简短澄清，正常系统保持安静。断网且播放中仍可说停/安静。

先报告当前源码路径、revision、工具链、板型、引脚与供电来源、存储资源、
可用驱动/语音前端及缺口。保留原有工程框架和无关改动；不猜引脚，不刷
平台返回的陌生固件。没有实物先做协议/状态机模拟，实机项写未执行。

按 P0→P6 逐阶段实施，先做最小可运行适配层。只改 firmware/ 或已明确
授权的固件目录及该目录的必要文档/检查，不重写手机和服务端。
麦克风统一采集分发，普通出声统一协调；本地停/安静高于网络和 TTS。
Linx 与 Sonar 凭证独立，HTTPS/WSS 校验证书，不记录密钥或整车对话。

公开原声保持 PCM WAV 16kHz/16-bit/单声道/最长8秒；Linx 按真实协商。
绑定本次会话授权版本、turn_id、原声Hash、准确STT和真实目标/邀请/会话。
它们是内部状态，不添加进旧Sonar请求。STT冲突/迟到/归属不明不公开。
正常授权内现场发言自动发布，相关回复合适时自动播，不逐条读回或问确认。
私人助手、闲聊、媒体、自播音、无授权和含混输入不自动公开。
仅用户选审阅模式或必要歧义才用自然澄清，不堆系统控制声音。

先实现本文所有必要 HTTP 与 Linx 协议、重试/持久化，再做真实设备联调。
需要服务器地址/凭证时由负责人安全注入；缺少外部条件继续独立可做项，
不得伪造上线或通过结果。不确定平台能力就实现小型探针记录真实收发。

每阶段提供修改文件、命令、实际输出、设备观察、失败与下一步；适量检查
状态机重要边界，板级和声学性能用实机验收。最终交 HANDOFF.md、构建与
烧录命令、固件Hash、接线/资源表、配置示例和逐项验收状态。
```

### 8.2 分阶段追加提示词

```text
[P0/P1] 在不改业务协议的前提下完成工程摸底和本地录放。先核对原理图及
SDK支持，搭 board_audio/board_input，实际录制/校验/试听8秒以内WAV。
报告真实烧录结果、供电/引脚来源、缓冲排空和内存占用，不把编译当实机。
```

```text
[P2] 在已有音频采集上实现本地唤醒/VAD和停/静音。列所选芯片/模型实际
支持的唤醒词与AEC接口。播放本人/他人/TTS时保持关键字可用；将最终扬声器
PCM作为适用的AEC参考。测试自播音和乘客/广播误触发，不声称声纹鉴别。
云端断网仍能停止，关麦后不能语音重开。无法达到则明确免提阻塞。
```

```text
[P3/P4] 按本文字段完成 Linx OTA/WSS/STT/TTS/精确读回，以及 Sonar HTTP。
先生成与当前时钟一致的邀请/原声模拟响应，断言轮次/版本/权限校验，再接
真实服务。用不同的私人聊天、公开转写、读回、确认轮次，不能相互串话。
实现会话授权、真实语音来源、意图与关系校验，允许范围内自动发布/收听；
不把旧assist/listen开关当自动发布授权，不用按键/屏幕或逐条确认替代新目标。
```

```text
[P5/P6] 补 ESP32 主动提问/收到原声，再覆盖过期、取消、上传/确认/回执丢
响应、模式切换、重启和噪声。连续三轮核对真实ID与原声归属；恢复先同步，
未知结果不重播不重发。输出通过/失败/未执行及脱敏证据，设备验收独立列。
```

## 9. 开发电脑先做的无发布检查

负责人已通过安全方式在本机导出 `SONAR_BASE_URL`、`SONAR_DEVICE_TOKEN` 后，在电脑终端执行以下只读请求，不上传、不公开、不更改配置。示例域名 `https://sonar.example.com` 是占位，不能作为真实服务使用。

```sh
curl --fail-with-body --silent --show-error "$SONAR_BASE_URL/healthz"
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $SONAR_DEVICE_TOKEN" "$SONAR_BASE_URL/v1/me"
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $SONAR_DEVICE_TOKEN" "$SONAR_BASE_URL/v1/inbox"
```

不要开 curl -v/命令跟踪把请求凭证录入日志。电脑通不代表 ESP32 通；板卡的 localhost 不是电脑，手机 ADB reverse 也不对 ESP32 生效。板卡再复测 DNS、时间、证书和 HTTPS。

上传阶段可在负责人提供的专用测试用户与测试服务中使用下述形状，`metadata.json` 按 5.8 创建、`voice.wav` 必须来自本轮录音。此操作仅创建私有草稿，不自动 confirm：

```sh
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $SONAR_DEVICE_TOKEN" \
  -F 'audio=@voice.wav;type=audio/wav' \
  --form-string "metadata=$(cat metadata.json)" \
  "$SONAR_BASE_URL/v1/posts/drafts"
```

状态机模拟必须覆盖：pending/prompted、actionable=false、request/revision 改变、过期、静音、重复投递、ack 丢响应、授权撤销、审阅确认超时和跨轮迟到。模拟结果标明“模拟”；预置文字和静音 WAV 不能冒充真实 STT 或真人回答。

## 10. 实机验收与交付记录

先在静止车内完成，再另行组织受控条件体验评估。零触屏只是交互目标，不是道路安全或车规认证；噪声、路况和驾驶负荷没有本轮通过证据。

| 编号 | 检查 | 必须成立 |
| --- | --- | --- |
| H1 | 录放、最大时长、资源 | 正确WAV，实体麦克风/扬声器有效，内存有界，无复位/看门狗 |
| H2 | 会话授权后的自然现场发言与回复 | 无必需触屏/按键/逐条唤醒确认或播放命令；正常路径无控制播报 |
| H3 | 出声中/断网中“停一下/安静”，两类各20次 | 首轮建议每类至少19/20成功，从指令结束至实体停声P95≤500ms；识别事件至静音另设≤300ms动作预算，并记录人声起点至停声延迟 |
| H4 | 相同路况句在已授权现场/私人助手/无授权/媒体源中对照 | 只有合法现场原声自动发布；授权撤销/否定/旧结果不发 |
| H5 | 沉默、误唤醒、录到广播、乘客插话、连续识别失败 | 不擅自公开，不无限重问；无法确定来源时保留草稿 |
| H6 | A问B答连续三轮、B问A答至少一轮 | 真实STT和真人原声，ID/版本不串；无有效授权绝不公开 |
| H7 | 相关回复与人声/采集/电话/安静交叉 | 空隙自动播正文，用户说话即让路；不先问要不要听，不抢麦 |
| H8 | 拒绝/取消/过期/位置失效/quiet | 无迟到提醒或无资格发布；恢复用新问题验证 |
| H9 | 上传/confirm/ack丢响应、重启 | 不重复公开、不自动重播未知内容；未确认草稿不复活确认 |
| H10 | Linx绑定/真实STT/本地人声让路 | 分别实测本机；必要TTS单测，不把读回当自动发布前置 |
| H11 | 手机锁屏/后台/来电，ESP32供电/网络恢复 | 各端真实可用性可观察；恢复不自动继续公开或大声连播 |
| H12 | 正常风噪/空调/音乐、不同距离音量 | 记录误唤醒/漏唤醒、停声延迟、可懂度；无数据就写未测 |

H3 的 300ms 是客户端动作预算建议，不是从用户开口到停声的保证。禁止只报平均值掩盖失败：记录样本数、成功率、尾部延迟与失败条件，阈值经静止车内测试再冻结。

最终 `HANDOFF.md` 至少包含：源码/revision、板卡/原理图/接线/供电、SDK/依赖、构建/烧录/回退命令、固件文件及 SHA-256、分区/资源、配置安全注入、唤醒模型和参数、按键/LED映射、逐项通过/失败/未执行、证据位置与剩余问题。分开标记模拟、编译、烧录、Linx、Sonar、MCP、免提体验，不互相替代。

## 11. 外部技术依据（查平台变更时用，不是开工必读）

- Linx [OTA网关](https://linx.qiniu.com/docs/xrobot/platform/OTA)、[WebSocket协议](https://linx.qiniu.com/docs/xrobot/platform/websocket)、[云端MCP](https://linx.qiniu.com/docs/xrobot/mcp/software-mcp)：本文平台消息依据。
- 乐鑫 [I2S](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/peripherals/i2s.html)、[HTTP Client](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/protocols/esp_http_client.html)、[NVS](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/storage/nvs_flash.html)：切换到实际芯片和锁定SDK版本，勿直接升级。
- 乐鑫 [ESP-SR AFE](https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/audio_front_end/README.html)：AEC、降噪、VAD、WakeNet 与播放参考能力；当前板卡是否支持需要核定。
- Google [车用交互原则](https://developers.google.com/cars/design/design-foundations/interaction-principles)：简短、可中断、驾驶任务优先。NHTSA [语音车内界面研究](https://www.nhtsa.gov/document/characteristics-voice-based-interfaces-vehicle-systems-and-their-effects-driving-0)：免提语音仍可能产生认知负荷。

接口变更时由负责人更新本文件版本，并提供实际部署对应的变更说明，避免独立转发副本与服务不同步。所有文中未标实测的免提能力均待实现和验收。
