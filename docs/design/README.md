# 声呐 Sonar 设计工作台

**人发声，广场组织声音，个人 Agent 帮你听见有关系、合情境、适用于所在地点的内容。**

## 开发入口

- [App 与 ESP32 车用免提交互约束](ux-designs/ux-sonar-2026-10-24/EXPERIENCE.md)：统一自然交流、会话授权、打断/静音、通知节制、平台差异及验收；[状态预览](ux-designs/ux-sonar-2026-10-24/mockups/handsfree-states.html)。Android 与服务端已有增量实现，ESP32 与真实车内体验待验收。
- [自然交流发布审查](../development/review-20261025.md)：审查发现、修复、回归与服务器发布约束。
- [自然交流实现与接口](../development/natural-exchange.md)：自动发布、相关原声播放、独立会话授权、失败处理及分项验证。
- [ESP32 独立开发交接书](../development/esp32-ai-handoff.md)：可单独转发，内含免提要求、完整必要接口、Linx 消息、AI 提示词与交付检查。
- [两天开发任务入口](../../two-day-development.md)：三端排期、AI 任务包、接口契约与联调验收。
- [产品定义](personal-agent-concept.md)：人、广场与个人 Agent 的职责及核心场景。
- [声音匹配与交付需求](voice-matching.md)：上下文问答、情境声音、POI 公共频道；含时效、运营权限、到达交付、收听规则及目标验收（已扩展服务端与 Android，实机待验收）。
- [声音匹配实现与验收](../development/voice-matching.md)：新增协议、权限、访问、回执与隔离模拟器复跑。
- [两天黑客松架构](architecture.md)：主流程、ESP32、Android、Linx、单机服务、接口及验收。
- [Android 接入灵矽](../development/linx-android.md)：OTA 激活、绑定、PCM/Opus、私有语音测试与真实平台验收边界。
- [灵矽智能体与 MCP 配置](../development/linx-platform.md)：平台私有服务、Streamable HTTP、设备身份映射及分项验收。
- [个人声音决策](attention-decisions.md)：Bamboo/StartLux 已参与实际邀请筛选，包含规则复核、后台调用、缓存及失败处理。
- [真实位置与地点关联](location-and-places.md)：前台定位、消息位置快照、目标地点、空间召回与个人策略的分工（已实现，实机待验收）。
- [架构评审](architecture-review.md)：两天交付判断、接入风险与核心理念的演示条件。
- [黑客松项目方案](</Users/joby/Documents/Brain/Qiniu/Voxity/声呐 Sonar｜黑客松项目方案.md>)：范围、分工与路演故事。

## 工程约束

客户端为 ESP32 与 Android；自有业务服务单机部署，语音交互依赖 Linx 云平台。ESP32 硬件与固件由对应成员实现，双方按音频及业务协议联调。

两天主场景为一个预设路口的真人现场问答，包含个人筛选、主动提示和回复追踪。Android 可使用真实定位与 POI，旧模拟位置协议保留；道路方向尚未匹配；同路闲聊、同目的地交流在核心完成后扩展。

工程仓库：[cnjoby/voxity](https://github.com/cnjoby/voxity)。当前文档记录设计决策，不代表平台接入、部署或设备验收已完成。
