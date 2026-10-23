# 服务端 AI 任务包

状态：服务端本地实现与检查已推进，真实平台、部署和设备验收仍待完成。计划占用约 13 小时开发与自检，另留 3 小时联合验收；时间为角色预算，不是完成承诺。

| 任务 | 当前结果 |
| --- | --- |
| S1 | 工程、锁文件、身份、seed、me/context 与本地探针已实现；Linx 真实调用待验证 |
| S2 | 草稿、确认、问答查询、取消与原声权限已实现并通过本地检查 |
| S3 | 规则、邀请、回执与待听已通过本地 HTTP 闭环；G1 的真实设备提示待验收 |
| S4 | 三工具及只读 probe 已通过官方 SDK 本地 SSE 检查；Linx 云端调用待验证 |
| S5 | 自动检查与进程重启恢复已验证；容器配置已提供，容器构建及公网部署未执行 |

运行方法、实际结果与接入缺口见[服务端交接](../../server/HANDOFF.md)。

先读[产品架构](../design/architecture.md)、[接口契约](contracts.md)与[总计划](../../two-day-development.md)。交付 FastAPI + SQLite + 本地音频目录的单机服务；个人规则和 MCP 归本角色，客户端不自行实现广场筛选。

## 目录与协作

拥有 `server/`、`deploy/`、`scripts/`；建议 server/app 下分 api、services、models、mcp、audio，tests 放 server/tests。客户端目录归各端。契约变更先写 contracts.md 并通知负责人，不能静默改字段；AI 未获授权不向其他任务发送消息。运行时文件不得提交。

S1 内同时完成 Linx MCP 凭证与设备身份映射的最小验证，平台控制台涉及实际账户操作时记录结果与配置位置，不输出密钥。

## 任务

- [ ] **S1｜工程、身份与平台探针，D1 0–2h**
  - 建立 Python 项目、依赖锁、环境样例、`GET /healthz`（200）、配置和启动说明；设备 Token 哈希映射 alice/bob。
  - schema 初始化 users/profiles/posts/deliveries 四表；持久目录可配。seed 命令幂等，凭证从环境注入。
  - 实现 me/context；提供契约 JSON 模拟响应，双端不等数据库完成即可开始。
  - 验证 Linx 可调用一个无副作用探针并取得可信身份；若平台未就绪，记录阻塞，继续业务 API。
  - 验收：本机 healthz 200，无 Token 的 me 401，alice/bob 配置独立；真实 MCP 证据单独记录。

- [x] **S2｜原声与人发起的问答，D1 2–4h**
  - 实现 multipart 草稿、音频实参验证、幂等和作者私有访问；confirm 发布、question 查询、cancel。
  - 问题地点与本人位置分开；回答绑定 invitation+question，不接收 user_id 冒充作者。
  - 事务保证重复确认不重复发布，失败草稿不公开；未核对转写不能代人确认。
  - 验收：一条真实 WAV 可建草稿、重试仍同ID，未确认他人不可读；无效 WAV/超长音频返回契约错误。

- [ ] **S3｜个人策略、邀请和待听，D1 4–8h**
  - 新问题事件按每人位置、方向、位置时效、互助开关、mode、重复记录决定邀请；作者不邀请自己。
  - 实现 inbox/list/detail/ack；邀请过期与取消；回答发布生成 A 的音频交付，question→answered。
  - 修改意愿撤销邀请；quiet 保留音频待听。取出/确认/播放复核共用规则，轮询不能成为反复邀请来源。
  - 原因码最少支持 ELIGIBLE、MODE_QUIET、ASSIST_DISABLED、PRESENCE_EXPIRED、TASK_EXPIRED、TASK_CANCELLED、ALREADY_HANDLED；给本人可见的实际原因，不暴露他人私有配置。
  - 验收 G1：Android 的问题使 ESP32 得到一次邀请，回复回到 Android；无完整录音时可先用标注素材，但必须真实设备出声。

- [ ] **S4｜Linx 三工具及任务一致性，D2 0–3h**
  - 实现 get_feed/set_focus/queue_voice，业务层复用 HTTP 服务方法，工具不调用公网自身 API 绕过权限。
  - get_feed 保存短期候选快照；queue_voice 验证请求、版本、用户与候选，复用已有音频交付。
  - 在真实 Linx 会话中用“现在安静”“愿意回答附近路况”改变配置；确认工具结果可回读、真实影响新问题的邀请。
  - 验收：旧 revision、过期 request_id、伪造设备映射均拒绝；两端 Agent 不串个人状态。

- [ ] **S5｜自动验证、单机交付，D2 3–5h**
  - 用 pytest/等效测试和临时 SQLite 覆盖发布幂等、越权、个人策略正反例、回复关联、过期取消、状态回执与旧版本；时间由测试时钟注入，不靠长 sleep。
  - 提供单进程应用容器/启动配置、同机 HTTPS 代理示例、持久卷、部署说明；不安装额外分布式服务。`/healthz` 只暴露健康状态，不回传配置。
  - 交付真实运行的 base URL、设备配置注入方式、构建/测试命令和结果；重启验证数据库与文件同在。未知域名、证书或服务器访问参数标为部署输入，不能编造已上线地址。
  - 验收：软件测试通过与实际部署成功分别记录；部署条件缺失时保留可运行本地服务及明确阻塞项。

## 交给 AI 的执行指令

> 你负责 Sonar 服务端。先读取 two-day-development.md、docs/design/architecture.md、docs/development/contracts.md 和本文件。按 S1→S5 实施，每项先给可运行的小闭环；只修改 server/、deploy/、scripts/ 及必要的执行记录。遵守现有项目规则，保留他人工作。优先 G1，不添加范围外功能；用真实测试验证规则、权限和幂等。平台接入、部署和设备验收需各自证据。完成后给出任务ID、变更文件、运行方法、检查结果及阻塞，不以模拟结果代替联调。
