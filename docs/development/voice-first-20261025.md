# 2026-10-25 语音优先 App 0.2.0 实现与交付

后续更新：当前为[0.2.1地点切换](location-switch-20261025.md)，快捷路口已按用户确认改为“金科路与高科中路交叉口”。本文保留0.2.0交付时的地点、哈希及验证事实。

当前 Android 为 **0.2.0（versionCode 6）**，包名 `com.sonar.app`；公网后端为 `20261025-voice-first-020`。按已批准的[体验设计](../design/ux-designs/ux-sonar-2026-10-25-voice-first/EXPERIENCE.md)实施，并采纳用户后续纠正：只维护一个当前版本，以真实高德 POI ID 为准，删除本地模拟。软件、部署、设备和用户验收分开记录。

## 用户流程

首页只保留“开始”和“连接与隐私”。准备页包含业务连接、灵矽绑定、系统权限、地点和隐私；已有绑定不必重新填写。开始后，屏幕只镜像当前状态、地点来源及安静／结束兜底，不再要求选择五种模式，也没有“我的任务”或“现场互助”开关。

普通问题先私有检索，缺少结果时可以建议问人；“帮我问问模力社区现在还有车位吗”“帮我转告……”表达公开动作。真实原声播放后可自然回答、明确“回复他”、继续追问、“再听一遍”、询问进展、“不用等了”或“解决了”。模型理解意图、决定检索与协作，执行器继续核对身份、原声、地点、时效、实际播放上下文和幂等请求。

“先别问我”“今天别问我”“以后别邀请我”分别使用会话、当地今天和长期偏好；自己的问题收到回应不受陌生邀请开关影响。合成试听保留合成来源，不成为真人问答对象。等待超时会在允许出声时私下告知一次，安静期间排队，不替用户重发。

本地中文停止模型支持安静、继续、结束、关麦和撤回类控制；断网停止不依赖 Linx。结束真正关闭麦克风，之后需要重新点击开始。主语音识别／合成仍使用已有 Linx 配置。完整单句上限30秒，超限提示重说，不截取半句公开；未知上传结果先核对请求，不自动重发。播放使用回声参考、平台 AEC 状态与识别文本保护，真实车内声学表现仍需实测。

## 真实地点与服务器音频

三处快捷入口由服务器 `/v1/me.place_shortcuts` 提供，ID 为真实 AMap `poi.id`，不再生成 `amap:hash` 或使用 `demo:*` 别名。

| 快捷地点 | AMap ID | GCJ-02 纬度、经度 |
| --- | --- | --- |
| 模力社区 | `B0K2KHYN77` | 31.184698, 121.617324 |
| 上海七牛信息技术有限公司（地址为人工智能岛19号楼） | `B0FFJSBSGR` | 31.184980, 121.609005 |
| 华夏中路与金科路交叉口 | `B0FFHPHNSH` | 31.173934, 121.602353 |

人工智能岛整体 POI `B0FFJEOSPR` 与19号楼的公司 POI 不同，原来属于整体园区的4条记录保持整体园区身份。远程选择通过 `/v1/me/remote-place`，坐标由服务器登记的真实 POI 派生，始终标为 `remote_place`，界面显示“远程地点 · 非手机实到”。切点只改变本人；已发问题的目标不被后续切换改写。返回手机位置需要系统定位，不冒充 GPS 到场。

Bob／ESP32 的运营配置固定 `B0K2KHYN77`，手机可在三处切换，也可语音搜索其他真实高德地点。原36条素材已移至 `server/demo-assets/`，每处12条；APK 无地点模拟素材、本地 Mock API 或预置现场录音。说“听一下这里的合成素材”会请求服务器、标明合成内容；其他有权限用户也能听到。离线控制模型和本地故障提示属于运行能力，不是地点模拟声音。

## 覆盖安装与制品

- 唯一当前交付：`output/sonar-0.2.0-voice-first.apk`，96,663,060 bytes（约92.2 MiB）。包体包含离线中文控制模型与各 ABI 的识别库。
- SHA-256：`54533034979b1371e68ec2a53aa3ede163c9c9b4473e819fe77b60e081ff6983`。
- 签名证书 SHA-256：`aab3e02e34cd3e0a3bdfa40495da3a547b8a9dfe30233b49dd178ed8d44284a5`；与此前交付0.1.4一致，模拟器使用 `adb install -r` 安装成功。
- 保持 Keystore `sonar-config-v1`、加密配置、Token、Linx URL／Token／设备身份／client ID、任务和回执。一次性读取旧三点选择并映射到原始 ID，关闭旧 Mock／手动测试转写配置；这是已有数据迁移，不是维护两套设备协议。
- 默认 `https://122.195.131.106`，已有旧本机8765默认地址仍按一次性迁移规则更新。自定义连接不随意覆盖。正常账户缓存不重置，不重新提交旧草稿。

APK 扫描未发现本机已知服务凭证，未包含 `.env`、`assets/demo/` 或 `onsite_question.wav`。未卸载、清理或修改实体手机数据；实体手机覆盖安装及现有绑定可用性由用户验收。

