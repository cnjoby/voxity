# 三类声音：实现协议与验收

日期：2026-10-24。对应 [18 条需求与 12 个验收场景](../design/voice-matching.md)。本轮范围由“继续实现”扩展至 Android 前台与本机服务端；既有两天原型的硬件验收标准保留。

## 运行边界

- `question/answer` 协议兼容；新增 `reply/scene/knowledge/announcement`，仍上传真实 WAV、核对转写、点击确认后公开。私聊尚未开放，传 `visibility=private` 被拒绝，不能引用无权访问的原声。
- Android 前台轮询；真实 Linx、实体扬声器、道路到达、后台唤醒与室内楼层仍需单独验收。模拟器注入位置与手动转写只证明规则和交互。
- 情境默认关闭；运营公告和自己的回复默认允许自动收听，各自设置。个人静音统一阻止播放。运营资格为封闭试点人工授权，UI 标记演示身份，无自助提权接口。
- 150 米 POI 半径、120 秒离开宽限、每次访问最多 1 条情境提示、间隔 300 秒均为可配置初值，尚无物理到达体验结论。匹配要求新鲜定位、精度合格且 `距离 + 误差 <= 半径`；无可靠楼层/入口/区域证据时，这些范围只供主动查询。

## HTTP 增量协议

身份、错误、UTC 时间及 multipart 约定继承 [基础契约](contracts.md)。`GET /v1/me.profile.listening` 返回独立设置。客户端先取当前版本，再更新：

```json
{
  "expected_revision": 2,
  "scene_enabled": false,
  "channel_enabled": true,
  "replies_enabled": true,
  "interests": ["风景"],
  "subscribed_places": [],
  "muted_topics": [],
  "muted_channels": []
}
```

`PUT /v1/me/listening` 更新上述完整设置；版本冲突 409。不会变更 `assist_enabled/focus` 或刷新位置。订阅是情境兴趣依据，仍需物理到达且开启情境设置才自动提示；远程订阅与搜索不授予现场资格。

`POST /v1/posts/drafts` 继续使用 WAV + metadata。地点声音必填 `kind/transcript/target_place_id/purpose`，目标必须是服务端解析过的真实 POI；`recorded_location` 仅记录作者录制位置，与目标和收听者位置独立。`visibility` 仅允许 public。

| kind | 用途与额外字段 |
| --- | --- |
| scene | `purpose=reflection/experience/explanation/observation`；可选 topics；`applicability=unconditional/unknown`，unknown 仅主动查询；explanation 须有 source_note |
| knowledge | `purpose=explanation`、source_note（注明来源与核对日期）；可带授权 channel_id；只按需查询 |
| announcement | `purpose=notice`、channel_id、valid_from、valid_until；scope 默认为 poi，或 entrance/floor/area + scope_label；可用 replaces 指定同频道同范围旧公告 |
| reply | `root_question_id/reply_to/recipient_id` 明确原问题、被回复原声、被回复作者；参与者才能继续同一对话，不凭地点关联 |

即时观察必须有开始/结束且时段不超过 `SONAR_OBSERVATION_MAX_TTL`（默认一小时）；公告不超过 `SONAR_ANNOUNCEMENT_MAX_TTL`（默认一天）。没有缺省长期动态公告。客户端发布表单目前选择立即生效、1～60 分钟；HTTP 支持未来生效。替代公告必须立即生效，不提前撤下仍有效的旧公告。草稿创建和确认时均检查权限、范围、版本与有效期，发布、替代和撤下队列在同一事务内完成。

新增接口：

| 接口 | 行为 |
| --- | --- |
| GET /v1/voices?place_id=... | 主动查询公开有效声音与频道；返回 items/channels/announcement_status；无有效版本明确为“暂无有效运营公告”。允许远程浏览，无到达副作用 |
| POST /v1/voices/{post_id}/queue | `{client_request_id: UUID}` 表示一次主动收听；与自动/订阅入口共用 delivery，并合并 reasons；可在关闭该类别自动收听时手动听，但仍尊重个人静音。重复相同命令不重启播放；新的用户命令允许重听。旧 `{}` 请求保持幂等，不重启终态 |
| POST /v1/voices/{post_id}/withdraw | 作者可撤回个人声音；授权频道运营方可撤回频道内容；撤回待播立即失效 |
| GET /v1/conversations/{question_id} | 参与者查询原问题、首轮回答和定向回复链，返回 visibility/can_reply/items；不公开参与者坐标 |

