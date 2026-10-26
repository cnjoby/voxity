# ESP32 固件入口

2026-10-27：上游 [PR #1](https://github.com/ma6174/ai-passport/pull/1) 已合并，仓库已公开。本地子模块固定到正式主线提交 **60e1a1e998e0e65eb3c655e3a7a8289f4ef9933b**，`.gitmodules` 更新跟踪 `main`；该提交与Sonar分支a960e005的源码树相同，已含异步收件的Ogg播放修复。本次父仓库更新将旧引用9f46184替换为上述正式主线提交。

此前本地未提交的统一播放候选补丁已完整存入子模块stash及忽略目录 `output/esp32-submodule-public-20261027/`，不叠加到正式上游源码；现有Android、服务端和其他文档改动保留。当前实体设备维持原固件，用户已确认的一轮双向出声见[排查与听感记录](../docs/development/esp32-reply-failure-20261027.md#040之后的真实往返)，不代表本次主线构建已烧录。

2026-10-26：通过 Git 子模块将同事维护的固件纳入本项目，路径为 [`esp32/`](esp32)。父仓库只记录仓库地址和固定提交，固件源码、历史和权限继续由原仓库管理。

| 项目 | 当前记录 |
| --- | --- |
| 原仓库 | [ma6174/ai-passport](https://github.com/ma6174/ai-passport)，公开 |
| Sonar 分支 | `main` 已合入Sonar；`feature/sonar-voice-client` 保留开发历史 |
| 固定提交 | `60e1a1e998e0e65eb3c655e3a7a8289f4ef9933b` |
| 硬件 / 工具链 | ESP32-C3、8 MB Flash、无 PSRAM；ESP-IDF 5.5.3，以该版本仓库规格为准 |
| 许可证 | 原仓库 MIT，保留于 `esp32/LICENSE` |
| 维护责任 | 固件同事在原仓库开发并推送；本项目维护者审核后更新引用 |

## 下载

原仓库现已公开，通过HTTPS下载无需额外的私有仓库读取授权。

```sh
# 新下载完整项目，包含固定版本固件
git clone --recurse-submodules https://github.com/cnjoby/voxity.git
cd voxity

# 已经下载父仓库：在仓库根目录执行
git submodule update --init --recursive firmware/esp32
git submodule status firmware/esp32
```

初始化会检出父仓库固定的提交，而不是自动使用原分支最新代码。也可普通 `git clone`，单独运行 Android 和服务端。GitHub 的 Download ZIP 不包含子模块源码；要取完整固件请用Git递归初始化。

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

实际 Wi-Fi、业务 Token 和灵矽绑定留在固件自己的私密配置/NVS，不能加入父仓库。先按子模块文档配置，再执行构建；构建不等于烧录或设备验收。本次仅更新上游引用和验证，不叠加本地候选补丁；没有烧录设备或修改原仓库权限。

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
git -C firmware/esp32 switch main
git -C firmware/esp32 pull --ff-only origin main
```

固件修改先在原仓库完成审查、测试、提交和推送。父仓库不自动推送子模块提交；必须先确保所引用的固件提交可从原远端取得，再提交父仓库指针。

## 更新本项目引用

先确认子模块没有需要保留的未提交改动，在仓库根目录执行：

```sh
git -C firmware/esp32 status --short --branch
git -C firmware/esp32 fetch origin
git -C firmware/esp32 log --oneline HEAD..origin/main

# 审阅后更新到配置分支的远端版本
git submodule update --remote --checkout firmware/esp32
git diff --submodule=log -- firmware/esp32

# 验证后将新版本作为父仓库变更提交
git add .gitmodules firmware/README.md firmware/esp32
git commit -m "chore: update ESP32 firmware reference"
git push origin main
```

更新时同步本页的固定提交记录。其他协作者拉取父仓库后，执行 `git submodule update --init --recursive firmware/esp32` 获取同一版本。回退父仓库提交后同样执行该命令，即可回到对应固件源码；这不会回退已烧录的实体设备。

## 本次引用更新验证

- GitHub确认仓库public、PR #1已合并；主线60e1a1e与Sonar分支a960e005源码树一致。
- 子模块工作区清洁；本地候选补丁可由记录的stash或逐文件备份恢复，其他父仓库改动保持原样。
- Build: PASS；Host tests: PASS（ESP-IDF 5.5.3完整门禁与归档校验）。Device tests: NOT RUN（未烧录，沿用用户选择）。Unverified: 本次主线版本的板上播放、运行固件身份及内存余量。逐项证据见 `output/esp32-submodule-public-20261027/verification.json`。
- 本次验证镜像SHA256：`8cb9ee5432e9f254fd0e69f408e34695a51ba5e7d33e205d76647171b7916f56`；匹配ELF SHA256：`1b6f56a0d512f487d2b26e5cd5651630f4d22c3cfb7415691e90ba3b72d602f9`。归档位于 `firmware/esp32/build/firmware/` 下同镜像SHA目录，二进制不提交或公开发布。
- 父仓库同步提交 `.gitmodules`、`firmware/esp32` 与本页。源码提交、远端推送与设备烧录分别处理；本次未推送或烧录。
