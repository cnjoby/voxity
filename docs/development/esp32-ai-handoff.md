# 声呐 Sonar｜ESP32 当前开发交接书

2026-10-26补充：生产为`20261026-conversation-first-025`，Android仍为0.2.3。有效question邀请不再等待相关性模型批准：以`actionable`、真实request_id和有效期为准，`payload.attention.action=notify`；新增`model_action`／`model_reason`仅为标记、`blocking=false`，不要因模型ignore、defer或尚未返回而自行拦截。有效会话收听新问题不再受十分钟冷却。核心question／answer路径和WAV编码保持不变；连续真人闲聊仍需第7节的双方会话接入。[完整变更与验证](conversation-first-20261026.md)。

更新：2026-10-25，针对真实 Android 0.2.3 与当前生产服务核对。当前入口：`http://122.195.131.106`（联调HTTP）或 `https://122.195.131.106`。这是当前指南；旧v4文字已移至[历史快照](esp32-ai-handoff-v4-20261025-archive.md)，不得再按旧模拟ID、8秒限制和旧会话描述实现。ESP32工程、板卡资料及烧录环境由硬件同学提供，本仓库没有其固件源码。

## 先修复当前“answered, waiting voice”

2026-10-25 23:29这轮真实联调中，两端都在模力社区。ESP32提问后，手机发布回答“有的有的，东门还有三个空位”。服务端收到Bob的played回执，但用户确认ESP32没有出声。该设备状态不应记为播放验收成功。

现网已核对：

- `GET /v1/questions/{question_id}` 返回 `status=answered`，`answer.post_id`、`answer.audio_url` 均有值。
- 用Bob原Token下载该 `audio_url` 返回200、`audio/wav`、133164字节，4.16秒，16kHz／单声道／16-bit PCM WAV。
- 该audio delivery曾收到played，因此 `GET /v1/inbox` 为空。这不代表答案或音频丢失；已处理的交付按去重规则退出待听队列。
- 当前question JSON正文约971个UTF-8字节；JSON解析器所需内存可能大于网络正文长度。旧示例是精简字段，不是最大响应或固定容量保证。

请依次记录：问题ID、JSON HTTP码和正文长度、JSON解析结果／内存错误、`answer.post_id`、`answer.audio_url`、音频HTTP码／Content-Type／Content-Length、实际音频接收字节数、RIFF解析的编码参数／data长度、I2S写入字节数、DMA排空、功放使能、最终ack事件和request_id。不要输出Authorization、Token或Wi-Fi密码。

`answered`只表示服务器有答案。收到200、下载完成、入播放队列都不能发送played。只有真实原声完整送至输出链路且缓冲排空后才played；失败发failed，保留明确错误，不要在清理／finally分支无条件played。若之前误报played，设备应显示“播放结果不一致”，而不是永久waiting voice。

## 1. 身份、地点与范围

- Android为alice，ESP32为bob，分别用原有业务Token；Linx Token是另一套凭证。服务器和地图／模型Key不下发设备。
- 当前Bob固定远程模力社区 `B0K2KHYN77`；direction用 `unknown`，来源是remote_place，不能伪装成GPS。`GET /v1/me`是实际状态依据。
- 远程选择接口：`PUT /v1/me/remote-place`，JSON `{ "expected_revision": 实际revision, "place_id": "B0K2KHYN77" }`。坐标从服务器登记的真实高德POI取得。不要发送旧 `zhonghuan_jinke`、`demo:*`、`demo_exit` 或模拟presence。
- 真实设备定位走 `PUT /v1/me/location`；ESP32没有真实GPS时使用已选择的远程地点。运营固定点位与客户端显式选择以现网本人profile为准。

| 地点 | 原始高德ID | 含义 |
| --- | --- | --- |
| 模力社区 | B0K2KHYN77 | 张江科学之门T1 5层，当前ESP32固定点 |
| 七牛 | B0FFJSBSGR | 上海七牛信息技术有限公司，人工智能岛19号楼 |
| 张江人工智能岛 | B0FFJEOSPR | 园区整体，与七牛不是同一个POI |
| 金科路／高科中路路口 | B0FFHPL61V | 当前Android远程快捷点之一 |

