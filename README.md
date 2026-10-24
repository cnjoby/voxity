# 声呐 Sonar

听见同路的声音。

人发起问答，广场保存并组织声音，个人 Agent 按主人的意愿筛选、安排播放并追踪回复。

两天黑客松原型：Android 定位并提问，处在目标附近且愿意互助的参与者收到邀请，自愿回答后提问者听到原声。自有业务服务单机部署，语音交互计划接入 Linx；ESP32 由硬件成员实现。

自然现场交流 `v0.1.1`：一次授权后自动分享明确现场原声，在空隙接续相关真人回复，正常路径保持安静。[接口与能力边界](docs/development/natural-exchange.md) · [发布审查](docs/development/review-20261025.md)。

## 工程入口

| 目录 | 内容 |
| --- | --- |
| [server](server/README.md) | FastAPI、SQLite、原声文件、个人任务及 MCP 适配 |
| [android](android/README.md) | Kotlin、Compose、录音、提问、等待、播放与私有配置 |
| [deploy](deploy/README.md) | 单机容器与 HTTPS 代理配置 |
| [docs/design](docs/design/README.md) | 产品定义、架构与评审 |
| [开发计划](two-day-development.md) | 三端分工、接口契约与验收 |

服务端已完成本地业务闭环；Android 已通过前台定位、真实高德 POI、附近邀请与双用户录音问答的模拟器闭环。定位为显式注入坐标，双方录音由模拟器 AudioRecord 产生，转写和确认由测试输入。另已完成真机 Linx 接入，用户确认私人语音收到对话回复；[公网服务](deploy/deployment-20261024.md)已部署并通过 HTTPS、地图/决策、合成问答和重启检查，完整真人公开问答及 ESP32 仍待验收，详见[服务端交接](server/HANDOFF.md)及[Android 交接](android/HANDOFF.md)。

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

真实位置接入见[定位协议](docs/development/location.md)。Bamboo/StartLux 原生决策已接入实际邀请筛选与提醒，本机已开启，规则检查与人工确认保留，见[个人声音决策](docs/design/attention-decisions.md)。

三类声音扩展已加入服务端与 Android 前台：情境分享、POI 授权运营公告、公开多轮定向回复及独立收听设置。新增协议、运营授权命令、验证结果与模拟器复跑见[声音匹配实现与验收](docs/development/voice-matching.md)。

Android 真机已绑定灵矽，真实 PCM STT/TTS 自动检查通过，用户确认“试说一句”收到对话回复；另一次真人自动检查超时，业务 MCP 仍待联调，见[手机接入记录](docs/development/linx-android.md)。

张江三个地点的 36 条合成测试音频已内置 Android，可在首页切换模拟地点、离线试听或请求手机真实位置。详情见[测试音频与位置切换](docs/development/demo-audio.md)。