`question` 可选 `channel_id` 表示访客向该 POI 频道提问，不授予运营身份；首轮 `answer` 与后续 `reply` 可显式指定 `channel_id` 选择运营身份，创建草稿与确认时均重查该 POI 授权。未指定时保留个人身份。运营回复只交付原对话，不加入全员公告；另录公告并确认范围/期限才广播。Android 提供频道提问和授权运营回应的单独入口。

首轮 `answer` 仍需有效现场邀请；后续 `reply` 检查已建立关系和上下文权限，不再要求参与者在原地。连续对话期限暂取原问题发布后的 `SONAR_AUDIO_TTL`（默认 24 小时），首轮原声的待听期限仍兼容原协议（回答发布后 24 小时）；取消/过期的未回答问题不能重启，不能无限续期。当前不提供私聊或转发私密内容。

## 队列、访问和回执

`GET /v1/inbox` 为附近适用用户提供有效公告，不按兴趣、互助开关或频道自动收听设置删掉公告；出声资格单独计算。知识不自动入队。情境由独立开关、主题、订阅和次数预算选择；历史感慨带时间与历史说明，未知适用条件不主动推荐。场景选择目前为确定性规则，Bamboo 仍仅参与既有回答邀请。

audio payload 增加 kind/visibility/author_id/recorded_at/place_name/location_scope/valid_until/source_type/reasons/priority；频道内容另有运营主体、频道、演示身份；定向回复另有 root_question_id/reply_to/recipient_id。新声音可提供 `recorded_at`，Android 录音开始时按服务端时钟估计填写；缺少时明确“录制时间未提供”，另列服务端 `collected_at`，不把上传日期冒充录制日期。所有客户端可见数据均不含别人的 recorded_location 或访问列表。

优先级为主动选择 0、自己的回复 1、运营公告 2、邀请 3、情境 4。客户端录音/转写/播放互斥；播放前下载后再联网复核 ID、request_id、revision、静音、权限和有效状态。断网不能完成复核便不出声，不使用动态缓存推断最新状态。

同 POI 访问由新鲜物理位置建立，与手选目标独立。短暂离开沿用 visit_id；超过宽限后重新进入产生新 visit_id。离开后尚未开始的自动声音取消；相同访问内不重复提示同一公告版本，新版本可提示。旧公告替代、撤回、到期、运营撤权立即阻止其开始播放；旧声音原作者仍可读取自己的存档，不赋予他人播放权。

交付仍以 recipient+post+audio 唯一。同一声音从主动查询、地点订阅、到达命中只合并 reasons；新访问复用逻辑交付并轮换 request_id/visit_id。客户端按 visit_id 保存尝试记录，旧访问回执不能应用到新访问；模式变更仍保留同内容的去重。

ack 增加 received/seen；played 只在播放器完整结束后上报，skipped/failed 不能冒充听完。`delivery_receipts` 追加保存各 request/visit 的回执历史；生成队列不记 received，客户端实际取得队列才报 received，点击收听才报 seen。不能以服务器入队数作为人人收到/听见的证据。后续容量与试点覆盖率应另外测量。

“稍后听”保留服务端 pending，并在本机持久化延后，不自动重启；手动可恢复。情境已尝试/已听默认不重复，减少此类可屏蔽主题，无主题时关闭情境。关闭单频道自动播报仍允许主动查看；“我的”可恢复已屏蔽的主题/频道。

## 本地运营资格

先用 `/v1/places` 解析 POI，再在 server 目录显式创建频道和授权；不会读取客户端自称的运营身份：

```sh
uv run python -m app.operator_cli create --channel demo_parking --place POI_ID --label 停车场频道 --operator-label 停车场运营方
uv run python -m app.operator_cli grant --channel demo_parking --user bob
uv run python -m app.operator_cli revoke --channel demo_parking --user bob
```

只能创建新的不可变频道，绑定一个 POI；本版本授权管理整个该 POI，可发布指定子范围。默认均标记封闭演示，不代表完成正式运营资格核验。撤权使该作者频道声音不可被其他用户查询或播放。

## 验证与复跑

