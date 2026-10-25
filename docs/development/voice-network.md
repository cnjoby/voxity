# 语音互动网络：实现、协议与本轮验证

日期：2026-10-25。设计依据：[车主与个人 Agent 的语音互动网络](../design/voice-network.md)。本轮在 `d7411ea` 基线上本地开发，未部署或更新线上灵矽提示词，未安装到实体手机。工作区另有规划、部署和 ESP32 交接修改，本轮未覆盖这些工作。


后续状态（2026-10-25）：本增量已随 [SNR-001–004 后端发布](../../deploy/deployment-snr-003-004-20261025.md)。私人查询的来源优先选择、任务台账和播放前复核以 [SNR-003/004](snr-003-004.md) 为准；下文保留原实现阶段的验证记录。实体手机和灵矽提示词状态仍单独核验。
## 实际入口与回应来源

Android 首页“开始现场交流”沿用独立的会话授权、原声录制及收听通道，授权文字已包含目的地发问和双方自愿的车友闲聊。一次会话中，服务端按明确意图区分：

| 输入 | 处理 |
| --- | --- |
| “我想去张江人工智能岛，现在堵车吗？” | 地点解析 → 本人的当前有效定位 → 高德驾车路线、预计时间及已返回的分段路况 → Agent 回应 |
| “张江人工智能岛下雨了吗？” | 明确 POI → 所在区县天气报告 → Agent 回应；保留报告时间，不声称知道该 POI 此刻雨量 |
| “去公司路况怎样？” | 只查本人的目的地别名；没有地址先问，补充明确地点后再查询 |
| “帮我问问张江人工智能岛现在堵不堵？” | 明确要求真人 → 目的地附近参与者的有效邀请 → 自愿现场回答 → 提问者收到原声 |
| “前面景区还有停车位吗？” | 先问景区名称，不发布；补充地点后发布同一原问题的原录音 |
| “我想闲聊” | 独立真人请求；对方也明确想聊、会话可用且近期在线时才配对 |
| 暂未匹配到车友 | 先问“要我陪你聊吗？”；明确接受后进入私人 Agent 陪聊，拒绝不开始 |

工具回应和真人音频分别执行。Agent 的文本由 Android 中文 TTS 合成；接受私人陪聊后，后续私人轮次使用既有 Linx 语音能力。真人内容始终下载上传者的原始 WAV，不生成替代声音，也不强制在原声前拼接一段 Agent 播报。

“我的 → 灵矽语音”仍是独立私人助手诊断入口；它可通过新增的只读 MCP 工具查询信息，但没有上传/发布真人原声的 MCP 能力。真人网络的自动发问、澄清和闲聊从上述自然交流 HTTP 通道执行，不能把私人诊断入口的自由回复当成已经建立真人任务。

## 会话与发言协议

`POST /v1/exchange/sessions` 增加：

```json
{
  "client_request_id": "11111111-1111-4111-8111-111111111111",
  "expected_revision": 1,
  "place_id": "已注册的会话初始地点",
  "auto_publish": true,
  "auto_play": true,
  "network_enabled": true
}
```

`network_enabled` 默认为 false，旧会话增量迁移后仍为 false，旧版请求的幂等指纹保持兼容。新版 Android 用户同意扩大后的发送范围时才设置 true。`auto_publish`、`auto_play` 仍分别控制发送和自动收听，不能由互助开关推定。旧服务器不认识这个字段；新 APK 必须使用配套的新服务。

发言仍上传同一真人 WAV 与 `client_request_id / expected_session_revision / transcript / recorded_at`。原声格式、8 秒上限、60 秒录音时效及原有鉴权不变。新增结果：

```json
{
  "status": "clarifying",
  "action": "clarify",
  "agent_response": {
    "speaker": "agent",
    "kind": "clarification",
    "text": "你说的是哪个景区？请说城市和景区名称。",
    "sources": []
  }
}
```

工具答案为 `status=responded, action=agent`，`agent_response.kind=weather|route`；来源数据放在 `sources`。没有有效定位返回 `kind=location_required`，不编造原点。工具失败时返回 `clarifying / human_offer`，只有明确同意后才公开原提问。

