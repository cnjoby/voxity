# 声呐 Sonar｜三端接口契约 v1

2026-10-26生产036时效扩展：开启`SONAR_INFORMATION_VALID_UNTIL_DAY_END`后，普通公开动态信息、问题、邀请、回答按北京时间发布当日24:00截止，线程回复还受原线程截止限制；长期until_withdrawn公告保留。按服务端`expires_at`/`valid_until`验收，不使用下文旧120秒/90秒常量。原声payload新增`playback_intro`，按`published_at`计算“几分钟前/几小时前发布”；先播提示，复核状态后播原录音，原录音完成后才报played。[完整规则](day-validity-20261026.md)。

2026-10-26生产031已补齐OpenAPI版本`0.3.0`与WAV/Ogg二进制响应；格式合同和隔离curl证据见[音频格式对接核对](opus-interface-20261026.md)。

**2026-10-26最新版音频扩展：**业务上传支持单声道Ogg Opus（`audio/ogg`），仍按实际解码校验最长30秒与大小限制；压缩原声原样保存/返回，下载可能为`audio/ogg`，不为旧客户端转换WAV。Linx WSS使用原始Opus包，与HTTP的Ogg封装不同。会话增加`client_managed_voice`，录音metadata增加可空的`captured_context_post_id`；字段含义、MCP写操作门禁与验收见[0.3.0实现记录](low-latency-opus-20261026.md)。下文早期WAV-only描述以此扩展为准。

日期：2026-10-23。服务端已有[本机运行实现](../../server/README.md)，真实平台、部署和设备仍待验收；静态 JSON 样例供客户端模拟使用。产品职责见[架构](../design/architecture.md)，开发入口见[两天计划](../../two-day-development.md)。本文是字段、枚举与失败行为的唯一开发依据。

## 1. 共同约定

- 配置名 `SONAR_BASE_URL`，部署时填真实 HTTPS 地址；不在文档或源码放实际密钥。
- Sonar 请求使用 `Authorization: Bearer <device_token>`，服务端映射本人，不接收客户端指定权限主体。设备 Token 与 Linx Token 分开。
- JSON 使用 UTF-8、snake_case；时间为 UTC RFC3339；ID 为服务端生成的不透明字符串。客户端生成并持久化 UUID `client_request_id` 标识一条录音，网络重试沿用。
- 演示用户：`alice`（Android）、`bob`（ESP32）。本人位置：`zhonghuan_jinke`（中环路金科路口）或 `demo_approach`（提问者模拟接近区域）；提问目标固定为 `zhonghuan_jinke`，方向固定 `demo_exit`。目标地点独立于本人位置。
- 模式 `listen | quiet`；类型 `question | answer`。一期不实现额外分享类型。
- 每人一个主终端、一次一条待处理录音、一个未结束提问；完成或取消后才能新建问题。连续演示使用新录音ID，不复用旧问题。
- 演示默认：问题有效期 120 秒、邀请有效期 30 秒、位置有效期 600 秒、feed 请求有效期 30 秒、回答音频待听有效期为发布后 24 小时。均服务端可配，邀请不晚于问题截止；回答待听期限独立于问题期限，不代表真实道路验证指标。
- 服务端返回 `server_time`。客户端据此估计倒计时，不依赖设备墙钟准确；离线不能确认有效就不自动出声。
- 成功 GET/PUT/confirm/cancel/ack 返回 200；新草稿 201，幂等重试 200。失败形如 `{"error":{"code":"QUESTION_EXPIRED","message":"问题已过期","retryable":false}}`。
- 状态码：400 参数错误，401 凭证无效，403 无权限，404 不存在，409 状态/版本/幂等内容冲突，410 任务或请求过期，413 音频过大，415 格式错误，422 转写或字段缺失，503 外部能力暂不可用。
- 仅网络错误或 retryable=true 自动重试，退避 1/2/4 秒后显示失败；轮询断网退避至最多 10 秒，恢复先同步状态。401 停止重试并提示配置问题。

## 2. 配置与任务查询

### GET /v1/me

返回本人 profile、设备绑定及最近十条本人问题ID，便于重连恢复。实例见[me.json](fixtures/me.json)。Token 仅在初始化注入，不通过此接口回传。

### PUT /v1/me/context

JSON 请求更新完整可编辑配置：

