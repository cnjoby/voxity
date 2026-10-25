---
name: 声呐 Sonar 语音优先
description: 一次开始、随后全程语音；屏幕只呈现真实状态与必要的准备入口。
status: final
approval_status: approved
implementation_status: software_deployed_device_acceptance_pending
review_status: optional_review_skipped_by_user
updated: 2026-10-25
sources:
  - .decision-log.md
  - ../ux-sonar-2026-10-24/DESIGN.md
  - ../../product-architecture.md
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
    note: 继承 Android Compose MaterialTheme.typography.headlineSmall，系统中文字体与字体缩放。
  body:
    note: 继承 Android Compose MaterialTheme.typography.bodyLarge。
  supporting:
    note: 继承 Android Compose MaterialTheme.typography.bodyMedium。
rounded:
  md: 16px
spacing:
  unit: 8px
  gutter: 24px
components:
  start-action:
    background: '{colors.primary}'
    foreground: '{colors.surface}'
    radius: '{rounded.md}'
  readiness:
    foreground: '{colors.ink}'
  session-status:
    foreground: '{colors.heading}'
  context-line:
    foreground: '{colors.ink}'
  fallback-controls:
    foreground: '{colors.primary}'
  setup-row:
    foreground: '{colors.ink}'
    divider: '{colors.border}'
  device-indicator:
    note: 继承现有板卡可用的灯或小屏；不要求新增硬件，不以颜色单独编码状态。
---

# 声呐｜最少界面，完整语音

## Brand & Style

沿用现有海蓝、青色和浅色 Compose 界面。主角是用户想知道的事和接回来的声音，屏幕安静地陪伴。视觉变化集中在删除多入口、多卡片和模式选择，不另建品牌风格。

[EXPERIENCE.md](EXPERIENCE.md)规定行为；两份主规范优先于预览。设计已获批准并实现为Android0.2.0，配套后端已部署；[当前交付与检查](../../../development/voice-first-20261025.md)分别记录软件、设备和用户验收。ESP32固件仍由硬件同学维护。

## Colors

| Token | 使用 |
| --- | --- |
| `{colors.background}` / `{colors.surface}` | 页面底色与必要内容表面；不用白卡组成信息流 |
| `{colors.heading}` / `{colors.ink}` | 一个主状态、地点来源及简短说明 |
| `{colors.primary}` | 唯一主动作“开始”以及低显著性控制 |
| `{colors.border}` | 准备页原生行分隔，不作为唯一可操作边界 |
| `{colors.attention}` | “免提暂不可用”“模拟位置”等必要说明，不模拟交通警报 |

普通文字对比度目标至少 4.5:1、大字至少 3:1；交互边界与焦点需可辨。颜色不承担唯一状态语义。沿用色板不等于所有渲染组合已通过对比度或夜间实机验收；本轮不新增 dark 色板。

## Typography

主状态使用 `{typography.status}`；启动说明与当前对象用 `{typography.body}`；权限边界、来源和准备入口用 `{typography.supporting}`。会话中不展示持续滚动转写、完整指令表或任务列表。大字体允许换行，不能省略“模拟位置”“麦克风已关闭”等关键事实。

## Layout & Spacing

手机保持单列与原生安全区，基准 `{spacing.unit}`、页侧距 `{spacing.gutter}`。启动页保留名称、真实就绪状态和留白，行为说明紧邻唯一的“开始”按钮之前，其后是低显著性“连接与隐私”。首次系统权限沿用原生样式。

开始后同一表面只镜像主状态、一个当前对象/来源和底部安静、结束兜底；没有导航、模式栏、信息流或待办角标。横屏只重排留白，不增加面板；屏幕熄灭不改变业务。

## Elevation & Depth

继承 Compose 原生弹层和分隔。日常会话不用层叠白卡、悬浮任务或阴影强调多项工作；不以旋转进度和持续波形暗示系统仍可用。

## Shapes

主动作采用 `{rounded.md}`，原生控件保持现有 shape。同心圆仅作静态品牌图形；没有采集证明时不能让其脉冲成“正在听”。

## Components

| 组件 | 视觉规则 |
| --- | --- |
| `start-action` | 一页一个实心主按钮“开始”，使用 `{components.start-action.background}`；原生焦点和禁用态；无用途下拉 |
| `readiness` | 名称下方一条实情，如“可以开始”或“免提暂不可用”；错误说明使用 attention，不能只换圆点颜色 |
| `session-status` | 页面主要文字，只显示实际状态；字号继承 status，不堆叠处理步骤 |
| `context-line` | 最多一个当前对象与必要来源；例如“模力社区 · 等停车回应”；模拟位置始终可见 |
| `fallback-controls` | 底部两个带文字的原生控制“安静”“结束”；通知沿用系统样式，不增加第三种业务动作 |
| `setup-row` | 仅准备页的连接、隐私、权限和联调位置原生行；不把开发字段铺在主界面 |
| `device-indicator` | 沿用板卡现有指示能力，稳定区分就绪、播放、安静、关闭/不可用；不要求用户盯灯完成交流 |

[三态预览](mockups/voice-first-states.html)示意启动、会话镜像和不可用布局。首次权限、连接与隐私、联调位置按原生规范和 EXPERIENCE 表格实现，未单独绘制；语音对话通过脚本交接。预览不连接麦克风，不证明设备能力。

## Do's and Don'ts

| 应当 | 避免 |
| --- | --- |
| 用一个主动作让人开口 | 把五种用途改名后继续排成菜单 |
| 让状态和来源真实、简短 | 关麦后仍显示“可以唤醒” |
| 保留可访问的紧急控制 | 把触屏兜底变成正常交流必经步骤 |
| 让等待保持安静 | 任务角标、倒计时、催答动画和不停更新的转写 |
| 将模拟位置与合成内容分别标明 | 用“模拟”一词混淆人的所在地与音频来源 |
