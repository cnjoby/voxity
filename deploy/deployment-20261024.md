# Voxity / Sonar 公网部署记录

日期：2026-10-24，Asia/Shanghai。服务端基线 revision：`1265b4d03ba02a00046e0f676ead1762b034933d`。
初始部署包来自当时工作目录的 `server/app`，逐文件 SHA256 写入服务器的
`/opt/voxity/current/deployment-manifest.json`，部署时已现场比对一致。未提交的 Android、音频和其他开发修改保留。
23:27 起另一个聊天「厘清灵矽在项目中的用途」在同机配置 Linx/MCP，修改了 `app/mcp.py` 并重启服务；
该文件目前已与初始 manifest 不同。这里记录部署基线，不覆盖那边仍在进行的联调。

## 环境与入口

| 项目 | 实际值 |
| --- | --- |
| 公网地址 | `122.195.131.106` |
| 系统 | Ubuntu 24.04.3 LTS，x86_64，2 vCPU，约 3.8 GiB RAM |
| 初次检查磁盘 | 系统盘约 48 GiB，剩余约 46 GiB |
| 业务地址 | `https://122.195.131.106` |
| 健康检查 | `https://122.195.131.106/healthz` |
| 接口文档 | `https://122.195.131.106/docs`，已在浏览器检查实际渲染 |
| 运行方式 | Python 3.12.3、锁定的生产依赖、Uvicorn 1 worker、systemd |
| 代理 | 官方 Caddy 2.11.7 Linux amd64，分发包经官方 SHA512 校验 |
| 监听 | 应用 `127.0.0.1:8765`；Caddy 管理端 `127.0.0.1:2019`；公网 TCP 80/443 |
| 防火墙 | UFW 已启用，只放行 TCP 22、80、443，包含 IPv6 对应规则 |

服务器初查只有 SSH，没有既有业务、数据库或容器。Docker Hub 连接超时；采用原生部署，
保持现有单进程、SQLite、文件音频和 Caddy 架构。没有安装 Docker，也没有以本次结果宣称容器构建成功。

## 运行文件和配置

| 内容 | 服务器路径 |
| --- | --- |
| 当前版本 | `/opt/voxity/current` → `/opt/voxity/releases/20261024-1265b4d` |
| 生产依赖 | 当前版本的 `.venv`；从 `uv.lock` 导出，按哈希离线安装 |
| 私有环境 | `/etc/voxity/server.env`，最终只读检查时 `root:root`，640，由 systemd 读取 |
| 数据库与音频 | `/var/lib/voxity/sonar.sqlite3`、`/var/lib/voxity/audio/` |
| 服务单元 | `/etc/systemd/system/voxity.service` |
| 代理配置 | `/etc/caddy/Caddyfile`、`/etc/systemd/system/caddy.service.d/voxity.conf` |
| 证书状态 | `/var/lib/caddy/.local/share/caddy/` |
| 初始数据备份 | `/var/backups/voxity/bootstrap-20261024/`，目录 700、私有文件 600 |

从本地私有 `.env` 安全传输 Alice/Bob 设备凭证、专用 MCP 密钥、高德 Web Key 和 Bamboo Key，
没有将密钥放入源码、文档、URL、命令行参数或 APK。仅将远端 `SONAR_DATA_DIR` 改为
`/var/lib/voxity`；该值必须使用绝对路径，systemd 的 EnvironmentFile 优先于 Environment 默认值。
`SONAR_DECISION_ENABLED=true`。本次部署验证时 `SONAR_MCP_ENABLED=false`；
另一个 Linx 聊天随后已设为 true 并写入 Alice/Bob 平台绑定，联调仍在进行。
本聊天不把 MCP 开关打开等同于完成平台调用验收，也不覆盖其配置。

用 SQLite backup API 取得一致快照并复制音频；迁移 3 个账号、3 份个人配置、89 条声音、16 条交付和 89 份 WAV。
原本地服务与数据未迁走或删除。重启后上述行数、音频文件集合及全部音频 SHA256 保持一致。
远端以独立 `voxity` 无登录系统账号运行，源码只读，只有数据目录可写。
已将本机现有 SSH 公钥加入 root 的 authorized_keys 用于部署；保留原 SSH 登录方式。

客户端尚未自动改地址：在手机“连接设置”把业务地址改为 `https://122.195.131.106`，
保留原 Alice Token 和 Linx 配置。现有 `configure_device.py` 会整体替换连接配置，
可能清掉已绑定的 Linx Token，不能直接用于已绑定手机的此次地址迁移。