```json
{
  "expected_revision": 1,
  "presence": {"place_id": "zhonghuan_jinke", "direction": "demo_exit", "source": "simulated"},
  "assist_enabled": true,
  "mode": "quiet",
  "focus": "现在安静"
}
```

响应为更新后的 profile 与 server_time。时间由服务端填写。只有意愿或位置语义变化才递增 revision；重复设置同值只刷新位置时间，不重复取消任务。版本不符 409，客户端 GET me 后让用户重试，不盲目覆盖。

模拟现场确认后每 60 秒复用此接口刷新位置；失去用户确认或退出场景时停止刷新。静音在客户端先立即执行，再同步服务端；同步失败继续保持本地静音。打开声音需服务端同步成功。MCP set_focus 与本接口调用同一业务方法。

### GET /v1/questions/{question_id}

仅提问者查询自己的任务（回答者通过 invitation 获得必要问题信息），响应示例：

```json
{
  "question_id": "q_demo_001",
  "status": "waiting",
  "expires_at": "2026-10-23T02:02:00Z",
  "answer": null,
  "invite_outcome": "prompted",
  "server_time": "2026-10-23T02:00:08Z"
}
```

`status = waiting | answered | cancelled | expired`。回答后 answer 为 `{post_id, transcript, author_label, published_at, audio_url}`；不公开回答者坐标。`invite_outcome = none | pending | prompted | answered | declined | cancelled | expired`，仅表示此请求的实际进度，不推断现场没人。

A 等待时每秒查询；answered 后停止等待查询并处理原声交付。quiet 时仍查任务，但不出声。新回复生成 audio 交付，不能因 answered 就误记为 played。未答且问题到期才显示“暂时无人回应”；拒绝后可以继续显示剩余时间或由 A 取消，不自动再找人。

## 3. 录音草稿与发布

### POST /v1/posts/drafts

