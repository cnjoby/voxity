# Sonar 服务端

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
- 使用整段 WAV 下载，无 Range、GPS、离线系统推送或全城推荐。SSE 与候选快照要求一个应用进程，不能启动多个 worker。
- 服务端不能证明喇叭真正播过；played 是客户端回执，设备必须独立验收。
