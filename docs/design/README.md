# 声呐 Sonar 设计工作台

**人发起问答，广场组织声音，个人 Agent 帮你筛选、收听和追踪。**

## 开发入口

- [两天开发任务入口](../../two-day-development.md)：三端排期、AI 任务包、接口契约与联调验收。
- [产品定义](personal-agent-concept.md)：人、广场与个人 Agent 的职责及核心场景。
- [两天黑客松架构](architecture.md)：主流程、ESP32、Android、Linx、单机服务、接口及验收。
- [架构评审](architecture-review.md)：两天交付判断、接入风险与核心理念的演示条件。
- [黑客松项目方案](</Users/joby/Documents/Brain/Qiniu/Voxity/声呐 Sonar｜黑客松项目方案.md>)：范围、分工与路演故事。

## 工程约束

客户端为 ESP32 与 Android；自有业务服务单机部署，语音交互依赖 Linx 云平台。ESP32 硬件与固件由对应成员实现，双方按音频及业务协议联调。

两天主场景为一个预设路口的真人现场问答，包含个人筛选、主动提示和回复追踪。位置与方向为模拟配置；同路闲聊、同目的地交流在核心完成后扩展。

工程仓库：[cnjoby/voxity](https://github.com/cnjoby/voxity)。当前文档记录设计决策，不代表平台接入、部署或设备验收已完成。