```sh
cd server
uv run pytest -q
uv run ruff check app tests
# 仓库根目录；需 Android SDK 35 与 JDK 17/21
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' android/gradlew -p android testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
uv run --project server python android/tools/run_voice_matching.py --serial emulator-5554
```

最后一项仅允许模拟器，自建临时数据库、随机设备凭证和本机 8767 服务；不读取正常服务器 .env、不清理其数据，不接真实高德/Bamboo/Linx。安装 Debug 包并切到专用测试会话；退出后测试服务器终止。报告和截图在 `android/build/reports/voice-matching/`。

服务端 `tests/test_voice_matching.py` 覆盖 AC-01～12 的规则，包含独立用户、越权、移动、取消、历史/条件/有效期、兴趣与静音、版本、抖动/新访问、权限撤销、去重、回执历史和知识按需；它不证明物理到达或 Android 实际扬声器效果。

Android `VoiceMatchingTest` 验证前台静音可查看公告、知识不入自动队列、情境录音发布、录音时公告更新不抢麦、原问题/回复链和连续定向回应。输入为模拟器 AudioRecord 与手动测试文字，测试回答者使用合成 WAV。

## 2026-10-24 验证记录

本次结果：服务端 **94 项通过**（原有 71 项 + 本轮 23 项）；Android **13 项单元测试通过**，`lintDebug/assembleDebug/assembleDebugAndroidTest` 通过；隔离模拟器 `VoiceMatchingTest` **1 项完整流程通过**。检查了实际渲染的发布表单、确认草稿、上下文和设置截图。单项完整流程包含多个操作，不等于完成全部真人体验场景。

| 目标场景 | 服务端证据 | 客户端证据 | 实体设备 |
| --- | --- | --- | --- |
| AC-01 同地不同问不串话 | 三用户、双问题、错配邀请被拒绝 | 显式绑定原问题；模拟器首轮与追问 | 待验收 |
| AC-02 移动、静音、取消 | 移动后有效回答、静音待听、取消拒绝迟到回答 | 静音收到原声，恢复后播放器完成 | 待验收 |
| AC-03 上下文与公开范围 | 参与者/父原声/接收人校验；拒绝 private | 展示回复链，选择具体作者与原句后录音 | 待验收 |
| AC-04 历史与即时观察 | 录制时间保留/未知标记；过期观察不召回 | 原声日期、历史说明和有效期展示 | 待验收 |
| AC-05 远程查询/未授权 | 远程浏览无访问副作用；未知条件不主动提示 | 主动浏览入口、情境默认关 | 待验收 |
| AC-06 兴趣与静音 | 两用户不同兴趣/互助配置均取得公告 | 静音显示公告；恢复按设置播放 | 待验收 |
| AC-07 更新/撤回/过期 | 另含运营撤权，旧音频对其他人不可读/播 | 录音期间更新，刷新后仅显示当前版本 | 待验收 |
| AC-08 抖动/楼层未知 | 抖动不新建访问，真正离开可再提示；楼层仅查询 | 显示 POI 附近与楼层未确认 | 待验收 |
| AC-09 冒充/越权运营 | 普通用户与跨 POI 发布均拒绝；确认时重查；频道提问不提权、运营回复不广播 | 频道公开提问与显式运营回应入口，模拟器确认来源 | 待验收 |
| AC-10 多入口/回执重试 | 合并理由，同命令重试幂等；逐访问留存回执 | 单元测试阻止旧访问回执套用新访问 | 待验收 |
| AC-11 录音/设置/重连 | 延后后重查静音、范围与过期内容 | 模拟器录音不抢麦；恢复播放与按需知识实际完成。断网/权限切换仍需设备组合测试 | 待验收 |
| AC-12 知识与告知 | 十条知识只供查询，公告优先入队 | 模拟器十条知识不自动播放；点击一条后播放器完成 | 待验收 |

可重跑的 [服务端测试](../../server/tests/test_voice_matching.py)、[Android 场景](../../android/app/src/androidTest/java/com/sonar/app/VoiceMatchingTest.kt)与[隔离启动器](../../android/tools/run_voice_matching.py)为证据来源。本地报告目录为 `android/build/reports/voice-matching/`；未统计真实会话覆盖率、物理到达准确率、驾驶期间体验或后台送达率。