明确目的地的问题返回 `published / question`，增加 `target_place:{id,label}`。既有 question/invitation/answer/reply、音频权限和回执继续复用。发布与接收本人真实问题线程的回复不再要求提问者身处目的地；现场回答、无关现场声音与 scene 分享仍保留在场规则。提问目标由已消歧的地图结果确定，不修改本人真实位置。

### 澄清与竞态

`agent_pending` 每个会话最多一条：原始录音、原始转写/请求 ID、待解决意图、候选地点和原轮次引用保存于 SQLite。期限沿原轮次最多 60 秒，不因反复澄清延长。重启后可继续有效澄清；原问题仍使用原录音和原请求 ID，不将“上海世纪公园”另发成一个问题。

多个同名 POI 不选择第一条，支持说具体名称或第几个候选。“前面景区”不从初始地点推断。公司/家别名只按当前可信 user_id 保存和读取。

地图和模型在事务外执行，返回后复核会话、设置版本、期限、上下文和待澄清状态。安静/结束删除待澄清录音；明确“别发”撤销待续请求。迟到结果不能恢复授权或发布到新上下文。已有等待中的问题仍按原规则返回 `QUESTION_ACTIVE`，失败不会遗留本次新写入的音频文件。

## 独立的真人闲聊状态

闲聊新增 `chat_requests / chat_messages / chat_deliveries`，不复用公共 question 状态、地理附近资格或 `assist_enabled`。双方实际说出闲聊意愿，且都授权网络发送时才配对。候选要求会话活跃、未安静、请求有效、最近 30 秒有请求/收听活动；待配对请求 120 秒有效，配对不超过双方会话期限。

闲聊结果 `status=chatting, action=human_chat`。未匹配时返回 `clarifying / chat_offer`；明确接受后返回 `responded / agent_chat`，随后私人发言返回 `action=private_chat`。等待期间新车友匹配与接受 Agent 陪聊互斥复核，不在已经配对后再偷偷启动 AI。用户可以再次明确寻找车友，退出当前 Agent 陪聊状态重新匹配。

闲聊消息使用标准音频交付形状，`payload.kind=chat`、`source_type=human_original`、`visibility=participants`。兼容字段 `question_id` 在此承载不透明的 chat 请求 ID，不代表公共问题；`post_id` 为 `chatm_...` 消息 ID。

- `GET /v1/exchange/sessions/{sid}/inbox` 返回本人本会话的待听闲聊原声；支持已有 `exclude` 和去重。
- `GET /v1/chat/audio/{message_id}` 只允许该有效配对的双方下载，不能只知道 ID 就访问。
- `GET /v1/inbox/{delivery_id}` 和 `POST .../ack` 复用原声播放前复核及 `played/skipped/failed` 规则。
- `PUT .../context` 接受实际听到的本配对消息，不能绑定其他配对的内容。
- 会话查询的 `session.chat` 返回本人的 `id/state/expires_at`，供终端同步对方已结束后的状态。

配对后普通发言发给车友；明确叫“声呐”或要求工具查询时回到个人 Agent。安静暂停出声；结束/过期关闭双方配对、终止待播交付并清空保存在聊天表中的音频，不自动补发。第三个用户不能下载双方消息，也不能读取其交付。

## 地图与 MCP

现有高德适配新增区县天气与驾车路线，未安装另一个地图供应商插件。对 Linx 暴露三个 Sonar MCP 工具，复用同一适配和个人身份约束：

| 工具 | 输入 | 边界 |
| --- | --- | --- |
| `find_places` | `query` | 搜索并登记公共 POI，结果供消歧；不更新本人位置 |
| `get_place_weather` | 已明确的 `place_id` | 返回区县报告、reporttime、来源；过期/缺失结果失败 |
| `get_driving_route` | 已明确的 `place_id` | 原点只取本人的新鲜定位；保留未知路段，不将其说成畅通 |

原 `probe / get_feed / set_focus / queue_voice` 保留。`get_feed` 增加本人 `destinations` 与本人当前 `chat` 摘要，不能读取其他用户的别名、私人对话或 Agent 状态。无可信设备身份的新工具调用同样返回 `MCP_IDENTITY_MISSING`。网络请求不在 SQLite 写事务内，服务端 Key 不下发终端。

