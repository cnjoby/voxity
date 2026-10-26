# 声呐 Sonar 设计工作台

- [手机回复 ESP32 失败排查（2026-10-27）](../development/esp32-reply-failure-20261027.md)：跟踪服务端039/040、Android睡眠模式与两次有声重放，以及ESP32统一Opus播放补丁；构建、主机与实机验收分别记录。

- [旧内容清理后的 Android 启动恢复（2026-10-27）](../development/android-stale-question-20261027.md)：0.3.6已安装，修复旧问题404被当作断网导致“开始”不可用，3项真机回归通过。

- [模力社区旧内容清理（2026-10-27）](../development/modaily-cleanup-20261027.md)：清理57条非公告内容及关联记录，保留11条公告、其他地点和账号配置；含备份位置与验证。

- [车位询问直接转发（2026-10-26）](../development/parking-direct-20261026.md)：去掉问人二次确认；生产038进一步修复旧等待问题导致的重复替换确认，Android0.3.5继续使用，真人语音验收另列。

2026-10-26 工程交付入口：[当前实现架构、模块规格与分工](module-specs.md) · [运行指南](../RUNNING.md) · [最新现有 PPT](../presentations/README.md)。产品规划与当前实现分别描述。

- [低延迟架构优化方案（2026-10-26）](low-latency-architecture-20261026.md)：基于已有手机时间线与源码，提出一次识别、统一业务编排和流式播放；含迁移关口、性能目标与验收规格；已实现部分见下方0.3.0记录。
- [0.3.0低延迟与Opus实现（2026-10-26）](../development/low-latency-opus-20261026.md)：已落实一次语音上行、边录边传、流式播放和压缩业务音频；复审后服务端已部署；预生成回复门禁未放行，真实平台/设备验收另列。

- [2026-10-26 会话优先与天气修复](../development/conversation-first-20261026.md)：有效设备邀请立即送达，普通闲聊直接与Agent继续，天气按地点和日期查询；已部署，手机持续聊天已获用户确认，ESP32验收另列。

- [0.2.3真实语音联调与修复](../development/audio-incident-20261025.md)：手机与服务器链路、ESP32原声播放、园区问答范围和当前固件交接；ESP32实际输出独立验收。

- [0.2.1 地点切换（2026-10-25）](../development/location-switch-20261025.md)：七牛／金科路与高科中路交叉口／模力社区，或当前设备位置；真实高德ID、同签覆盖包与生产同步。

**以车主个人 Agent 为入口，围绕时间、地点和事件，连接真人原声与有来源的信息，追踪需求和回应。**

## 表达与路演

- [核心价值四问版（2026-10-25）](sonar-core-story-20261025.md)：按为什么、是什么、原理、商业路径重组；含对讲／IM／地图群比较与新版 PPTX，规则和验收置于附录。
- [Sonar 表达重组（2026-10-25）](sonar-expression-20261025.md)：基于最新语音网络基线，整理主张、四类需求、Agent 协作、路演叙事与视觉方向；表达建议，非功能交付证明。

## 开发入口

- [WAV/Opus格式核对与031发布](../development/opus-interface-20261026.md)：历史素材的Android播放、ESP32的双格式合同、实际curl隔离验证和OpenAPI修正。

- [低延迟/Opus复审与生产发布](../development/review-deploy-opus-20261026.md)：7项修复、256/60/20项验证、030发布、配置和159份原始音频保留，平台门禁的实际状态。

- [模力社区长期公共提示](../development/modaily-entry-notices-20261025.md)：完整欢迎与入园须知、访客登记、园区客户通行；AI合成演示，直到修改或撤回。
- [语音优先0.2.0实现与交付（2026-10-25）](../development/voice-first-20261025.md)：一个开始后语音查、问、听、回复、追问、重播、取消和结束；真实高德ID、远程来源及服务器素材。Android已打包、配套后端已部署，软件与模拟器检查通过；真人手机、ESP32和车内声学仍待验收。[已批准设计](ux-designs/ux-sonar-2026-10-25-voice-first/README.md) · [三态预览](ux-designs/ux-sonar-2026-10-25-voice-first/mockups/voice-first-states.html)。

- [SNR-005 Android 闭环](../development/snr-005.md)：分项用途与授权、来源镜像、任务重听与继续、中断保护；0.1.3（4）已打包，代码和隔离验证完成，真机待验证。

- [SNR-003/004 实现与验证](../development/snr-003-004.md)：统一合法检索、来源选择、个人任务、有界邀请和原请求核对；后端已部署，Android 隔离模拟器通过，真人与固件验收另列。
- [SNR-001/002 实现与验证](../development/snr-001-002.md)：统一消息／动作／分项授权 v1，HTTP/MCP 共用执行入口，旧协议兼容及本地验证结果；已随 SNR-003/004 后端发布。
- [产品定义与功能架构（2026-10-25）](product-architecture.md)：下一阶段产品基线；四类相对主体、六要素消息、四条业务线、事件／任务／交付分层状态、当前源码证据与增量路线。
- [下一步开发任务与验收](../planning/sonar-development-backlog-20261025.md)：P0 统一入口、分项授权、信息检索及任务闭环；P1 事件、多回答、关注与治理；设备、发布和用户验收分开。
- [车主与个人 Agent 的语音互动网络](voice-network.md)：工具回应、目的地真人问答、双方自愿的闲聊，以及经明确同意的 Agent 陪聊。
- [三种自然语言意图实测](../development/intent-check-20261025.md)：AI 闲聊、目的地路况、前方景区车位；真实云端语音结果、隔离业务复现与当前断点。
- [App 与 ESP32 车用免提交互约束](ux-designs/ux-sonar-2026-10-24/EXPERIENCE.md)：统一自然交流、会话授权、打断/静音、通知节制、平台差异及验收；[状态预览](ux-designs/ux-sonar-2026-10-24/mockups/handsfree-states.html)。Android 与服务端已有增量实现，ESP32 与真实车内体验待验收。
- [自然交流发布审查](../development/review-20261025.md)：审查发现、修复、回归与服务器发布约束。
- [自然交流实现与接口](../development/natural-exchange.md)：自动发布、相关原声播放、独立会话授权、失败处理及分项验证。
- [ESP32 独立开发交接书](../development/esp32-ai-handoff.md)：可单独转发，内含免提要求、完整必要接口、Linx 消息、AI 提示词与交付检查。
- [两天开发任务入口](../../two-day-development.md)：三端排期、AI 任务包、接口契约与联调验收。
- [产品速览](personal-agent-concept.md)：人、广场与个人 Agent 的职责及三类公共声音；完整定义见上述新基线。
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

两天主场景为真人现场问答，包含个人筛选、主动提示和回复追踪。Android使用手机定位或真实高德POI远程选择，远程来源不表示到场；本地模拟已移除，只维护当前版本。道路方向尚未匹配；同路闲聊、同目的地交流的完整设备验收另列。

工程仓库：[cnjoby/voxity](https://github.com/cnjoby/voxity)。当前文档记录设计决策，不代表平台接入、部署或设备验收已完成。
