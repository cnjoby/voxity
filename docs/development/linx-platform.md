# 灵矽智能体与 Sonar MCP

日期：2026-10-24。范围：平台智能体、私有 MCP、线上身份映射与协议检查。手机录放、Bamboo 邀请筛选、自动分享及 ESP32 分别验收。

## 已配置

| 对象 | 配置 |
| --- | --- |
| Android | 复用账号内原有 Sonar 智能体，昵称声呐；保留已绑定手机、原模型与音色 |
| ESP32 | 创建独立 Sonar ESP32 智能体，昵称声呐；尚未绑定实体硬件 |
| 提示词 | [声呐智能体提示词](../design/linx-agent-prompt.md)，平台已保存并回读核对 |
| 意图与记忆 | `Intent_function_call`、`Memory_nomem`；关闭固定唤醒问候，个人配置以业务工具为准 |
| 私有 MCP | Sonar 声音广场，仅当前账号使用，绑定两个智能体 |
| 线上地址 | `https://122.195.131.106/mcp/http`，证书验证通过 |
| 工具 | `probe`、`get_feed`、`set_focus`、`queue_voice` |
| 业务映射 | Android 当前真实 Linx Device/Agent 映射到 alice；ESP32 智能体预留给 bob，真实设备映射待绑定后填写 |

保留原模型、音色和已有插件配置。提示词限制工具使用范围，不把不能执行的公开发问、自动分享或撤回描述为已完成。Bamboo 仍在 Sonar 服务端独立负责邀请提醒，不作为灵矽对话模型。

账号管理 Key 保存于忽略的 `server/.env.linx`，权限 0600，不下发手机、ESP32 或 MCP 实例。MCP 使用独立 `SONAR_MCP_SECRET`，保存在业务服务器配置与平台私有 MCP 实例的 Authorization Header 中。设备/智能体映射保存在本机 `server/.env`、线上 `/etc/voxity/server.env` 和 users 表；账号 Token、关注、任务和原声未重置。

## 传输与权限

本轮实际创建服务发现：旧文档的 `transport_type=0` 被平台拒绝为 `invalid mcp config`；依据当前官方控制台前端配置，使用 `transport_type=1` 成功创建。平台格式为：

```json
{
  "type": "sse",
  "transport_type": 1,
  "sse": {
    "type": 0,
    "url": "https://122.195.131.106/mcp/http",
    "params": {},
    "header": {"Authorization": "Bearer <SONAR_MCP_SECRET>"}
  }
}
```

服务端保留旧 SSE 接口，新增 JSON 响应的无状态 Streamable HTTP。每个请求都重新校验鉴权与设备归属，不让一个用户复用另一个用户的会话。平台控制台没有设备上下文时只允许发现工具，实际调用四个工具都失败；不填固定虚构 Device/Session 来绕过身份检查。

真实设备会话由灵矽附加 `X-Linx-Device-Id`、`X-Linx-Agent-Id`、`X-Linx-Session-Id`。Sonar 先验证专用 Bearer，再核对 users 映射。ESP32 在真实 MAC 尚未绑定前不能操作 bob 的状态。

## 验证证据

- 平台管理 API：账号鉴权、两个智能体配置回读、私有服务创建、实例开通和绑定成功。
- 平台 `GET /v1/mcps/{id}/tools`：真实返回四个工具及 Schema。
- 平台无设备上下文调用 `probe`：返回 MCP `isError=true`、`MCP_IDENTITY_MISSING`。API 外层 `code=0` 只代表请求完成，不是业务鉴权成功。
- 官方 Python MCP SDK 经公网 HTTPS：工具发现成功；无身份 probe 拒绝；真实已登记 Device/Agent 加测试 Session 的只读 probe 映射 alice。测试 Session 由脚本提供，不能冒充灵矽语音透传验收。
- 服务端 96 项测试通过，含两种传输、无身份工具读写拒绝、不同用户隔离、版本检查和原业务回归；Ruff 检查通过。保留一条上游 TestClient 弃用提示。
- 独立协议客户端使用账号已授权的设备绑定及单独持久 Client UUID，通过真实 OTA/WSS 上传两条本机合成预录音。真实 STT/TTS 完整返回；“查询收听模式”读到个人配置，“现在安静”经云端 MCP 把线上 alice 从 listen/revision 19 改为 quiet/revision 20。随后用鉴权 MCP 恢复 listen/revision 21，关注与互助开关保持原值。未启用手机麦克风、未播放回复、未公开声音。此项证明真实 Linx → MCP → Sonar 链路，不是手机 App 或真人体验验收。
- 新 MCP 文件已部署至当前线上 release；旧文件备份为 `app/mcp.py.before-linx-20261024`，环境备份为 `/etc/voxity/server.env.before-linx-20261024`。

私有平台响应、测试报告和配置前快照在忽略的 `output/linx-setup/`。其中可能含连接参数，不作为公开交付物。

手机 App 内追加验收未完成。无线 ADB 曾断开，用户提供的地址和重新配对均未完成握手；撤销了失效连接的后台重试。用户接线后系统识别 vivo USB，ADB 经 mDNS 恢复连接。测试包首轮安装成功，但基于 Activity 的检查启动停滞，终止后记录 `Process crashed`，没有语音结果，不能认定为平台调用失败或成功。准备改用无 Activity 的独立 `LinxMcpLiveTest`；测试包构建成功，重新安装时手机返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，停止安装重试，未替换原 App。原草稿与配置未主动改写；当前工具没有发布、回答或会话自动公开授权能力，`queue_voice` 只排队，不能证明设备已播放原声。

后续允许安装测试包后，可将 16kHz 单声道 PCM WAV 合成测试素材放入 App 私有 `files/linx-mcp-read-state.wav`，仅运行 `LinxMcpLiveTest#prerecordedVoiceReadsRealSonarMcp`，显式传入 `linx_mcp_live=true` 和 `linx_mcp_base_url`。该测试默认跳过，不使用 Activity、麦克风或播放器，报告为私有 `files/linx-mcp-live.json`；同时核对手机保存身份、线上映射、任务存储摘要和配置未变化。

## 后续设备接入

Android 保持现有灵矽绑定，重新建立语音会话后加载新配置。手机的 Sonar HTTP 连接需要使用同一个线上服务与 alice 凭证，否则语音修改的状态和 App 显示可能来自不同服务器。

ESP32 使用设备真实 OTA 激活码绑定 Sonar ESP32，取得真实 Linx MAC 后更新 bob 映射；不能复用手机身份。业务 Token 与 Linx Token 独立，硬件录放和业务原声仍按[独立交接书](esp32-ai-handoff.md)验收。

## 官方依据

- [平台 API 与认证](https://linx.qiniu.com/docs/xrobot/api/index)
- [智能体配置 API](https://linx.qiniu.com/docs/xrobot/api/agent)
- [MCP 服务管理 API](https://linx.qiniu.com/docs/xrobot/guide/mcp-store)
- [云端 MCP 身份 Header](https://linx.qiniu.com/docs/xrobot/mcp/software-mcp)

传输配置以本轮实际 API 响应及官方控制台前端为准，已记录其与页面文档的差异。