## 高德和 Bamboo 验证

- 服务器真实请求高德搜索、WGS84 坐标转换、逆地理编码：均返回 `status=1`、`infocode=10000`。
- 公网业务 API `/v1/places` 使用设备鉴权搜索：HTTP 200，返回 5 个地点。
- 当前高德 Key 对该服务器没有出现 IP 白名单拒绝；未修改高德账号控制台的白名单。
  这证明当前调用可用，不证明控制台启用了白名单限制。
- 服务器使用原生 Bamboo 决策接口：相关路况样例 `notify / MODEL_DECISION`；
  无关冰淇淋样例 `ignore / MODEL_DECISION`。
- Linx 动态设备凭证仍由手机管理；本部署聊天没有更换或重绑定设备。
  另一个 Linx 聊天正在处理智能体及 MCP，不能沿用本次初始关闭状态描述其最终结果。

## HTTPS 和验证结果

Caddy 使用 Let's Encrypt `shortlived` profile，公网 IP 写入证书 SAN，证书由公信 CA 签发，
不需要安装自签根证书。`default_sni` 指定公网 IP，处理 IP 客户端不发送 SNI 和服务器 NAT 的情况。
首次证书有效期为北京时间 2026-10-24 22:22:08 至 2026-10-31 14:22:07；
Caddy 已开启自动维护，需保留证书目录和 80/443 可达。
证书申请曾因海外连接超时重试，随后由服务器自行完成签发；实际未来续期尚未发生。
相关机制见 [Caddy TLS 配置](https://caddyserver.com/docs/caddyfile/directives/tls) 和
[Let's Encrypt shortlived profile](https://letsencrypt.org/docs/profiles/#shortlived)。

| 检查 | 本次结果与边界 |
| --- | --- |
| 本机服务端自动测试 | 96 passed；一条上游 Starlette/httpx 弃用提示 |
| 生产源码核对 | 初始部署逐文件 SHA256 与 manifest 一致；随后另一个 Linx 聊天更新了 app/mcp.py，原始 manifest 保留 |
| 公网健康、TLS | HTTPS 200、系统信任库校验通过；HTTP 308 跳转 HTTPS |
| 公网设备鉴权 | Alice/Bob 均 200；缺失/伪造凭证均 401 |
| 公网音频 | 授权下载 200、151118 字节，与本地原文件 SHA256 一致；无凭证 401 |
| MCP | 初始部署检查为公网 503；随后由另一个 Linx 聊天开启并联调，当前无凭证请求应为 401 |
| 三轮问答与静音反例 | 服务器回环及真实公网 HTTPS 均通过；隔离数据库、合成静音 WAV、模拟确认与回执，决策关闭以隔离业务检查 |
| 服务恢复 | 实际重启 Voxity/Caddy，健康、数据库完整性、行数和 89 份音频哈希通过；二者 active/enabled，无 failed unit |
| 暂时检查入口 | 公网合成检查结束后已移除临时路由，停止临时服务并删除隔离测试库；正式数据没有混入合成检查记录 |
| 设备/用户体验 | 未执行此次公网手机录放、ESP32、真人 STT 或 Linx MCP 验收；主机整机重启未执行 |

合成公网检查的本地结果在忽略目录 `output/deploy-20261024/https-smoke.jsonl`。
没有以合成检查替代三端真人验收，也没有修改项目整体 G1/G2/G3 为通过。

## 运维与后续发布

```sh
ssh root@122.195.131.106
systemctl status voxity caddy
journalctl -u voxity -u caddy --since '30 minutes ago' --no-pager
curl --fail https://122.195.131.106/healthz
```

修改 `/etc/voxity/server.env` 后执行 `systemctl restart voxity`。
修改 Caddyfile 后先以 `SONAR_PUBLIC_IP=122.195.131.106 /usr/local/bin/caddy validate --config /etc/caddy/Caddyfile`
检查，再执行 `systemctl reload caddy`。Caddy drop-in 中提供 SONAR_PUBLIC_IP 环境变量。

后续发布先创建新的 releases 子目录，从锁文件导出并校验安装生产依赖，核对来源 manifest。
停 Voxity 后备份整个 `/var/lib/voxity` 和私有环境文件，再切换 current 软链接并启动；
检查 HTTPS、身份和数据完整性。保留旧版本用于回退。
当前只有部署时的初始数据备份，没有配置周期备份或异地备份。
回退源码前确认数据库兼容性；不要恢复旧数据库覆盖新增业务数据。