用户已授权人工智能岛园区内问答互通：七牛与园区通过已核实的父子关系匹配，仍保留各自真实ID。问答回执说明园区范围；公告与楼层适用范围不因此泛化。模力社区不是这条园区关系的一部分。作者所处地点、问题询问目标是两个字段，不能互相覆盖。

## 2. 当前HTTP与数据约定

所有业务请求携带 `Authorization: Bearer <原设备Token>`。音频URL是同源相对路径，拼接同一个Sonar base URL并继续带Authorization；不要拼接Linx地址，不对外域／跳转转发凭证。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /healthz | 服务存活，不能证明业务或声音正常 |
| GET | /v1/me | 本人profile、revision、地点和模式 |
| PUT | /v1/me/remote-place | 本人远程真实POI选择／心跳 |
| PUT | /v1/me/context | 本人mode／focus／assist等设置，须实际expected_revision |
| POST | /v1/posts/drafts | multipart原声与metadata，首次201、幂等重试200 |
| POST | /v1/posts/{post_id}/confirm | 提交这条已获本人公开意愿的原声，body为{} |
| GET | /v1/questions/{question_id} | 只查询本人问题，包含answer对象 |
| GET | /v1/inbox | 本人可处理的邀请／音频队列 |
| GET | /v1/inbox/{delivery_id} | 动作前复核这次交付 |
| POST | /v1/inbox/{delivery_id}/ack | 同一request_id的真实结果回执 |
| GET | /v1/audio/{post_id} | 实际WAV二进制 |
| POST | /v1/voices/{post_id}/queue | 本人明确重听时重新排队，使用稳定client_request_id |
| GET | /v1/requests/{request_id} | 核对未知提交结果，不能换UUID重发 |

JSON为UTF-8、snake_case，忽略未知扩展字段，不要求响应只有示例字段。未知kind不得冒充已支持音频播放，也不得ack played。解析失败必须输出错误类型并保留可重试状态。

内存受限固件优先对HTTP流做JSON字段过滤，只保留当前操作需要的字段。不要为完整inbox或全部来源元数据使用按旧样例大小猜出的固定小缓冲。若使用固定JSON内存，记录解析器NoMemory／截断、实际可用堆和最大块；不能解析失败后仍使用之前半份对象。控制JSON缓存与音频播放环形缓冲分开，音频逐块下载／解码／写I2S，不整文件装入JSON文档。

### 本人问题响应：最小必须读取字段

```json
{
  "question_id": "p_question_actual",
  "status": "answered",
  "answer": {
    "post_id": "p_answer_actual",
    "audio_url": "/v1/audio/p_answer_actual"
  }
}
```

其余字段可多于示例，包括来源、时效、位置、转写、可见范围。`answer`在没有答案时为null。`status=answered`且answer有效时应进入音频获取状态；不要继续等待一个额外的`has_voice`字段，当前协议没有该必需字段。

### 待听audio delivery：最小必须读取字段

```json
{
  "items": [{
    "id": "d_audio_actual",
    "type": "audio",
    "question_id": "p_question_actual",
    "request_id": "r_actual",
    "profile_revision": 1,
    "state": "pending",
    "actionable": true,
    "expires_at": "实际UTC时间",
    "payload": {
      "post_id": "p_answer_actual",
      "kind": "answer",
      "audio_url": "/v1/audio/p_answer_actual"
    }
  }],
  "server_time": "实际UTC时间"
}
```

每次最多10项，不能只看第一项或假定第一项一定是邀请。`id`是交付ID，`request_id`是这次交付版本；`payload.post_id`是音频消息ID。三者不能混用。inbox为空不等于question.answer为空，也不表示音频文件被删除。