`multipart/form-data` 两个字段：`audio`（二进制 WAV，filename=voice.wav）、`metadata`（UTF-8 JSON 字符串）。禁止 base64 音频和在 JSON 填任意音频 URL。

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
  "reply_to": "q_demo_001",
  "invitation_id": "d_invite_001"
}
```

回答不重复上传目标地点，由关联问题继承。kind 两种互斥字段由服务端校验；answer 必须属于本人的有效邀请，reply_to 与 invitation 的问题一致。身份由 Token 确定。草稿仅作者可访问。

音频：PCM WAV，16 kHz、16-bit signed little-endian、单声道、时长 >0 且 ≤8 秒；文件 ≤300 KiB，并校验 WAV 实际参数和帧数。Linx 格式另按握手处理，不直接把 Opus 标成 WAV。

响应：`{post_id, status:"draft", transcript, audio_url, server_time}`。服务端保存后不修改该草稿音频或转写；需修改则重录新草稿。相同 user+client_request_id、相同内容返回原对象；内容不同返回 409 IDEMPOTENCY_CONFLICT。

### POST /v1/posts/{post_id}/confirm

请求 `{}`。用户已核对对应录音与转写后才调用；模型或工具无权代为确认。响应为 `{post_id, status:"published", question_id, question_status, server_time}`。question_id 对问题等于其 post_id；回答返回 reply_to。

确认时重新检查问题、邀请、本人意愿、位置与时效。有效回答允许 invitation 为 pending 或 prompted；拒绝/取消/过期后不能发布。校验和发布在事务内完成。首条有效回答结束 waiting；回答竞争或终态发布返回 409/410，不悄悄变成另一条新问题。

相同草稿确认重试返回原发布结果，不再生成邀请或交付。若首次确认未发生且已失效，返回相应错误。创建问题和执行个人策略生成邀请是同一业务操作；只有满足 B 个人规则才创建邀请。不能只根据问题中的路口文字推断 B 在那里。

### POST /v1/questions/{question_id}/cancel

仅提问者，请求 `{}`；重复取消成功，answered 返回 409。响应与问题查询同结构。取消撤销待处理邀请与播放；已经回答的问题可以跳过音频，无需取消问题。

## 4. 邀请与播放队列

### GET /v1/inbox

当前会话每秒轮询，最多十项，按创建时间升序。返回 `{items:[delivery],server_time}`，空队列 items=[]。邀请实例见[invitation.json](fixtures/invitation.json)，音频实例见[audio-delivery.json](fixtures/audio-delivery.json)。

统一 delivery 字段：`id, type, question_id, request_id, profile_revision, state, expires_at, actionable, reason_code, payload`。

- `type=invitation`：state 为 pending/prompted/answered/declined/cancelled/expired；payload 含问题原文、地点名称、预录提示素材键 `onsite_question`、问题原声URL。inbox 返回 pending 和仍可回答的 prompted 邀请；只对 pending 自动提示，prompted 保留回应入口，不反复提示。
- `type=audio`：state 为 pending/played/skipped/cancelled/expired；payload 含 post_id、转写、来源、时间与 audio_url。quiet 时保留 pending，actionable=false，不自动播放；恢复 listen 后刷新 revision，再交付。
- payload 的 URL 是同源相对路径，下载仍附带设备凭证；不得把凭证拼进 URL。固定系统提示素材由客户端打包，服务端给键，不新增公共素材下载权限。

### GET /v1/inbox/{delivery_id}

新增的播放前复核接口，返回 `{delivery,server_time}`。仅本人。取音频后、实际出声前再读：必须 actionable=true、ID/request_id/revision 与准备播放内容一致、未到期、本地未静音。失效返回当前终态及 actionable=false；过期内容不再播放。

invitation 只提示一次；录音、TTS 或本地缓冲未清空时先等待，之后重新复核。该检查降低跨通道迟到风险，无法保证远端在最后检查之后瞬间取消就撤回已出声内容；本地静音始终立即生效。

### POST /v1/inbox/{delivery_id}/ack

请求 `{ "event": "alerted", "request_id": "r_invite_001" }`。

| event | 使用场景 | 结果 |
| --- | --- | --- |
| alerted | 邀请短提示与滴声播完 | invitation→prompted，保留用于回应的 invitation_id |
| declined | 主人明确拒绝 | invitation→declined；仅不回应则等待过期 |
| played | 原声完整播放结束 | audio→played |
| skipped | 主人跳过或停止本次原声 | audio→skipped，不结束其提问任务 |
| failed | 下载/播放失败 | 状态保留待处理或变过期；本地本次会话不自动重复播放 |

响应 `{delivery,server_time}`。重复同一回执幂等，非法类型/状态转换 409；request_id 不匹配 409。静音中止原声时不 ack played/skipped，保留待听，恢复后手动再听可从头开始。

客户端在出声前持久记录本次 delivery_id+request_id 已尝试，完整播放后保存结果再重试回执。崩溃或回执丢失后恢复时不自动重播尝试过的项，显示/提示可手动恢复；不能承诺跨断电“恰好播一次”。首次手动恢复先复核服务器。新邀请用新ID，不能复用旧邀请。

### GET /v1/audio/{post_id}

返回 `Content-Type: audio/wav` 二进制，携带 Content-Length。检查本人草稿所有权，或已发布内容的候选/邀请/问答访问关系；不能只因知道ID就下载他人草稿。只需完整文件 GET，不要求 Range。过期邀请不再授权获取问题音频；回答保留供其提问者待听。

## 5. Linx MCP 边界

云端 MCP 提供 Streamable HTTP `POST /mcp/http`，兼容原 SSE `GET /mcp` 与 `POST /mcp/message/`。2026-10-24 灵矽控制台的新服务只接受 Streamable HTTP，平台实例设置 `config.type="sse"`、`config.transport_type=1`；`sse` 是平台配置结构名，不表示实际使用旧 SSE 传输。配置与分项验收见[灵矽智能体与 MCP](linx-platform.md)。Linx 设备注册、握手、编解码和音频帧遵守官方协议。Token、Agent 与设备实际配置写到私有环境文件，提交示例时只留占位符。

必须先验证可用的 MCP 调用凭证，再依据经过验证的设备/Agent/Session 映射用户。身份 Header 本身不证明可信；无法验证时禁用远程工具并报告阻塞，客户端业务 API 可继续开发，不能宣称完成 Linx 联调。

控制台在没有设备会话时，仅携专用 Bearer 可初始化协议和发现工具 Schema，调用任何工具都返回 `MCP_IDENTITY_MISSING`，不读取或修改个人业务状态。部分身份 Header 缺失仍拒绝；真实业务调用必须同时具备可信 Bearer、Device、Agent、Session 和已登记的用户映射。无身份工具发现与各用户 SSE 会话隔离；Streamable HTTP 每个请求独立鉴权并使用无状态传输。

| 工具 | 输入 | 输出与约束 |
| --- | --- | --- |
| get_feed | `{}` | `{request_id,profile_revision,expires_at,profile,tasks,candidates}`；至多10条合法公开消息，candidate含post_id、原文、来源、时间与audio_url |
| set_focus | `{expected_revision,mode?,assist_enabled?,focus?}` | `{profile}`；只更新当前用户允许字段，复用配置业务逻辑；mode和开关才实际影响策略 |
| queue_voice | `{request_id,profile_revision,post_id}` | `{delivery_id,state}`；必须来自本轮合法候选，重新校验权限/时效/当前模式并去重；quiet 返回 409 MODE_QUIET |

get_feed 的 request_id 在服务器内存保存已授权候选、用户与版本，30秒过期；进程重启即失效，工具需重新查询，不恢复旧播放指令。业务问题与交付仍在 SQLite 持久化。重复 queue_voice 返回原交付；已播放/跳过不自动重播。

现场回答的 audio delivery 由发布事件自动产生；queue_voice 若选中同一回答应返回这条交付，避免两条通道重复排队。唯一性：邀请为 recipient+question_id+type，音频为 recipient+post_id+type。

## 6. 平台与录音配对待验证项

两端各完成：手动开始/停止录音→Linx STT→同一轮本地原声→确认草稿；同时仅一个未完成轮次。Linx session_id 不能直接充当业务录音ID。必须记录实际 STT 完成/超时策略及实测，不假定协议存在 final 或业务 turn_id。

关联不明、断连或超时均不发布。Android 可在明确标注的调试模式输入转写以测 API；ESP32 可用标注的预置音频与转写测试业务通道。模拟测试不能作为真实语音联调通过证据。最终验收至少一次真实提问和真实回答。

## 7. 真实位置与附近问答扩展

[定位接入契约](location.md)定义 PUT /v1/me/location、GET /v1/places、可选 recorded_location、真实 target_place_id 及附近邀请。旧模拟协议保留；context 可省略 presence，仅更新参与意愿，不刷新物理位置。

## 8. 个人决策与提醒

启用 SONAR_DECISION_ENABLED 后，合法候选邀请先由后台 Bamboo 决策；notify 才进入 inbox。ignore/defer 保存于该候选，不反复推理；缺少 Key、超时、上游失败暂缓，不自动全部提醒。推理不在 HTTP 数据库事务内执行，结果返回后重检收件人、消息、request_id、配置版本、位置、意愿、静音和时效。

已批准的 invitation.payload 增加 attention={action:"notify",provider:"bamboo",model:"startlux-decision-27b-autothink"}。GET inbox/{id} 的未批准候选 actionable=false，reason_code=ATTENTION_NOT_APPROVED；确认回答、alerted 回执、原问音频和 MCP queue_voice 同样不能绕过批准。prompted 仍保留自愿回应入口，只有 pending 自动提示。

自己的问题与回答交付延续现有协议：回答不再做兴趣筛选，quiet 下保留待听。Android 通过“我的 → 我关心什么”更新 focus；静音与互助开关保留 focus。完整配置与复跑见[个人决策](../design/attention-decisions.md)。

## 9. 三类声音兼容扩展

2026-10-24 已新增 reply/scene/knowledge/announcement 草稿、地点浏览、频道授权、访问去重与独立收听设置；原 question/answer 字段及行为兼容。增量字段、HTTP 接口、期限和验收见[声音匹配实现协议](voice-matching.md)。私聊、实时对讲及后台唤醒未开放。


## 10. 自然现场交流（2026-10-25）

[自然交流接口与实现](natural-exchange.md)新增独立 exchange 会话、自然 utterance、相关原声 inbox 和当前原声 context。用户在会话层分别授权自动发布与收听，逐条手动确认不再是该模式默认路径；旧手动草稿协议保留。scene 可以成为真实回复根，不能伪造 question/invitation。正常成功不播系统控制声音。物理范围、时效、访问、版本和播放回执仍由确定性规则复核。Android 与服务端已有实现，ESP32 与车内声学待验收。

## 11. 语音互动网络增量（2026-10-25，后端已部署）

[语音网络实现协议](voice-network.md)定义 `network_enabled`（旧会话默认 false）、私有工具回应、地点澄清、远程真人问答、双向闲聊和 Agent 陪聊的明确同意。`chat` 使用独立任务与参与者可见的原声，不复用互助开关或公共帖子。新增 MCP `find_places / get_place_weather / get_driving_route` 为只读业务能力，身份校验不变，不能代替终端上传原声或发问。

## 12. 统一契约与语音入口（SNR-001 / SNR-002）

[实现、权限映射与验证](snr-001-002.md)冻结 `VoiceMessageView / AgentTurnResult / SessionCapabilities` v1，提供 [JSON Schema](contracts/v1/schemas.json) 和[跨端 fixture](fixtures/contracts-v1.json)。新增 `/v1/contracts`、`/v1/messages/{id}`、`/v1/agent/sessions`、`/v1/agent/sessions/{sid}/turns` 及 MCP `agent_turn`。旧录音入口委托同一应用执行逻辑，保留旧字段并新增 `result`。分项能力与旧标志不能混用；旧会话保持原指纹和授权范围。私人查询可独立于公开许可，纯文本不能伪造真人原声；合法已有原声的地点澄清可以通过文本完成。此增量已随 [SNR-003/004 后端发布](../../deploy/deployment-snr-003-004-20261025.md)，旧固件可继续使用原协议；设备接入与真实语音验收独立。


## 13. 合法信息检索与个人任务（SNR-003 / SNR-004）

实现及验证见 [SNR-003/004](snr-003-004.md)。以下接口均使用既有 Bearer 用户鉴权，MCP 使用既有可信设备映射；其他人的任务返回不可见错误，不接受输入用户身份。旧响应字段保留，统一 `AgentTurnResult` v1 未变，轮次外层新增 `task_id`。

| 接口 | 输入及输出 |
| --- | --- |
| POST `/v1/information` | `{place_id,query?:string,purpose?:"all"或"parking"或"weather"或"route"或"knowledge"}`；`{place_id,purpose,items,status,requires_source_comparison,server_time}`，status 为 available/no_valid_information |
| POST `/v1/information/validate` | `{message_ids:[id],purpose?:同上}`，最多 20 个；`{valid,valid_message_ids,server_time}`。播放动态来源须沿用原 retrieval_purpose，不用 all 放宽时效 |
| GET `/v1/tasks` | `{items:[task],server_time}`，当前至多 50 条，按更新时间倒序 |
| GET `/v1/tasks/{task_id}` | task：id/revision/request_id/kind/status/original_text/place_id/question_id/conversation_id/reason_code/created_at/updated_at/expires_at/sources/deliveries/last_result/server_time |
| POST `/v1/tasks/{task_id}/actions` | `{expected_revision,client_request_id:UUID,action:"resolve"或"cancel"}`；返回 task。同 UUID 同输入幂等；不同输入 409 IDEMPOTENCY_CONFLICT、旧版本 409 REVISION_CONFLICT、已关闭 410 TASK_CLOSED |
| GET `/v1/requests/{request_id}` | 原 UUID 对应 `{request_id,turn,task,server_time}`；旧手动草稿为 `{request_id,post,server_time}`。未知 404 REQUEST_NOT_FOUND，非法 UUID 422 INVALID_REQUEST_ID。查询无重发副作用 |

items 包含原 voice_payload 字段及 observed_at/content_origin/temporal_class/observation_time_known/retrieval_reason/version/retrieval_purpose；结果保留来源范围、发布时间和有效期。版本为当前不可变声音 ID，替代／撤回仍须即时访问和时效复核。任务 sources 的 currently_valid 是读取时判断；历史 last_result 不是当前播放许可。工具来源还受有效期约束。无数据不是否定现场事实。

任务状态 querying/clarifying/waiting/responded/deferred/resolved/cancelled/timed_out/failed 与 delivery 状态独立；answered、received、played 均不代表需求解决。动作只管理本人任务，不能通过结束回应者任务取消其他人的待听内容。HTTP 请求成功也不表示已播放。未知提交先持久保存原请求，再只读核对；不能更换 UUID 自动重发。

MCP `find_information(place_id,query?,purpose?) / get_tasks(task_id?) / get_request(request_id) / set_task_state(task_id,expected_revision,client_request_id,action)` 调用同一规则。`get_feed/queue_voice` 新类型继续使用原请求候选、profile_revision、权限、时效及交付唯一性校验。邀请 payload 的 collaboration 补充原请求／任务、deadline、visibility、version 和允许动作，不授予接收者自动回应／公开权限。
