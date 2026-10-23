# 单机部署

本机环境未安装 Docker，以下配置尚未做容器构建和公网部署验证。应用已有本机运行证据，见 [服务端交接](../server/HANDOFF.md)。

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
