# 单机部署

2026-10-25 已更新自然现场交流 v0.1.1，运行代码 `7b2c6ec`；数据、配置和重启检查通过。最新情况见[本次发布记录](deployment-20261025.md)。下文 2026-10-24 部署内容保留为历史基线。

2026-10-24 已部署到 Ubuntu 24.04：业务地址 `https://122.195.131.106`，
[健康检查](https://122.195.131.106/healthz)、[接口文档](https://122.195.131.106/docs)。
本次采用 Python 3.12 虚拟环境、systemd 单进程服务和 Caddy 公网 IP 证书；
高德、Bamboo、公网 HTTPS 三轮合成问答与重启恢复已验证。
运行路径、配置迁移、验证边界和运维命令见 [实际部署记录](deployment-20261024.md)。

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