出声前复核delivery的actionable、ID／request_id／revision、有效期和本机状态。received、seen、下载成功都不是played。终态或版本变化时不拿旧缓存自动出声。已尝试但完成未知的项重启后不自动重播；用户明确重听时调用queue，保存它返回的新request_id。

### 回执

```json
{"event":"played","request_id":"本次真实request_id"}
```

invitation短提示完成可用alerted，明确拒绝用declined；audio完整输出并排空才played，明确跳过用skipped，下载／解析／输出失败用failed。回执失败只重试相同回执，不重发提问、不自动重复播放。request_id冲突须刷新交付；HTTP成功不证明I2S或功放出声。

## 3. 录音、发布与回听

当前原声格式仍为PCM WAV：16kHz、16-bit signed little-endian、单声道；**上限已从旧8秒提高到30秒**（480000帧），单文件上限1000KiB，完整multipart body上限1MiB。时长与格式都校验；超限不截半句发布。当前4.16秒回答小于旧8秒限制，此次不能仅归因于时长上限调整。

解析RIFF chunks找fmt/data，校验PCM、声道、采样率、位宽和data长度。不要把44字节WAV头当PCM送I2S；I2S槽宽32-bit、麦克风有效位、左右声道与播放器16-bit PCM的转换由真实板卡资料确定。不得猜引脚或仅凭成功写入队列就声称喇叭播放完成。

问题multipart metadata示例：

```json
{
  "client_request_id": "本轮固定UUID",
  "kind": "question",
  "transcript": "模力社区现在有车位吗？",
  "target_place_id": "B0K2KHYN77",
  "direction": "unknown"
}
```

answer metadata使用 `kind=answer`、`reply_to=实际问题ID`、`invitation_id=本人有效邀请ID`，不重复指定目标地点。audio字段是真实WAV文件；metadata是JSON文本，不能base64，也不能把任意audio URL当上传音频。

草稿POST成功取得post_id后，提交对应confirm；同一录音保留同一个client_request_id，HTTP超时先核对原请求。服务端收录成功、有人收到邀请、播放完成与用户实际听见是不同状态。

本人问题收到answer后，按其audio_url下载播放；正常队列路径同时管理实际delivery。若旧固件误发played导致inbox为空，明确重听操作使用 `POST /v1/voices/{answer.post_id}/queue`，JSON `{ "client_request_id": "本次重听固定UUID" }`；使用返回的delivery和新request_id。不能每轮自动queue造成重复播放。

当前配置：question 120s、invitation 90s、位置采样有效60s、回答待听86400s。过期邀请不再授权新回答；回答音频另按其待听期限保留。以返回的server_time、expires_at和最新状态为准。

## 4. 语音优先会话（当前接口）

App已使用单一voice_first会话。ESP32若接入同一语义入口，使用当前接口，先完成本轮板级播放故障修复，不混入旧五模式或旧模拟协议。

`POST /v1/agent/sessions`（同义路径 `/v1/exchange/sessions`）：传client_request_id、expected_revision、`interaction_mode=voice_first`、明确的place_id与timezone；当前默认私人查询，不自动公开所有车内谈话。需原声的语音轮次用 `POST /v1/exchange/sessions/{sid}/utterances` 上传audio＋metadata，metadata含client_request_id、expected_session_revision、transcript、recorded_at。

响应顶层既可能含agent_response，也可能含voice_action；必须先执行结构化voice_action，不能因agent_response.text为空而忽略真人音频。type=play_audio时下载items内的audio_url；type=location时更新本人地点来源。普通查询私有；明确问人／分享／有效上下文的回答才公开。合成素材不建立真人回答关系。

实际播放开始后同步 `/v1/exchange/sessions/{sid}/context`，使用真实post_id和当前session revision；之后自然回答才绑定已听见的唯一问题。复用当前会话的inbox、阶段控制及原声回执。played不代表需求已经解决。当前App的match_scope／dispatch是附加回执信息，不替换原ESP32 question.answer、inbox、audio_url和ack字段。

