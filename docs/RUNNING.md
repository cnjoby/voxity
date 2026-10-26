# 声呐 Sonar｜运行与验证指南

核对日期：2026-10-27。[旧内容清理后的启动恢复](development/android-stale-question-20261027.md)已安装验证。公告恢复、当日有效与发布时间提示见[当前规则](development/day-validity-20261026.md)。适用范围：本仓库 Android 0.3.6（versionCode 17，Android 10+，已覆盖安装用户手机）与当前服务端源码。[语音故障修复记录](development/voice-start-fix-20261026.md)包含手机平台配置、核验放行、真机 Opus 尾部和播放冲突修复及验收结果。[低延迟与Opus变更](development/low-latency-opus-20261026.md)要求终端处理Ogg Opus下载，配套两端应同步发布，不提供旧客户端转换分支。ESP32 固件由同事独立维护，通过 `firmware/esp32` 子模块固定引用；现为公开仓库，仍需独立 ESP-IDF 环境，见[固件运行入口](../firmware/README.md)。

## 1. 下载与环境

```sh
git clone https://github.com/cnjoby/voxity.git
cd voxity
```

上述普通克隆足够运行 Android 与服务端。需要固件时，再执行 `git submodule update --init --recursive firmware/esp32` 下载固定版本的硬件源码；新下载完整项目也可使用 `git clone --recurse-submodules`。Download ZIP 不包含子模块源码。

| 部分 | 需要准备 | 依赖入口 |
| --- | --- | --- |
| 服务端 | Python 3.12、uv；首次安装可访问依赖源 | `server/pyproject.toml`、`server/uv.lock` |
| Android | Android SDK Platform 35、Build Tools 35、JDK 17 或 21、Python 3；安装设备需 adb | Gradle Wrapper 8.11.1、AGP 8.9.1、Kotlin 2.1.20 |
| 手机语音 | 麦克风、通知权限；实际位置需要定位权限；可用的灵矽设备绑定与网络；本机中文 TTS | [Android 接入说明](../android/README.md)、[灵矽接入记录](development/linx-android.md) |
| 外部服务 | 高德 Web 服务 Key；Bamboo API Key；真实灵矽账号及设备绑定按功能配置 | `server/.env.example`、[平台接入](development/linx-platform.md) |

仅启动 HTTP 服务及运行隔离自动测试不需要真实第三方 Key。完整自然语音会话需要外部服务与设备配置，不是下载源码即可离线运行的功能。

## 2. 启动本地服务端

在仓库根目录执行：

```sh
cd server
uv sync --python 3.12 --frozen
uv run python -m app.cli init-env
uv run python -m app.cli seed
uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8765 --workers 1 --no-access-log
```

`init-env` 只在 `.env` 不存在时执行；它以 0600 权限生成不同的 Alice、Bob 业务 Token 和 MCP 专用密钥。已有配置时跳过，不覆盖。`seed` 可重复执行，保留既有个人状态和任务。运行数据默认写入 `server/data/`，包含 SQLite 和音频。

在另一个终端验证：

```sh
curl --fail http://127.0.0.1:8765/healthz
```

返回 HTTP 200。浏览器打开 `http://127.0.0.1:8765/docs` 查看当前源码生成的 API；`/openapi.json` 为接口清单。业务请求需要对应用户的 `Authorization: Bearer <设备Token>`，两台设备使用各自身份。MCP 密钥与设备 Token 分开。

当前实现有进程内候选、SSE transport 和模型任务，必须使用单个 worker。修改配置后重启服务。停止本机 Uvicorn 使用 Ctrl-C；正常重启保留数据目录。

### 按功能补齐配置

编辑本地 `server/.env`，不要将实际值提交到仓库：

| 变量 | 用途及缺失时的行为 |
| --- | --- |
| `SONAR_ALICE_TOKEN` / `SONAR_BOB_TOKEN` | `init-env` 生成；不同且各至少 32 字符。缺少则 seed 失败 |
| `SONAR_AMAP_KEY` | POI、地理及天气/路线工具；缺少时相关能力返回不可用 |
| `SONAR_BAMBOO_API_KEY` | 语义意图与自然交流判定；缺少时拒绝新自然交流会话 |
| `SONAR_DECISION_ENABLED` | 控制后台注意力标记；不是自然交流模型依赖的总开关 |
| `SONAR_MCP_ENABLED` / `SONAR_MCP_SECRET` | 默认关闭；完成可信平台鉴权及身份映射后才启用 |
| `SONAR_*_DEVICE_ID` / `SONAR_*_AGENT_ID` | 灵矽身份与 Alice、Bob 的可信映射；使用各自实际绑定信息 |
| `SONAR_DATA_DIR` | 本机默认 `./data`，相对于服务启动目录；测试与部署分别选目录 |

新库初始用户没有真实现场位置。由设备更新真实位置或明确选择远程地点；远程地点不代表 GPS 到场，也不会因为提到地点名称就获得现场身份。

## 3. 构建与安装 Android

在 SDK Manager 安装 Platform 35、Build Tools 35，在本机设置 `ANDROID_HOME` 为实际 SDK 目录，或创建忽略的 `android/local.properties`：`sdk.dir=/实际/Android/SDK/路径`。将 `JAVA_HOME` 指向实际 JDK 17/21。

