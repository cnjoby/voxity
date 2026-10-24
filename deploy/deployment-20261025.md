# 自然现场交流 v0.1.1 发布记录

时间：2026-10-25 01:22，Asia/Shanghai。代码通过 [PR #1](https://github.com/cnjoby/voxity/pull/1) 合并到 main，运行代码 revision 为 `7b2c6ec864a462ea75da36171a87e0e625ea25fe`。本记录随后作为文档提交补充，不改变运行源码。

## 版本与运行状态

- 地址：[HTTPS 健康检查](https://122.195.131.106/healthz)、[接口文档](https://122.195.131.106/docs)。公网 TLS 使用系统信任库验证，未使用跳过证书校验。
- 当前软链接：`/opt/voxity/current` → `/opt/voxity/releases/20261025-7b2c6ec8`。
- 原版本保留：`/opt/voxity/releases/20261024-1265b4d`。
- 单进程 Python 3.12.3 / systemd；Voxity 与 Caddy 均 active/enabled。实际再次重启 Voxity 后健康检查、来源文件、数据库和音频复核通过。
- 来源清单：当前目录 `deployment-manifest.json`，20 个运行源码/配置/锁文件逐一 SHA256 与合并版本一致。部署包 SHA256 为 `6db4e3860d3be587ee4affe23ae762bedd258633fc54250ac914c646e61e571a`。
- `uv.lock` 与原部署相同，复制原有锁定的生产虚拟环境，`pip check` 通过；没有重新解析依赖或安装未锁定升级。
- 完整预检与实际发布结果保存在服务器当前目录的 `preflight-report.json`、`deployment-result.json`。本地证据在忽略目录 `output/release-20261025/`。

## 数据、配置与回退

停止 Voxity 后，备份整个数据目录与私有环境到 `/var/backups/voxity/release-20261025-7b2c6ec8/`，再原子替换 current 并启动。备份目录仅 root 可访问，私有环境副本 600。

原有 13 张表的逐表内容摘要在切换前后完全一致；3 个账号、3 份 profile、89 条声音、16 条交付、89 份音频保留。切换时地点已为 64 个，按停机前实际快照保留，未按历史 59 个地点重置。新增 exchange_sessions/turns/candidates 三张表初始为空。数据库完整性、外键及所有旧音频 SHA256 检查通过。

私有环境文件摘要前后一致：设备 Token、Linx 身份绑定、MCP 开关/密钥、AMap/Bamboo Key、Caddy 和数据路径均未覆盖。正式库没有写入合成交流测试；所有业务闭环检查使用临时库和明确标记的静音 WAV。

回退时切回原源码软链接并重启即可；本次迁移只增加表。不要恢复旧数据库覆盖发布后的新增业务。旧 release 和本次停机备份保留。

## 实际检查

| 检查 | 结果 |
| --- | --- |
| 复制正式库上的增量迁移 | 原 13 表摘要不变；新增三张交流表；完整性/外键通过 |
| 以 voxity 服务账号执行隔离新业务闭环 | scene 发布/收听/定向回复；问题本人原声/回答/后续回复；played 回执、暂停、归属隔离通过 |
| 公网 `/healthz` | HTTP 200 |
| 公网 OpenAPI | 已包含自然会话、utterances 和新收听接口 |
| 无凭证 `/v1/me`、MCP | HTTP 401 |
| Alice/Bob 身份读取 | 均 HTTP 200；查询不存在的新会话均正确 404 |
| MCP tools/list | probe/get_feed/set_focus/queue_voice 可发现 |
| MCP 带可信设备绑定的 probe | Alice/Bob 均正确映射；未执行改意愿/排队等写操作 |
| 实际重启恢复 | 健康 200、20 个来源文件一致、数据库完整、89 份音频不变 |
| 本地自动检查 | 服务端 116 项；Android JVM 30 项；Lint/构建及 5 项隔离设备检查通过 |

Android 安装包为 `0.1.1` / versionCode `2`，公开下载见 [GitHub v0.1.1 Release](https://github.com/cnjoby/voxity/releases/tag/v0.1.1)。APK SHA256：`398068a8c8055c6926efdf7c5494658e783908c4db43f6c48fe8dd621b58ebbb`。APK 不含用户凭证，更新后保留已有配置；没有在本次发布中安装到实体手机。

## 验收边界

本次完成服务器更新与软件/隔离业务检查。没有把模拟器、静音 WAV、决策桩或 MCP 服务端探针当作车内声学、真人 Linx 双端、实体手机后台体验或 ESP32 固件验收。实际语音与车内回声仍待对应设备验证。审查修复见[发布审查](../docs/development/review-20261025.md)，接口见[自然交流实现](../docs/development/natural-exchange.md)。
