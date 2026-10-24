# 自然现场交流：Android 与服务端实现

更新：2026-10-25。对应[自然交流交互规范](../design/ux-designs/ux-sonar-2026-10-24/EXPERIENCE.md)。本轮实现 Android 和 Python 服务端；ESP32 固件仍由硬件同学接入。代码、隔离测试和真机验收分别记录，不能互相替代。

## 用户实际流程

停车时，在真实服务、真实定位模式下选定交流地点，点击“开始现场交流”。“自动公开现场原声”和“自动收听相关回复”分开选择，初始均不勾选。Android 需要定位、麦克风和后台控制通知权限；自动发布还需要已绑定的灵矽识别。自然交流使用 Android 9 及以上的本机扬声器路由。

开启后，正常说“金科路中环路下面堵死了”。App 自动截取同一句原声、识别文字，服务端判定为授权地点的现场分享后直接发布，不逐条询问“确认公开”。对方收到与关注或当前交流有关的现场原声；对这段原声的真实回复沿原关系返回，在用户没说话、没处理录音、没通话且允许播放时自动播放。

正常路径不播放 App 的成功 TTS、邀请控制录音或“要听吗”。状态只显示在界面。用户开口先暂停原声，不等待云端判断；被打断的交付持久标记为延后，恢复后不自动从头重播。退出会话后，旧手动流程也不再自动播邀请提示。

“安静”同时暂停自动发布和自动播放；“结束交流”立即停止麦克风与播放。状态同步失败不会使本地恢复出声。后台会话通过非粘性前台服务维持，常驻通知静默提供安静、结束入口；不会开机自启或自动恢复上次公开授权。系统杀进程/离线退出时，服务端撤销是尽力同步，最迟由服务端会话期限结束；本机不后台补发录音。

## 实现边界与失败处理

- 单麦克风 AudioRecord，16 kHz / PCM16 / 单声道；20 ms 帧，约 240 ms 前置缓存，约 1.2 s 静音收束，一段最多 8 s。过长发言标记截断并保持私有，等待完整停顿，不悄悄拆成多条公开内容。
- Android 33+ 优先尝试系统离线识别器读取同一份 PCM；没有可用结果时，非安静的发布会话使用灵矽 STT。离线识别服务存在不等于中文模型可用，界面在有实际识别结果前不宣称指令就绪。
- 完整短句“安静/安静一下/别说了/先别说了/暂停交流”“恢复交流/恢复收听/继续交流”“停一下/停止播放/暂停播放/稍后听”“刚才那句别发/先别发/不要发出去”“关闭麦克风/结束交流/结束免提”在识别后分别触发本地动作，控制短句不发布。断网的具体语音控制依赖可用的本机离线中文识别；未就绪时通过通知操作。任何本地语音起点仍可打断播放。
- 平台 AEC/NS 有则启用；没有 AEC 时，在播放中起声的录音不自动公开。AEC 开启只代表系统能力可用，未证明车内消回声有效。播放及结束后约 500 ms 内起声的转写，还与最近原声做整句、片段和近似文本检查，疑似回声不公开；原声里的控制短句也不执行。文本检查不能替代声学验证。当前没有经过验证的司机/乘客/广播声源区分。
- Android 系统通话/通信模式期间丢弃采集帧并暂停自动播放，原声播放器响应音频焦点丢失。没有车机导航音频联调证据。
- 网络、识别、模型、位置或关系不确定时，保持私有，不循环播报失败。每个账号/服务作用域最多暂存 5 段 WAV，应用私有目录，旧段滚动删除，不自动重传。完整的停车暂存审核/补发页面尚未提供。
- 前一网络轮次未结束时仍识别新录音中的本地控制短句；新增普通发言保留私有，不堆积多个失去现场上下文的公开请求。
- 发送途中断网或取消不能保证服务端没有提交。界面明确显示“结果待核对”，不谎称“未发送”，也不自动重传。当前客户端还没有完整的结果核对历史页。
- “刚才那句别发”先安静；若上一条已知发布结果是本人现场 `scene`，调用真实撤回接口；其他种类不虚构撤回成功。未完成发送的迟到结果由服务端版本与期限复核。

## 新增 HTTP 接口

均沿用 `Authorization: Bearer <本设备凭证>`，用户身份由服务端解析，客户端不能传用户 ID。响应均带 `server_time`；时间使用带时区的 ISO 8601。字段错误 422、身份 401、权限 403、资源不存在 404、版本/上下文改变 409、已结束/过期 410。缺少 Bamboo Key 时，新建会话返回 503 `DECISION_NOT_CONFIGURED`。

