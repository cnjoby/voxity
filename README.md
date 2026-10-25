# 声呐 Sonar

听见同路的声音。

声呐是以免提语音为入口、围绕地点和事件连接人与信息的交互语音网络。用户说出需要，个人 Agent 查询有来源的信息、联系愿意回应的现场参与者，并追踪原声回复。私人助手对话、公开声音、个人任务与每位收件人的播放状态分别管理。

## 当前源码与交付

2026-10-26 核对：Android **0.2.3（versionCode 9）**；服务端包含会话优先与天气路由修复，既有发布标识为 `20261026-conversation-first-025`。本次 GitHub 上传验证与历史部署/设备验收分别记录，不据此宣称重新部署。

| 交付内容 | 入口 |
| --- | --- |
| 下载、依赖、启动、构建、安装与使用 | [完整运行指南](docs/RUNNING.md) |
| 当前实现、模块规格、接口、状态所有权与角色分工 | [架构与模块规格](docs/design/module-specs.md) |
| 同事维护的 ESP32 固件、固定版本及获取/更新方法 | [固件入口](firmware/README.md) |
| 产品定义、六要素消息、业务线与下一阶段设计 | [产品架构](docs/design/product-architecture.md) |
| 最新现有 PPT、讲述备注、内嵌/独立原理动画 | [路演材料](docs/presentations/README.md) |
| 本次上传范围与验证证据 | [2026-10-26 交付记录](docs/delivery/github-20261026.md) |

本仓库包含现有 Android、服务端、测试、脚本和部署配置。ESP32 固件由同事独立维护，通过 `firmware/esp32` Git 子模块引用原私有仓库的 Sonar 分支，获取源码需要原仓库权限；[下载与更新方式](firmware/README.md)，对接使用[当前固件接入规范](docs/development/esp32-ai-handoff.md)。灵矽、高德和 Bamboo 为外部服务，实际 Key、设备绑定及运行数据不随源码分发。

## 快速启动服务端

需要 Python 3.12 与 uv。在仓库根目录执行：

```sh
cd server
uv sync --python 3.12 --frozen
uv run python -m app.cli init-env
uv run python -m app.cli seed
uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8765 --workers 1 --no-access-log
```

已有 `.env` 时跳过 `init-env`。健康检查为 `http://127.0.0.1:8765/healthz`，接口文档为本机 `/docs`。完整语音体验需配置高德、Bamboo 和灵矽；只启动服务及运行隔离测试不需要这些真实服务凭证。

## 构建 Android 与检查

Android 需要 SDK35、Build Tools 35、JDK17/21、Python3，首次构建会下载校验固定版本的离线中文控制模型。先配置实际 SDK 与 JAVA_HOME，再执行：

```sh
# android 目录
./gradlew testDebugUnitTest lintDebug assembleDebug
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk

# server 目录
uv run pytest -q
uv run python verify_release.py --synthetic
```

手机默认连接既有公网地址。使用本地服务时，需通过 `adb reverse tcp:8765 tcp:8765`、App 连接设置和本地生成的设备 Token 显式切换；灵矽身份独立配置。具体步骤与使用说明见[运行指南](docs/RUNNING.md)。

## 工程与设计入口

| 目录 / 文档 | 内容 |
| --- | --- |
| [server](server/README.md) | FastAPI、SQLite、原声、任务、模型/地图适配与 MCP |
| [android](android/README.md) | Kotlin、Compose、语音运行、录放与私有配置 |
| [firmware](firmware/README.md) | ESP32-C3 固件子模块、构建入口与版本协作 |
| [scripts](scripts) | 合成素材生成及 HTTP/语音网络检查脚本 |
| [deploy](deploy/README.md) | systemd、Caddy、Compose 备选及历史部署记录 |
| [设计文档](docs/design/README.md) | 产品定义、交互、架构和审查 |
| [规划索引](docs/planning/INDEX.md) | 开发任务、实现记录和商业叙事 |
| [v1 契约](docs/development/contracts/v1/schemas.json) | 消息、动作、授权与结果 Schema |
| [联调验收](docs/development/integration.md) | 软件、平台与实际设备的验证分界 |
| [原两天计划](two-day-development.md) | 初始三端分工与任务，作为历史排期保留 |

## 当前行为与历史证据

- [2026-10-26 会话优先与天气修复](docs/development/conversation-first-20261026.md)：有效设备邀请不等待模型标记；私人闲聊与天气日期路由修复。该记录包含此前生产与手机结果，本次上传不重做其设备验收。
- [0.2.3 语音排障](docs/development/audio-incident-20261025.md)：收发阶段、回执、原声播放增益与园区范围；持续真人聊天和 ESP32 输出按设备结果验收。
- [语音优先实现](docs/development/voice-first-20261025.md)：一个“开始”后以语音完成查、问、听、回复、重播、取消、安静和结束；旧 Mock 与内置地点素材已移除。
- [地点切换](docs/development/location-switch-20261025.md)：真实高德 POI 远程选择与当前设备真实位置分开。
- [SNR-001/002](docs/development/snr-001-002.md)、[SNR-003/004](docs/development/snr-003-004.md)、[SNR-005](docs/development/snr-005.md)：统一合同、检索、任务与 Android 增量闭环。
- [声音匹配](docs/development/voice-matching.md)、[自然交流](docs/development/natural-exchange.md)、[灵矽平台](docs/development/linx-platform.md)、[定位协议](docs/development/location.md)：按各自日期理解实现及验证范围。

最新现有 PPT 原样归档，部分附录状态早于当前源码；以当前架构、运行指南和交付记录为工程入口。运行数据、凭证、APK 和构建缓存留在忽略目录；明确归档的路演成品位于 `docs/presentations/`。
