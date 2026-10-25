# ESP32 固件入口

2026-10-26：通过 Git 子模块将同事维护的固件纳入本项目，路径为 [`esp32/`](esp32)。父仓库只记录仓库地址和固定提交，固件源码、历史和权限继续由原仓库管理。

| 项目 | 当前记录 |
| --- | --- |
| 原仓库 | [ma6174/ai-passport](https://github.com/ma6174/ai-passport)，私有 |
| Sonar 分支 | `feature/sonar-voice-client`；默认 `main` 是硬件基础工程，不是本次引用的应用分支 |
| 固定提交 | `9f4618489654d86e9fdc04851cad3df03bdcf77e` |
| 硬件 / 工具链 | ESP32-C3、8 MB Flash、无 PSRAM；ESP-IDF 5.5.3，以该版本仓库规格为准 |
| 许可证 | 原仓库 MIT，保留于 `esp32/LICENSE` |
| 维护责任 | 固件同事在原仓库开发并推送；本项目维护者审核后更新引用 |

## 下载

需要已获得原私有仓库的读取权限，并配置 GitHub Git 鉴权。父仓库公开并不授予固件权限，也不会将固件源码重新公开。

```sh
# 新下载完整项目，包含固定版本固件
git clone --recurse-submodules https://github.com/cnjoby/voxity.git
cd voxity

# 已经下载父仓库：在仓库根目录执行
git submodule update --init --recursive firmware/esp32
git submodule status firmware/esp32
```

初始化会检出父仓库固定的提交，而不是自动使用原分支最新代码。没有固件权限的协作者仍可普通 `git clone`，单独运行 Android 和服务端。GitHub 的 Download ZIP 不包含子模块源码；要取完整固件请用 Git 和有权限的账号。

## 构建入口

```sh
cd firmware/esp32
# 按原仓库环境说明安装并激活 ESP-IDF 5.5.3
idf.py --version
./tools/validate.sh --static
./tools/validate.sh --firmware
```

构建和设备配置以固定版本的下列文档为准：

- [环境安装](esp32/docs/development/engineering/environment-setup.zh_CN.md)。
- [构建与验证](esp32/docs/development/engineering/build-and-test.zh_CN.md)。
- [Sonar 应用说明](esp32/docs/development/sonar-client.zh_CN.md)。
- [板卡规格](esp32/docs/hardware-design/specifications.zh_CN.md)。
- [固件开发约束](esp32/AGENTS.md)。

实际 Wi-Fi、业务 Token 和灵矽绑定留在固件自己的私密配置/NVS，不能加入父仓库。先按子模块文档配置，再执行构建；构建不等于烧录或设备验收。本次没有安装工具链、改写固件、烧录设备或改变原仓库权限。

## 模块与项目分工

下列路径相对 `firmware/esp32/`：

| 固件模块 | 代码入口 | 与本项目的关系 |
| --- | --- | --- |
| 应用状态与界面 | `main/app_state.c`、`main/app_ui.c`、`main/main.c` | 设备交互与状态展示，由固件同事维护 |
| 语音及灵矽 | `main/audio_coordinator.c`、`main/linx_client.c`、`main/linx_ws.c` | 板端采集/播放、STT 与私人助手通道 |
| Sonar 业务与会话 | `main/sonar_client.c`、`main/sonar_session.c`、`main/sonar_net.c` | 调用父仓库服务端，处理会话、原声 URL 与交付回执 |
| 协议与状态辅助 | `main/sonar_http.c`、`main/sonar_config.c`、`main/sonar_store.c`、`main/sonar_wav.c` 等 | HTTP、配置/NVS、原声与内存边界 |
| 板级支持 | `components/bsp/` | 引脚、音频、显示、按键与电池规格 |
| 主机验证与工具 | `tests/`、`tools/validate.sh` | 原固件自己的静态、主机及构建检查 |

服务端的身份、授权、任务与接口规格仍以父仓库 [模块规格](../docs/design/module-specs.md)、[当前接入约定](../docs/development/esp32-ai-handoff.md)、源码与实际 `/openapi.json` 为准。固件分支内部分说明保留了早期按键/录音、ASCII 界面及手动 confirm 流程，现有会话代码已超出这些说明，不能仅靠旧文档推断实际行为；本次没有改写这些上游说明或认定协议全面兼容。需要固件同事按固定提交与实际后端逐项联调。

## 同事继续开发

子模块初始化通常处于 detached HEAD。要在其中提交代码，先进入原仓库的工作分支，并遵守其 `AGENTS.md`：

```sh
git -C firmware/esp32 status --short --branch
git -C firmware/esp32 switch feature/sonar-voice-client
```

固件修改先在原仓库完成审查、测试、提交和推送。父仓库不自动推送子模块提交；必须先确保所引用的固件提交可从原远端取得，再提交父仓库指针。

## 更新本项目引用

先确认子模块没有需要保留的未提交改动，在仓库根目录执行：

```sh
git -C firmware/esp32 status --short --branch
git -C firmware/esp32 fetch origin
git -C firmware/esp32 log --oneline HEAD..origin/feature/sonar-voice-client

# 审阅后更新到配置分支的远端版本
git submodule update --remote --checkout firmware/esp32
git diff --submodule=log -- firmware/esp32

# 验证后将新版本作为父仓库变更提交
git add firmware/esp32
git commit -m "chore: update ESP32 firmware reference"
git push origin main
```

更新时同步本页的固定提交记录。其他协作者拉取父仓库后，执行 `git submodule update --init --recursive firmware/esp32` 获取同一版本。回退父仓库提交后同样执行该命令，即可回到对应固件源码；这不会回退已烧录的实体设备。

## 本次纳入验证

- 确认原仓库私有属性、Sonar 分支、固定提交及 MIT 文件；源码未复制进公开父仓库。
- 检查子模块 URL、分支跟踪配置与 Git 固定引用；完成全新父仓库递归克隆验证。
- 固件构建：未运行；主机测试：未运行；设备测试：未运行。本次只纳入版本引用与文档，原固件及父仓库应用代码均未修改。协议兼容、真实录放和持续会话由后续联调验收。
