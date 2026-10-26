# Sonar 服务端

当前生产 **20261027-modaily-alias-040**：普通车位问题按授权直接问人；明确私人查找不公开，真人原声来源附play_audio动作。修复实测ASR“摩利社区”触发错误地点澄清。284项服务端测试、Linux隔离验收及健康检查通过；切换时111条消息/音频和身份、配置保留。[本轮证据与设备验收](../docs/development/esp32-reply-failure-20261027.md#039040与android听感对照)。

历史生产 **20261026-parking-context-038**：同地点重复提问及新会话不再被旧等待问题拦住，直接更新并发布本轮原声；当前会话改问另一个地点仍确认替换。[原因与部署](../docs/development/parking-direct-20261026.md#038旧等待问题触发重复确认)。

2026-10-26 当前生产 **20261026-parking-direct-037**：已授权会话的车位询问在无有效信息时直接转发，不再确认是否问在场的人；地点澄清与已有信息查询保留。[部署与验收边界](../docs/development/parking-direct-20261026.md)。

2026-10-26 当前生产 **20261026-day-validity-036**：普通动态信息、邀请与回答按北京时间发布当日24:00截止；显式长期入园公告保留。修复公告范围恢复，自动候选覆盖当日有效信息，payload新增`playback_intro`。[规则、迁移和验证](../docs/development/day-validity-20261026.md)。

2026-10-26 新增[低延迟与Opus链路](../docs/development/low-latency-opus-20261026.md)：Ogg Opus上传校验、压缩原声存储/下载、终端托管语音的MCP写操作门禁与明确回复修复。新增PyAV锁定依赖，请执行`uv sync --frozen`；当前发布为`20261026-opus-contract-031`（[音频格式核对](../docs/development/opus-interface-20261026.md)），在030基础上补齐OpenAPI并验证Opus utterance，见[复审与发布验证](../docs/development/review-deploy-opus-20261026.md)。预生成语音需通过平台核验名单，默认不启用。压缩音频下载返回`audio/ogg`，终端需支持Opus。

2026-10-26 当前源码、环境配置与可复制命令见[统一运行指南](../docs/RUNNING.md)；当前模块与职责见[模块规格](../docs/design/module-specs.md)。下文包含早期实现基线，旧固定目标地点、仅 confirm 发布和 MCP 工具清单不能代替当前契约。当前自然会话采用分项授权，MCP 兼容 Streamable HTTP 与旧 SSE。

FastAPI + SQLite + 音频文件，单进程运行。已实现 S1–S3 的本地业务链路及 S4 的 MCP 适配；平台与设备验收状态见 [HANDOFF](HANDOFF.md)。[三端契约](../docs/development/contracts.md)约定字段与交互。

## 本机启动

在项目根目录执行：

```sh
cd server
uv sync --python 3.12 --frozen
uv run python -m app.cli init-env
uv run python -m app.cli seed
uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8765 --no-access-log
```

init-env 只创建不存在的 `.env`，权限为600，随机生成两份独立设备凭证和 MCP 专用凭证，不打印凭证。已有 `.env` 时跳过此步，禁止覆盖已有配置。seed 可重复执行，更新身份绑定但保留个人配置、位置时间、声音和任务；过期位置需客户端明确刷新。

默认本机入口：`http://127.0.0.1:8765`；`/healthz` 无需登录；`/docs` 与 `/openapi.json` 提供可执行接口说明。默认监听回环，只用于本机。对接手机和 ESP32 时由部署负责人提供可访问的 HTTPS 地址，不把 localhost 填到设备上。

设备凭证通过本地 `.env` 中 SONAR_ALICE_TOKEN / SONAR_BOB_TOKEN 分别注入对应客户端，平台凭证另配。不得把凭证写入 URL、仓库、运行截图或交接输出。运行数据默认在 `server/data/`，已忽略。

## 检查

在 server 目录执行：

```sh
uv run pytest -q
uv run ruff check --config pyproject.toml app tests ../scripts/smoke_http.py
uv run ruff format --config pyproject.toml --check app tests ../scripts/smoke_http.py
uv run python ../scripts/smoke_http.py --base-url http://127.0.0.1:8765 --rounds 3 --synthetic
```

最后一条需要服务已启动、alice/bob 是专用演示账号且没有等待中的旧问题。它生成**静音 WAV 和测试转写**，模拟确认与回执，验证真实 HTTP 上的三轮问答与静音反例，不验证真人录音、STT、实际出声或设备按键。失败时保留状态便于调查，不自动删除旧数据。请在专用测试数据目录运行。

自动测试使用临时数据库与可控时钟；MCP 测试启动临时回环端口并使用官方客户端执行真实 HTTP/SSE，然后关闭该测试服务器。

## Linx MCP 接入

默认 `SONAR_MCP_ENABLED=false`，GET /mcp 返回503，尚未配置任何真实平台账户。

对接负责人需在 Linx 实测自定义 `Authorization: Bearer <SONAR_MCP_SECRET>` 是否能透传，并填入两个真实 Device/Agent ID，匹配现有用户后重新 seed。只有确认可信鉴权与绑定后开启 MCP。SONAR_MCP_SECRET 至少32字符，且不得作为设备 Token 使用。

入口 `GET /mcp`、`POST /mcp/message`（同时接受尾斜杠）。每次请求均校验专用凭证与平台 Device/Agent/Session Header。每个用户拥有独立 SSE transport，不能以 B 的身份向 A 的 transport 投递消息。身份 Header 自身不构成鉴权。HTTPS 代理仅接收配置域名，MCP 不依赖 Host Header 提供身份授权。

工具：只读 probe（检查可信身份）、get_feed、set_focus、queue_voice。后三个复用同一业务层，不能自动发布草稿。配置变化旋转待播 request_id，旧回执与旧 feed 不能继续操作；自然语言设置偏好不刷新现场位置。get_feed 快照仅进程内有效，重启后必须重查。

若平台无法透传已验证凭证，继续关闭 MCP 并报告阻塞，不能开放仅 Header 鉴权代替它。后续可在取得账户信息后验证可支持的认证通道。

## 实现约束

- 只有显式 confirm 才公开原声；服务端不进行 STT，不保证客户端提交的转写准确或替代真人确认。
- 问题目标固定中环路金科路口，模拟位置、方向与位置时效独立于提问文本。仅按参与者个人配置生成一次邀请，不因轮询重新邀请。
- 静音保留自己的问题与待听回复，撤销收到的有效邀请。回答与播放是独立状态。
- 超时在业务访问时处理，不运行额外调度系统；重启后首个请求即复核有效性。事务内不调用平台或外部网络。
- 发布状态与交付入队在同一 SQLite 事务；原声在数据库提交前落盘。意外断电可能留下未引用的文件，不会使其公开。
- 使用整段 WAV 下载，无 Range、离线系统推送或全城推荐；Android 前台定位与 POI 接口见[定位接入](../docs/development/location.md)。SSE 与候选快照要求一个应用进程，不能启动多个 worker。
- 服务端不能证明喇叭真正播过；played 是客户端回执，设备必须独立验收。

## 位置与决策配置

[定位接入](../docs/development/location.md)定义真实位置、POI 和附近邀请。高德 Web Key 仅放在本地 `.env` 的 `SONAR_AMAP_KEY`；缺失时地点接口明确返回 503。默认不允许模拟器位置取得真实附近资格，测试须使用隔离库。

[Bamboo 决策适配](../docs/design/attention-decisions.md)使用 `SONAR_BAMBOO_API_KEY` 和原生决策接口。设置 `SONAR_DECISION_ENABLED=true` 后参与实际邀请：规则召回、后台决策、规则复核、notify 才提醒。超时/故障暂缓并缓存，不阻塞问答 HTTP；自己的回答原声照常送达。本机已开启；仍限单应用进程。

## 三类声音扩展

本轮增加情境原声发布/浏览、POI 授权频道及版本公告、公开问答连续定向回复；静音、回复、运营频道、情境与互助各自按规则处理。接口、配置、授权命令、访问去重与复测见[声音匹配实现与验收](../docs/development/voice-matching.md)。实体到达、楼层与真人语音仍待验证。
