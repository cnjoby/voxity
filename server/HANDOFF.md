# Sonar 服务端交接

日期：2026-10-24。基线 revision：110516f；定位/POI 及决策适配的修改尚未提交。仅记录已执行结果，不代表双端或平台验收。

## 本轮新增

本机 8765 已开启 SONAR_DECISION_ENABLED。新增后台 AttentionWorker 与 deliveries.attention_json；迁移前保存私有快照。一次性修复旧客户端将“现在安静”写入 focus 的旧值，保留真正的 mode 和其他关注内容。决策 Key 与地图 Key 均不进入设备。


已接通真实高德搜索、WGS84转换与逆地理编码，新增真实位置更新、地点搜索、位置/目标独立快照、附近邀请和迟到附近用户召回；检查精度、采样年龄、距离、意愿、静音及任务期限。prompted 邀请保留自愿回应入口。详细接口见[定位接入](../docs/development/location.md)。

本地 8765 服务已更新，迁移前保存私有数据库快照；启动后原 users/profiles/posts/deliveries 的行数均保留，未清空任务。真实 HTTP 健康、本人配置读取和 POI 搜索均通过。

隔离临时库与真实 AMap 上，Android 模拟器两个用户会话已跑通定位、录音提问、提示后录音回应、原声播放和回执；坐标注入、测试文字与确认均明确标记，不是两台实体手机或真人STT证据。

Bamboo/StartLux 已接入实际邀请筛选：硬规则召回、后台异步决策、规则复核后 notify 才交付；ignore/defer 缓存，超时不反复调用。已通过真实模型、地图与 Android 双用户会话闭环及反例。见[决策接入边界](../docs/design/attention-decisions.md)。两类外部 Key 仅在忽略的本地 .env 中。

本轮当前服务端检查为 **71 passed**，Ruff 与格式检查通过。一条上游 TestClient 弃用提示保留；以下“24 passed”等是原实现的历史验证。

## 实现状态

| 项目 | 实现 | 验证 |
| --- | --- | --- |
| S1 | Python工程、uv.lock、私有配置生成、四表、哈希设备身份、幂等seed、health/me/context、只读MCP探针 | 本地通过；Linx真实凭证与透传未验证 |
| S2 | multipart原声草稿、确认发布、查询/取消、音频格式与权限、幂等 | 本地通过 |
| S3 | 每人独立规则、一次性邀请、问题/回复关系、待听、回执、静音/取消/过期/版本 | 本地HTTP通过；无真实ESP32/Android提醒证据，G1整体未通过 |
| S4 | probe、get_feed、set_focus、queue_voice、每用户SSE transport、候选快照、旧指令失效 | 官方Python SDK在本机真实SSE端口通过；尚未接Linx云端 |
| S5 | 自动检查、单进程运行、真实进程重启恢复、Dockerfile/Compose/Caddy配置 | 软件与本机恢复通过；Docker不可用，容器构建/公网部署未执行 |

文件入口：app/main.py（HTTP）、service.py（业务）、mcp.py（SSE适配）、schema.sql（SQLite）、tests/（检查）；部署说明见 ../deploy/README.md。

## 运行环境与结果

本机 macOS/arm64，Python 3.12.13，uv 0.11.11；依赖固定在 uv.lock。当前服务在本机 `http://127.0.0.1:8765` 运行，只有回环可访问，进程结束后按 README 重启。没有公网部署地址。

已执行：

- `uv run pytest -q`：**24 passed**，覆盖三轮API问答、私有草稿与越权、格式、幂等、位置/参与意愿、延迟回复、静音保留待听、旧版本与请求、取消/超时、重启、并发确认及MCP会话隔离。临时数据库与可控时钟。
- `uv run ruff check --config pyproject.toml app tests ../scripts/smoke_http.py`：通过；format检查通过；compileall通过；`uv sync --frozen --check` 不需更改环境。
- 实际启动Uvicorn，运行 `uv run python ../scripts/smoke_http.py --base-url http://127.0.0.1:8765 --rounds 3 --synthetic`：**三轮真实HTTP通过，静音反例通过**。音频是生成的静音WAV，转写及主人确认是模拟输入；下载字节与上传一致，不能证明真人或喇叭出声。
- 实际停止并重新启动Uvicorn：重新查询保留三条已回答问题，三份WAV仍可下载；MCP默认503保持关闭。
- 官方MCP客户端在临时真实回环TCP/SSE端口初始化、列举工具、probe、set_focus、get_feed；B的有效身份不能向A的transport注入请求。该检查不经过Linx。
- git ignore检查：server/.env、数据库、.venv均被忽略；私有配置以600权限创建，实际凭证未写入交接记录。

测试有一条上游Starlette TestClient关于httpx的弃用提示，未造成检查失败；真实HTTP/SSE检查不依赖这一TestClient适配层。

## 配置与接续

本机生成的 `.env` 只包含独立随机设备Token与专用MCP密钥；实际Linx Agent ID为空，MCP_ENABLED=false。客户端成员通过安全渠道取得对应设备配置，不能把两人的Token互换。

后续接续顺序：

1. Android对接alice，ESP32对接bob的业务HTTP；使用专用演示数据，验证下载附带设备凭证、播放前复核、回执与确认。
2. 获取实际Linx绑定与可验证鉴权配置，首要验证专用Bearer能否透传，再填Agent/Device并seed；验证后才启用MCP。身份Header不足以单独授权。
3. 两端各验证录音与STT配对，再跑真实提问与真人回答；不能以静音WAV脚本替代。
4. 取得服务器、域名与Docker环境后构建部署，记录公网HTTPS与恢复证据；再执行 docs/development/integration.md 中的 G1/G2/G3。

未知项：真实Linx账户与凭证透传能力、ESP32现有固件和录放状态、Android接入状态、部署主机/域名。以上不阻止本地业务开发，但阻止宣称完整比赛闭环已经验收。

### 2026-10-24 三类声音扩展

用户要求继续实现产品新增需求后，已增加定向回复、情境/知识/公告、独立收听设置、人工 POI 运营授权、公告替代/撤回、访问与回执历史。现有数据采用增量建表/加列，不重置配置与任务。94 项服务端测试通过；本轮未部署或重启正常服务器，实机/平台结果不沿用历史报告。协议、授权命令及逐场景验证见[声音匹配实现](../docs/development/voice-matching.md)。