供应商协议依据：[高德天气查询](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)、[高德路线 API](https://developer.amap.com/api/web-service/guide/routes)。本实现使用现有国内 `/v3` Web 服务；天气读取报告时间，路线读取预计耗时和 TMC 路况。接口错误、空结果或缺少字段不产生肯定答案。

## 验证结果与证据

| 层次 | 本轮结果 |
| --- | --- |
| 服务端自动检查 | 135 项通过，Ruff 检查/格式通过；一条原有上游 TestClient 弃用提示 |
| Android JVM / 构建 | 30 项通过；Lint、Debug APK、测试 APK 构建通过 |
| Android 隔离模拟器 | 5 项通过；前台服务、后台/安静、真人音频 API、Agent 音频来源、中文 TTS 生成与播放分别检查 |
| 真实供应商 + 隔离真实 HTTP | 2026-10-25 10:48–10:49，Linx STT + AMap + Bamboo + 临时业务库的两类问答闭环通过，原声逐字节比较、回执通过；工具、双方闲聊、AI 陪聊明确同意分流通过 |
| 实体双人及车内验收 | 未执行；模拟坐标、合成输入和模拟器不代表物理在场或真人听感 |
| 生产发布 | 本轮未执行；平台提示词只更新本地草案，未保存到云端 |

真实供应商检查采用 macOS 合成测试语音，通过已授权的独立协议客户端获取真实 STT；本机 ASGI 应用以真实回环 HTTP 运行，业务凭证和 SQLite 完全隔离，位置明确注入为 `android_mock`。双方问答和闲聊中的“原声”在本测试指上传的同一合成 WAV，用字节相等验证没有被 TTS 替换；没有把它说成真人现场录音。`played` 在 HTTP 检查中由脚本模拟，真实 MediaPlayer 完成回调另由模拟器检查。

真实供应商最终一轮验证：

1. 原始路况句经真实 STT，返回地图的路线、预计时间和未知路段说明。
2. 天气句经真实 STT，返回浦东新区报告及其时间，明确区县覆盖范围。
3. 明确问现场的人 → 对方的真实 Bamboo 邀请筛选/原声收听筛选 → 上传现场回答测试音频 → 远处提问者获得相同 WAV → 回执。
4. 未明确景区时没有发布 → 补充“上海世纪公园” → 地图确定目标 → 对方现场回答 → 原问题/原回答 WAV 相同 → 回执。
5. 一人想闲聊时只提供 Agent 陪聊选择；第二人明确想聊才互相交付原声，并继续往返。
6. 对方结束后，再次寻找车友无匹配 → 先询问 → 明确接受 → 后续进入私人 Agent 路由，不产生公共帖子。

失败记录一并保留：首轮停车邀请观察早于后台筛选完成，测试改为正常轮询等待；另一次邀请筛选返回 `MODEL_UNAVAILABLE / defer`，没有强行放行；一次真实停车回答暴露“还有十几个空位”被误判新查询，修正问句判断并增加回归用例后完整复跑通过。没有调低模型阈值，也没有清除正式系统中的判定缓存来伪造通过；单轮成功不代表延迟或供应商稳定性验收。

本机证据在忽略目录 `output/voice-network-20261025/`：`live-report.json`、前三次尝试报告、合成 WAV、渲染截图及中文 TTS 检查记录。Android 单元结果在 `android/app/build/test-results/testDebugUnitTest/`。未修改实体手机配置；本轮部署/Caddy/ESP32 等其他在途编辑予以保留。

## 复跑

```sh
cd server
uv run pytest -q
uv run ruff check app tests ../scripts/smoke_voice_network.py
uv run ruff format --check app tests ../scripts/smoke_voice_network.py
cd ../android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

真实供应商检查仅在 macOS 有 `say` 中文音色及 `ffmpeg` 时运行，需本地已有有效 AMap/Bamboo Key、已授权的 Linx Device 绑定及 `server/.env.linx` 的独立协议 Client ID。它不操作手机麦克风、不播放测试语音、不在正式库发帖：

```sh
uv run --project server --with websockets --with python-dotenv python scripts/smoke_voice_network.py --live
```

脚本创建新的输出目录和临时业务库；不能用其替代两位真人在实体设备上的录音、播放、后台、回声和停止体验验收。Android Agent 出声依赖可用的本机中文 TTS；不可用时保留可见文字和真实错误状态，不能声称已经出声。