离线依赖为 Vosk Android 0.3.75、JNA 5.18.1 和 `vosk-model-small-cn-0.22`，从[官方模型清单](https://alphacephei.com/vosk/models)下载并固定 SHA-256；构建脚本为 `android/tools/prepare_voice_assets.py`，许可证随包。设备解压校验路径和体积，缓存本机模型供后续开始复用。

## 软件验证

| 范围 | 当前结果 | 能证明与不能证明 |
| --- | --- | --- |
| 后端 pytest | **210 passed**；Ruff通过 | 实际执行私有／公开、问答跟进、拒绝、过期、原声权限、30秒边界、地点迁移及上下文反例；不是实体设备证据 |
| Android JVM | **49 passed** | 协议、完整音频、控制词、回声与取消等逻辑 |
| Android Lint／构建 | 通过 | Debug主包、测试包；Lint仍有平台／依赖建议等warning，未宣称零warning |
| Android 配置／界面／交流 | **24项通过** | 首轮23通过、远程点位测试的文字断言误用了全文匹配；改为子串后该项重跑通过。实际文字本就包含远程标记，无业务代码修复 |
| Android native runtime | **12项通过** | 打包模型真实识别控制录音、实际Service／AudioRecord／MediaPlayer、HTTP action、启动竞态、撤回、超时和故障；隔离HTTP、无真人输入／扬声器输出 |
| 实际渲染 | 已查看Start、交流、安静截图 | 页面层级、远程来源、兜底控制可见；隔离账户未绑定的Start禁用状态也准确显示，不是假称已就绪 |

最终构建命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`（在 `android/`）。后端：`uv run pytest -q` 和 `uv run ruff check app tests`（在 `server/`）。

审查修复包括：讨论“分享／回复／解决了”不触发公开或结案；否定参与意愿不反向授权；无定位分享不能绕过在场条件；合成播放清除旧隐式真人绑定；状态询问后显式回复可恢复有效书签；启动中的安静／结束、取消中的暂停、会话版本倒退、未知分享撤回、回执轮转、超时及认证失败提示。未映射历史哈希地点作为部署阻断项检查，本次真实生产库所有活跃相关引用均已映射，无此类遗留。

本地证据在忽略目录 `output/voice-first-20261025/`：`android-build.txt`、`apk-verification.json`、`device/instrumentation-final.txt`、`device/remote-place-final.txt`、`device/runtime-final.txt`、`device/{start,active,quiet}.png`、`review/`。早期失败日志保留，最终结果以对应重跑为准。

## 当前服务器部署

已核对原运行版本 `20261025-modaily-shared-final`，对生产库快照预演，再备份并部署至 `/opt/voxity/releases/20261025-voice-first-020`，`/opt/voxity/current` 已指向新版本。27份 app 文件的 SHA-256 与本机发布清单、远端文件一致；voxity 和 Caddy 均 active。

`/etc/voxity/server.env` 保留原Token、高德、模型及其他设置；仅改新远程点位配置、180秒turn TTL和90秒邀请TTL。新表保存 voice sessions／preferences／remote choices。迁移保留全部130条原记录、身份、音频、不可变录制位置历史及幂等指纹，转换目标引用和当前选择，旧会话结束。数据库 integrity／foreign key检查通过。旧录制位置仍可能含历史模拟来源，属于来源证据，不能伪改成真实 GPS。

备份位于服务器 `/var/backups/voxity/voice-first-20261025/`，含 `server.env.before`、一致的 `data.before.tgz` 和迁移报告；目录700，敏感文件600。部署脚本具备失败恢复旧代码／配置／数据的处理；本次部署成功，未执行恢复。

公网验证通过 HTTPS／HTTP `/healthz`，未鉴权 `/v1/me` 为401；Alice和Bob均能读取三个真实快捷点及每处12条合成素材，Alice下载36条WAV与原清单SHA一致。真实高德搜索返回模力社区原始ID。默认会话保持私人／非连续分享，一次私人状态查询返回 responded 后结束测试会话；未向真实用户发布测试语音。Bob 经HTTP读取仍为模力社区 `remote_place`。证据：`live-verification.json`、`deployment-manifest.json`、`preflight-migration.json`。

## 手机与 ESP32 验收

本轮 ADB 只有 API36.1 模拟器，使用无音频输出环境。代码、模型识别、协议和服务器检查通过，不等于真人 Linx、物理扬声器、人声打断、车内回声或ESP32固件验收。

覆盖安装后，确认Token和灵矽仍就绪，选模力社区并点开始；说“帮我问问模力社区现在还有车位吗”。ESP32同学应听到该原声并回应，手机应自动听到回复，再说“回复他，从哪个门进？”核对双向链。然后切到七牛公司：手机地点改变、Bob保持模力社区；可明确“问问模力社区……”继续跨地点询问。最后测试“再听一遍”“安静”“继续”“不用等了”“结束”。这些实际设备结果尚待用户反馈，未用合成素材替代真人成功。

原工作区已有大量未提交修改，本次保留并延续这些实现；未将无关工作一并提交。审查入口为[规格与验收矩阵](spec-voice-first-app.md)、本文件、当前Android和后端代码。