## 5. Linx与板级边界

业务Token与Linx WSS／OTA凭证独立保存，保留既有Device-Id、Client-Id和绑定，禁止为联调随机生成新身份或清空NVS。Sonar的HTTP入口不改变Linx安全WSS和实际音频协商。先hello确认session_id／PCM或Opus参数，再按真实协商上传或解码；TTS不是其他用户的真人原声。

硬件工程需提供：源码revision、ESP-IDF／Arduino及库版本、板卡／原理图、音频Codec与功放、I2S引脚／槽宽／采样率／声道、功放使能与音量、最小堆／最大块／PSRAM、构建烧录命令及固件SHA。没有这些资料，本仓库无法声称已修复或烧录ESP32固件。

建议串口每轮带question_id／post_id／delivery_id，记录以下阶段，而非只显示answered：

```text
question_answered -> audio_metadata_parsed -> audio_http_200
-> wav_validated -> i2s_writing -> output_drained -> played_ack_200
```

失败明确停在json_error／http_error／wav_error／i2s_error等状态；played_ack_200之后不再显示waiting voice。日志可打印User-Agent构建标识、状态码和字节计数，不打印任何Token。

## 6. 当前验收与证据

已验证：真实手机STT、扬声器TTS、ESP32原声在手机播放（用户确认）；手机对真实ESP32问题发出的原声回答已入库；该回答可用Bob Token正常下载。ESP32曾返回played但用户确认没有出声，**ESP32播放未通过验收**，需上述串口／输出链路证据，不能以服务器ack替代人耳。

当前细节见[语音故障记录](audio-incident-20261025.md)、[HTTP契约](contracts.md)、[0.2.3 App交付](audio-incident-20261025.md)。下次联调双方地点先核对真实ID，收到新鲜问题后在90秒邀请窗口内回答，再分别确认下载、播放、回执及真人听见。


## 7. 一轮问答后继续闲聊：必须接入同一会话

用户后续确认已经完成一轮真实对话播放，问题转为后续闲聊不能互通。现网记录显示：23:47手机发言是private_chat；23:48来自ESP的新问题被个人筛选记为MODEL_TIMEOUT；23:49手机的新问题记为MODEL_UNCERTAIN；没有Bob的配对chat请求，也没有双方paired闲聊会话。这些话语没有被当作同一段持续对话投递，不能靠重复GET同一个question修复。

固件需要区分两条业务路径：

- 有明确问题／邀请／回答关系的question、answer及其reply线程。
- 双方主动请求并配对的持续真人聊天：建立本人voice_first session，上传真实语音轮次表达聊天意图；session.chat.state变为paired后，继续使用该sid提交发言。对端也必须有自己的活跃session和聊天请求。只有一端请求时不能伪造对方同意，Agent私人陪聊不等于另一位真人。

配对状态的语音轮次可返回status=chatting／action=human_chat或chat_message；收件仍通过 `/v1/exchange/sessions/{sid}/inbox`，交付为type=audio，但payload.kind为chat，音频URL为 `/v1/chat/audio/{message_id}`。必须使用返回的audio_url，不硬编码所有声音都在 `/v1/audio/`。回执仍使用服务器返回的delivery ID和request_id；chat delivery ID前缀chatd不应被固件丢弃或替换成question ID。结束／安静、时效、对方退出与重复播放规则仍需执行。

若固件只轮询 `/v1/questions/{qid}` 等answered，或只接受payload.kind=answer，就没有接入持续聊天。请提供ESP32工程路径／仓库和对应revision，核对其上传入口、inbox解析、支持的kind、chat音频URL及播放器状态机。本仓库目前没有固件源代码，不能宣称这部分已修改或烧录。

原问答播放与持续聊天应分别验收。普通私人话语不会因两端在线就自动广播；对话关系和双向意愿应由同一会话及语义决策处理，不用把所有说话强制发到广场。
