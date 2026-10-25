# 单机部署

2026-10-26 当前生产 **20261026-conversation-first-025**：仅更新五份后端文件，保留024公告和全部数据／配置；模型标记不再阻断有效设备邀请，修复Agent闲聊与天气预报。[部署与验证](../docs/development/conversation-first-20261026.md)。手机无需更新。

2026-10-25 当前服务为 **`20261025-modaily-entry-024`**：模力社区三条长期入园公共提示已发布，直到修改或撤回；支持长期须知和合成来源，保留前版语音跟踪更新。来源核对、数据备份、226项回归与公网下载验证见[发布记录](../docs/development/modaily-entry-notices-20261025.md)。本次无需更新手机或ESP32。

2026-10-25 此前语音排障版本 **20261025-audio-trace-023**，配套Android0.2.3：[语音排障和部署记录](../docs/development/audio-incident-20261025.md)。音频接口仍为原HTTP路径和PCM WAV，新增传输／回执日志以排查ESP32先报played却无声的问题；本次公告更新保留这些日志。

2026-10-25 此前版本：**20261025-location-switch-021**（Android0.2.1），地点二选一及真实高德快捷点更新；备份、只改一份服务器文件、原配置保护和公网检查见[本轮记录](../docs/development/location-switch-20261025.md)。下文0.2.0为前版基线。

2026-10-25 当前版本为 **`20261025-voice-first-020`**，配套Android0.2.0。原始高德POI ID、远程点位、默认私有语音会话已上线，Bob固定模力社区。130条原记录、身份、音频与服务凭证保留；生产备份、迁移、27份文件清单及公网检查见[当前实现与部署](../docs/development/voice-first-20261025.md)。HTTP／HTTPS继续可用。下文均为历史版本基线。

2026-10-25 先前版本 `20261025-modaily-shared-final`：[点位与共享音频记录](../docs/development/simulated-points-20261025.md)。

2026-10-25 先前版本为 `20261025-snr34-da13c3f0`：SNR-001–004 与语音网络后端已部署，原数据、绑定、HTTP/HTTPS 和实际重启检查通过。源码来自已校验的工作区文件集合，见[该次发布记录](deployment-snr-003-004-20261025.md)。旧 `7b2c6ec` 的[发布记录](deployment-20261025.md)及下文 2026-10-24 部署内容保留为历史基线。

2026-10-24 已部署到 Ubuntu 24.04：业务地址 `https://122.195.131.106`，
[健康检查](https://122.195.131.106/healthz)、[接口文档](https://122.195.131.106/docs)。
本次采用 Python 3.12 虚拟环境、systemd 单进程服务和 Caddy 公网 IP 证书；
高德、Bamboo、公网 HTTPS 三轮合成问答与重启恢复已验证。
运行路径、配置迁移、验证边界和运维命令见 [实际部署记录](deployment-20261024.md)。

## ESP32 HTTP 联调入口

2026-10-25 为内存受限 ESP32 增加业务地址 `http://122.195.131.106`（TCP 80）。
`/healthz` 和 `/v1/*` 直接提供 HTTP，不跳转 HTTPS；设备仍需原有 Bearer Token，音频相对路径和上传协议相同。
HTTP 会明文传输设备凭证和音频，仅作为本轮联调入口。现有 HTTPS、证书自动维护、App 地址和 Linx WSS 保留。
`/docs`、`/mcp` 等其余路径继续跳转 HTTPS。
实际检查和回退方式见 [HTTP 联调记录](esp32-http-20261025.md)。

## 当前 systemd 部署

模板位于 [voxity.service](systemd/voxity.service) 和 [Caddyfile.ip](systemd/Caddyfile.ip)。
设备使用上述业务地址和原有设备 Token；高德与 Bamboo Key 只保存在服务器。
手机配置页修改业务地址时需保留现有 Linx 配置；不要用覆盖全部配置的 bootstrap 脚本迁移已绑定手机。

服务器访问 Docker Hub 超时，因而本次没有使用容器。原 Compose 方案保留如下，
容器构建仍未验证；原生部署成功不代表容器方案通过。

## Docker Compose 备选方案

需要真实服务器、Docker Compose、域名与80/443端口可达。DNS 指向该服务器后，由 Caddy 获取证书；SONAR_DOMAIN 填真实主机名，不能使用示例地址宣称上线。

1. 在 server 目录初始化私有 .env；填好客户端及平台绑定，MCP 默认关闭。
2. 从项目根目录运行，先构建，再 seed，最后启动服务：

```sh
export SONAR_DOMAIN=your-real-hostname
# Replace the value above with the actual hostname before running.
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml run --rm sonar .venv/bin/python -m app.cli seed
docker compose -f deploy/compose.yaml up -d
```

Sonar 不发布宿主机端口，只有同机 Caddy 对外提供 HTTPS。SQLite 与音频同置 sonar_data 卷，证书单独保存；应用以 uid10001 运行。备份时先停应用并备份完整数据卷（含 SQLite WAL 和原声），不要只复制主数据库文件。

部署后分别检查真实域名的 /healthz、设备身份、公开问答、MCP SSE 与重启恢复。容器健康检查不证明设备或 Linx 接入成功。不要执行 `down -v` 删除比赛数据；没有实际部署证据前，S5 的部署部分保持待验收。
