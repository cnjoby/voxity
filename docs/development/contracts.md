# 声呐 Sonar｜三端接口契约 v1

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

- `type=invitation`：state 为 pending/prompted/answered/declined/cancelled/expired；payload 含问题原文、地点名称、预录提示素材键 `onsite_question`、问题原声URL。inbox 只投递 pending 且当前可提示的邀请，prompted 不反复提示。
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

采用架构既定的云端 SSE MCP，由服务端角色完成平台实测和配置。Linx 设备注册、握手、编解码和音频帧遵守官方协议，本文不自创替代协议。Token、Agent 与设备实际配置写到本地环境文件，提交示例时只留占位符。

必须先验证可用的 MCP 调用凭证，再依据经过验证的设备/Agent/Session 映射用户。身份 Header 本身不证明可信；无法验证时禁用远程工具并报告阻塞，客户端业务 API 可继续开发，不能宣称完成 Linx 联调。

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
