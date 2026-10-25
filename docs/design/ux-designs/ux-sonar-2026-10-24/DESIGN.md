---
name: 声呐 Sonar 车用免提
description: 沿用既有 Android Compose 外观，以语音为主、屏幕和设备指示为辅的车用状态呈现。
status: final
implementation_status: pending
review_status: skipped_by_user
updated: 2026-10-24
sources:
  - .decision-log.md
  - ../../../../android/UI-NOTES.md
colors:
  heading: '#123E51'
  primary: '#007F86'
  background: '#F0F5F5'
  ink: '#163440'
  border: '#D8E8E9'
  attention: '#966019'
  surface: '#FFFFFF'
typography:
  status:
    note: Android Compose MaterialTheme.typography.headlineSmall；系统中文 Sans，支持字体缩放。
  body:
    note: Android Compose MaterialTheme.typography.bodyLarge；系统中文 Sans，支持字体缩放。
  supporting:
    note: Android Compose MaterialTheme.typography.bodyMedium；不得作为唯一反馈通道。
rounded:
  card: 16px
spacing:
  unit: 8px
  gutter: 16px
components:
  session-status:
    background: '{colors.surface}'
    foreground: '{colors.heading}'
  current-turn:
    background: '{colors.surface}'
    foreground: '{colors.ink}'
    radius: '{rounded.card}'
  waiting-summary:
    foreground: '{colors.ink}'
  quiet-control:
    foreground: '{colors.primary}'
  setup-row:
    foreground: '{colors.ink}'
    divider: '{colors.border}'
  device-indicator:
    note: 继承实际板卡指示能力；颜色不是唯一状态编码，不要求新增 RGB 灯。
---

# 声呐｜免提状态的视觉约束

2026-10-25 后继设计：[语音优先重设计](../ux-sonar-2026-10-25-voice-first/README.md)。新方案保留既有视觉风格，收缩启动与会话表面；本页保留历史记录。

## Brand & Style

本版保留现有声呐的海蓝、青色、浅色背景与白卡，不新增品牌方向。手机屏幕和ESP32指示只镜像实际状态；本次授权内现场发言自动分享、相关真人回复自动接续，系统保持安静。外观不能暗示麦克风、联网或待发布确认已经就绪，除非对应能力真实可用。

行为规则由同目录 [EXPERIENCE.md](EXPERIENCE.md)定义。两份主规范与预览冲突时，以主规范为准；本版为设计提案，不能作为设备通过记录。

## Colors

| Token | 继承含义与用途 |
| --- | --- |
| `{colors.heading}` | 主要状态标题；沿用海蓝，不为每种状态新增颜色 |
| `{colors.primary}` | 可操作入口与当前会话标记；不能仅凭青色表示正在录音 |
| `{colors.background}` / `{colors.surface}` | 现有浅灰蓝底与白色内容卡 |
| `{colors.ink}` | 关键正文、待听状态、地点与公开对象 |
| `{colors.border}` | 低强度分组；不承担可操作边界的唯一识别职责 |
| `{colors.attention}` | 需要停车处理、未就绪等解释性提示；不模拟车辆警报 |

文字与其背景的目标对比度：普通文字至少 4.5:1，大字至少 3:1；状态不能只用颜色传达。此处是实现要求，实际字号、禁用态、夜间与显示亮度仍需渲染验证。现有 light 配色不构成已验收的夜间模式；本轮不新增未经确认的 dark 色板。

## Typography

使用 `{typography.status}` 显示一个当前状态，例如“交流中”“已分享”“相关回复播放中”“已安静”“免提不可用”。正文使用 `{typography.body}`；额外解释使用 `{typography.supporting}`。行车页不显示滚动转写、技术日志或完整语音指令目录。字体放大时允许内容向下扩展，不能截断“未公开”“麦克风关闭”等关键语义。

## Layout & Spacing

Android 继承 Compose 原生布局与系统安全区，单列呈现；基础间距 `{spacing.unit}`，页侧距 `{spacing.gutter}`。驾驶状态镜像只保留会话状态、当前轮次、期待回答摘要和静音入口，不堆叠分享流、地图、POI 列表、键盘或开发配置。

屏幕可以熄灭；业务不能依赖用户看到、解锁或点按屏幕。横屏和大字体不新增同屏多任务。首次授权、设置、历史记录和测试素材仍在停车页面，通过明确的“停车设置”入口访问。

## Elevation & Depth

沿用现有白卡与轻边界，不新增阴影体系。当前轮次通过位置与标题形成层次，不能用放大闪烁、全屏警示或持续波形抢占注意力。

## Shapes

卡片采用 `{rounded.card}`，其余组件继承现有 Compose shape。声呐同心圆可以保留为静态品牌图形；常驻图形不代表持续上传，转动或脉冲也不作为“正在听”的唯一证明。

## Components

| 组件 | 视觉规则 | 状态约束 |
| --- | --- | --- |
| `session-status` 会话状态 | 顶部一条主状态，使用 `{components.session-status.foreground}`；附麦克风开/关文字 | 区分“可唤醒”“已安静，仍可唤醒”“麦克风关闭”“免提不可用”；状态来自真实服务 |
| `current-turn` 当前轮次 | 一张白卡，标题、目标/对象和一句动作提示；使用 `{components.current-turn.radius}` | 授权内自动执行；发送结果未知不能显示“已分享”，审阅模式才显示待确认 |
| `waiting-summary` 待听摘要 | 一行期待任务或待听数量，使用 `{components.waiting-summary.foreground}` | 不滚动、不红点催答、不倒计时闪烁；已回答与已播放分开 |
| `quiet-control` 静音入口 | 保留可见文字入口；使用 `{components.quiet-control.foreground}` | 行车主动作仍是语音“安静”；安静同时暂停自动发布，不能画成麦克风已关闭 |
| `setup-row` 停车设置 | 现有二级设置行，文字标签和分隔线 | 授权、连接、转写编辑、演示试听放这里；不可把行车失败导向立即触屏 |
| `device-indicator` ESP32 状态指示 | 用现有 LED/小屏的稳定明暗或可区分节奏表达；亮度在停车时设置 | 至少区分交流就绪、采集中、发布/播放、安静、麦克风关闭/故障；单灯板不能靠未说明的颜色表交付 |

[状态预览](mockups/handsfree-states.html)用于核对自然交流、静默分享和自动原声接续的两端状态镜像；它不连接真实麦克风，也不能证明免提已实现。停车设置和设备配置沿用现有原生页面结构，以表格规范交接。

## Do's and Don'ts

| 应当 | 避免 |
| --- | --- |
| 只有实际可唤醒时显示“可以唤醒” | 麦克风被系统关闭后仍显示在线圆环 |
| 显示“尚未公开”“发送结果待确认” | 用进度动画掩盖发布是否成功 |
| 行车页面是语音状态镜像，熄屏也能使用 | 要求驾驶员看转写、点确认、选地点列表 |
| 保留原生可访问标签、字体缩放与合理亮度 | 闪烁灯光、抢占式动画、用红色模拟紧急交通警报 |
| 安静与关闭麦克风有不同文字/指示 | 把停止播放、静音、取消提问、关麦做成同一图标 |