### 1. 创建一次会话

`POST /v1/exchange/sessions`

```json
{
  "client_request_id": "11111111-1111-4111-8111-111111111111",
  "expected_revision": 7,
  "place_id": "实际注册的地点ID",
  "auto_publish": true,
  "auto_play": true
}
```

`expected_revision` 来自 `GET /v1/me` 的 profile。至少选择一项，不能用全局 `assist_enabled` 推定公开授权。地点必须注册。不能在全局 quiet 中启动。一次用户仅保留一个活跃/暂停的交流会话；新建会话会终止旧会话。

```json
{
  "session": {
    "id": "exchange_...",
    "state": "active",
    "revision": 1,
    "place_id": "实际注册的地点ID",
    "auto_publish": true,
    "auto_play": true,
    "expires_at": "2026-10-25T02:00:00Z",
    "context_post_id": null
  },
  "server_time": "2026-10-25T01:00:00Z"
}
```

相同用户及请求 ID 的相同内容返回原会话；内容不同返回 409。重复创建不复活已结束的会话。默认一小时，界面不会自动续期或扩大地点。

### 2. 查询、暂停、恢复、结束

`GET /v1/exchange/sessions/{session_id}` 返回上述会话。

`PUT /v1/exchange/sessions/{session_id}`：

```json
{"expected_session_revision":1,"state":"paused"}
```

状态为 `active | paused | ended`。有效状态改变使 revision 增加；ended 不能恢复。Android 安静先停止本机操作，再暂停会话并同步全局 `mode=quiet`；明确恢复时先恢复全局 listen，再恢复会话。旧轮次不能使用新 revision 继续提交。

### 3. 提交自然发言

`POST /v1/exchange/sessions/{session_id}/utterances`

`multipart/form-data` 两部分：`audio` 为这句真人原声 WAV，`metadata` 为 JSON 字符串：

```json
{
  "client_request_id": "22222222-2222-4222-8222-222222222222",
  "expected_session_revision": 1,
  "transcript": "金科路中环路下面堵死了。",
  "recorded_at": "2026-10-25T01:00:03Z"
}
```

服务端验证原声格式、会话授权、录制年龄、真实物理位置新鲜度/精度/距离。客户端选定地点不证明人在现场。原声、转写、会话、请求 ID 绑定为一个幂等指纹；相同内容重试返回原结果，变更音频、文字或会话返回 409，不再次调用模型。Android 不自动重试未知发送。

模型仅选 `scene | question | answer | reply | ignore | defer`。明确否定发布的常见短句由确定性规则先忽略。服务端规则决定真实地点、邀请、收件人和根对话 ID；模型不能生成身份或关系。无合法邀请不能 answer，无可访问的明确上下文不能 reply。模型在数据库事务外运行，返回后复核会话、设置版本、时效、位置和关系。

发布示例：

```json
{
  "turn_id":"turn_...",
  "status":"published",
  "action":"scene",
  "post_id":"p_...",
  "root_id":"p_...",
  "server_time":"2026-10-25T01:00:07Z"
}
```

另有 `status=ignored`，或 `status=deferred, action=defer, reason=...`；不会发布。录音年龄默认最多 60 秒，未来误差最多 10 秒。模型默认最多等待 20 秒，超时暂缓。现场 scene 原声按当下观察发布，默认有效 300 秒；假设、附带条件或不确定描述不应被转成无条件观察。

`GET /v1/exchange/turns/{turn_id}` 查询本人该轮实际结果；其他用户返回 404。失联的 deciding 轮次到期转为 deferred，不能补发。

### 4. 获取适合自动播放的原声

`GET /v1/exchange/sessions/{session_id}/inbox`

返回沿用的 `{items:[delivery...],server_time}`。仅限授权收听、活跃会话、当前地点有效范围：本人本次任务/已选上下文的真实 answer/reply，以及独立相关性决策批准的新鲜 scene。已有当前任务直接回复先返回；其后每轮最多决策一个新 scene 或已批准邀请的真人问题，结果按会话/消息/配置版本缓存，最多返回 10 条。模型输入包含 focus、最近本次发言及当前原声上下文；上下文变化时丢弃旧判定。已屏蔽主题和旧交付尝试不会被新调度重置。本人旧任务、无关广播和系统邀请控制提示不混入自动原声队列。问题以真人原声交付，不能把控制提示当作提问原声。客户端可用重复的 `exclude=<delivery_id>` 查询参数排除已尝试/延后的交付，最多 256 项；不会删除或伪造 played 回执。切换上下文后，旧回复与旧上下文批准的场景留在待听中。