在仓库根目录执行：

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
adb devices
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 使用 Android Studio 自带 JDK 时，可在 Gradle 命令前加：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
```

首次构建自动运行 `tools/prepare_voice_assets.py`，下载并校验固定 SHA-256 的 Vosk 中文控制模型，再放入生成的 assets。需要访问模型下载源；离线构建可用环境变量 `SONAR_VOICE_MODEL_ZIP` 指向同一校验和的 zip。模型许可在 `app/src/main/assets/licenses/`。Gradle 缓存和模型下载缓存无需上传。

Debug APK 可安装；Release 默认未签名，不能作为直接安装交付。`install -r` 只有签名兼容时才能覆盖现有安装；不要为绕过签名问题卸载仍需保留配置的设备。

### 连接本地服务

USB 连接手机或模拟器后，在 `android/` 执行：

```sh
adb -s YOUR_SERIAL reverse tcp:8765 tcp:8765
```

在 App 准备页的连接设置中，将地址改为 `http://127.0.0.1:8765`，填入本地 `.env` 生成的 Alice 设备 Token。默认地址是既有公网服务，自己运行本地实例时必须显式修改。Bob 使用独立设备与 Bob Token。

Debug 仅对本机调试域名放行明文 HTTP；远端和 Release 使用 HTTPS。手机的 `127.0.0.1` 指向手机自身，USB 本机连接需要上述 reverse。Android 模拟器也可用 `http://10.0.2.2:8765`。

全新 Debug 安装可选用 `python3 tools/configure_device.py --serial YOUR_SERIAL`，从同仓库 `server/.env` 私密注入 Alice 配置。已有灵矽绑定时使用界面修改业务地址及 Token，避免 bootstrap 改写其他配置。旧脚本的 `--mock`、`--debug-transcript` 参数不作为当前语音优先体验的运行方法。

### 实际使用

1. 在准备页完成业务连接、灵矽绑定，确认本机中文 TTS 可用；给予需要的系统权限。
2. 选择明确标为远程的地点，或更新当前设备真实位置。选择地点与向服务器提供 GPS 是不同操作。
3. 点击“开始”进入当前语音会话；使用“我想闲聊”“上海明天天气怎样”“帮我问问某地点……”表达意图。问现场前需要目标地点及对应授权。
4. 使用“安静”“结束”等控制；真人回复播放原声，Agent 文本使用合成语音。结束后不会通过应用重启自动重新授权。

真人匹配需要另一位参与者在线、资格有效并明确愿意参与；无人回应应保留等待或超时。ESP32 持续聊天和车内声学效果需要固件及实机单独验收。

## 4. 可选合成素材

仓库包含 36 条有校验清单的合成示例，以及 3 条模力社区合成入园提示。均不是实时路况、正式运营公告或真人现场录音。服务端素材预览读取 `server/demo-assets/`；要让它们成为专用本地测试库中的声音，可显式执行：

```sh
# server 目录，先完成 init-env / seed；只对专用测试库执行
uv run python -m app.demo_audio --shared
uv run python -m app.poi_notices --assets notice-assets
```

第二条需要目标 POI 已存在；`Service` 启动时会登记当前试点快捷点。导入为幂等操作，不会把素材改成真人声音。长期提示直到撤回或替代；这些导入命令会写入所选数据目录，因此不作为普通健康检查执行。

## 5. 软件验证与设备验收

```sh
# server 目录：临时数据库、替代供应商
uv run pytest -q
uv run ruff check --config pyproject.toml app tests ../scripts/smoke_http.py
uv run ruff format --config pyproject.toml --check app tests ../scripts/smoke_http.py
uv run python verify_release.py --synthetic

# android 目录：不安装或控制真实设备
./gradlew testDebugUnitTest lintDebug assembleDebug
```

`verify_release.py` 使用隔离临时库和静音 WAV，验证公告检索、撤回、双用户问答、请求核对、任务结束及数据库完整性。设备测试位于 `android/app/src/androidTest/`，其中含真实服务和安装检查；先按对应测试条件配置，再选择性执行，不把所有设备测试作为无条件启动命令。

代码测试、APK 构建、实际部署、真实设备录放和用户验收分别记录。当前上传验证见[交付记录](delivery/github-20261026.md)，完整实机流程见[联调验收](development/integration.md)。

## 6. 部署与维护

systemd/Caddy 模板、备份与既有发布记录见[部署说明](../deploy/README.md)。本次上传源码不执行部署，也不重置线上配置和数据。Compose 是保留的备选方案，不能用本机测试通过推断其已验收；Dockerfile 当前仅打包应用代码，如需素材预览须另打包或挂载素材目录。

排查入口：`/healthz` 确认进程；401 检查设备身份；503 检查对应上游配置；无法访问电脑检查 reverse/地址；无法出声分别检查下载、播放器、系统音量及设备回执。`played` 只能说明客户端报告结果，不能证明实体喇叭确实发声。持久化数据备份须包含完整 SQLite/WAL 与音频，操作前按部署说明停服务并保存一致副本。
