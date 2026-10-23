# 声呐 Sonar

听见同路的声音。

人发起问答，广场保存并组织声音，个人 Agent 按主人的意愿筛选、安排播放并追踪回复。

两天黑客松原型：Android 提问，处在模拟路口且愿意互助的参与者收到邀请，自愿回答后提问者听到原声。自有业务服务单机部署，语音交互计划接入 Linx；ESP32 由硬件成员实现。

## 工程入口

| 目录 | 内容 |
| --- | --- |
| [server](server/README.md) | FastAPI、SQLite、原声文件、个人任务及 MCP 适配 |
| [android](android/README.md) | Kotlin、Compose、录音、提问、等待、播放与私有配置 |
| [deploy](deploy/README.md) | 单机容器与 HTTPS 代理配置 |
| [docs/design](docs/design/README.md) | 产品定义、架构与评审 |
| [开发计划](two-day-development.md) | 三端分工、接口契约与验收 |

服务端已完成本地业务闭环；Android 已通过模拟器对真实 API 的提问、等待与播放检查。模拟器联调使用手动调试转写和明确标注的合成回复。真实 Linx、手机录放、ESP32 和公网部署仍待验收，详见[服务端交接](server/HANDOFF.md)及[Android 交接](android/HANDOFF.md)。

## 启动服务端

需要 Python 3.12 和 uv。在仓库根目录执行：

```sh
cd server
uv sync --python 3.12 --frozen
uv run python -m app.cli init-env
uv run python -m app.cli seed
uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8765 --no-access-log
```

已有 `.env` 时跳过 init-env。设备凭证在本机生成，不随仓库分发。接口文档位于本机 `/docs`。Android 的构建、安装和 USB 端口映射见其[运行说明](android/README.md)。

## 检查

```sh
# server 目录
uv run pytest -q

# android 目录，需 SDK35 与 JDK17/21
./gradlew testDebugUnitTest lintDebug assembleDebug
```

两端与设备的验证步骤见[联调验收](docs/development/integration.md)。运行数据、凭证、APK、构建缓存和路演生成文件保存在本地，不进入源码提交。