Android 下载后还读取 `GET /v1/inbox/{delivery_id}` 和当前会话复核版本/期限/可执行性；麦克风说话、识别或发布处理、安静、通话、失去音频焦点时不抢播。开始尝试前记录持久 attempt，播放完成才写 `played` 回执；取消、失败、未知进度不自动重播。

### 5. 绑定当前原声上下文

`PUT /v1/exchange/sessions/{session_id}/context`

```json
{"expected_session_revision":1,"post_id":"服务端真实可访问的原声ID"}
```

Android 只在 MediaPlayer 实际开始播放后绑定；播放前取消、焦点失败不会绑定未听原声。上下文写入串行，尚未同步时后续发言保持私有。服务端校验同地点及音频访问权限；随后自然回复引用这个父消息及其真实作者。现场 scene 本身也能作为根：不必制造一个不存在的问题或邀请。为兼容旧协议，reply 的 `root_question_id` 和 conversation 的 `question_id` 字段可承载真实 scene 根 ID；conversation 增加 `root_kind` 区分。scene 撤回或到期后，它的后续对话不再可自动播放/新增回复。

原声下载、`played/failed` 回执、公开声音撤回仍沿用现有声音协议。灵矽承担 STT，业务发布/收听走 Sonar HTTP；模型与地图 Key 只在服务端。

## 配置与可复跑检查

`server/.env.example` 新增：

```dotenv
SONAR_EXCHANGE_SESSION_TTL=3600
SONAR_EXCHANGE_TURN_TTL=60
SONAR_EXCHANGE_DECISION_TIMEOUT=20
SONAR_EXCHANGE_LISTEN_CONFIDENCE=0.65
SONAR_EXCHANGE_OBSERVATION_TTL=300
```

自然交流独立使用 `SONAR_BAMBOO_API_KEY/BASE_URL`，不复用旧邀请决策缓存，也不要求启用旧邀请的 `SONAR_DECISION_ENABLED`。公开判定最低 confidence 0.85；收听最低默认 0.65，两者均要求 none_probability < 0.15。这是试用阈值，尚非车内验证结论。现有收听的回复禁用设置继续生效。

```sh
cd server
uv run ruff check app tests
uv run pytest -q
cd ../android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

新测试：服务端 `tests/test_exchange.py`；Android JVM `ExchangeRulesTest`；模拟器 `ExchangeDeviceTest`。设备测试使用进程内 MockWebServer、明确标记的静音 WAV 和临时账号，覆盖真实前台服务、后台生命周期、MediaPlayer、安静、延后、去重及通知/设置界面；会还原原配置，不发布到公网。运行指定 instrumentation class 即可，不依赖实际灵矽账号。暂存、真人识别、声源辨识、完整云端双人语音闭环需后续真机验收。

## 本轮证据（2026-10-25）

- 服务端 116 项检查通过（含新增自然交流用例），Ruff 通过。隔离用例覆盖现场自动发布、真实 scene 根回复、自然 question/answer、幂等、版本撤销、过期/位置变化、否定发布、模型不确定和监听迟到结果。
- Android 30 项 JVM 检查通过，Debug APK、测试 APK 与 Lint 通过；3 项自然交流与 2 项配置迁移隔离设备检查通过。JVM 覆盖 VAD 前置缓存、静音收束、长句不拆发、脉冲噪声、完整控制短句及自动上传协议；构建与 Lint 单独执行。
- 模拟器检查和渲染截图保存在忽略目录 `android/build/reports/exchange/`。它验证软件状态和音频 API，不是扬声器听感/回声证据。
- 真实 Bamboo 的人工合成文字探测：现场分享能返回 scene（一次约 1.4 秒，一次约 10 秒，也有 12 秒超时，最终等待上限调整为 20 秒）；否定发布返回 ignore；含本次发言上下文的相关候选返回 play，confidence 约 0.69。最终隔离 HTTP + 真实模型链路跑通 scene 发布（约 1.2 秒）→ 相关原声候选（约 1.3 秒）→ 真实 reply 发布（约 4.4 秒），scene/reply 使用同一真实根 ID。仅用合成文本和明确标记的静音 WAV，未发真人原声，不代表稳定延迟或完整误判率验收。
- 2026-10-25 已通过 PR #1 合并并更新公网服务，原有数据、配置与重启检查通过，详见[实际发布记录](../../deploy/deployment-20261025.md)。v0.1.1 APK 已提供下载；本次没有安装到实体手机，真人完整链路仍待真机验收。

发布前独立审查与修复记录见[2026-10-25 发布审查](review-20261025.md)。
