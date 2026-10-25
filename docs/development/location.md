# 真实定位与附近问答接入

日期：2026-10-24。实现：Android 原生前台定位、AMap 地点解析、SQLite 地点及录音位置快照、附近邀请。正常服务不允许模拟位置作为真实附近资格；测试显式允许。ESP32 既有模拟协议继续可用。

2026-10-25 增加用户明确选择的 `simulated_place` 点位联调：使用三个注册点位的真实坐标，允许收听与手动回应，始终标记模拟来源；不证明 GPS/真实到场，不放开通用 `android_mock`。本人选择、心跳、退出及 ESP32 临时固定规则见[点位联调协议](simulated-points-20261025.md)。

## 客户端交互

首次点说话时，若还没有目标地点，申请前台定位并解析附近地点；完成后点说话录音。以后在前台且允许互助时约每 15 秒尝试更新定位。退出前台取消定位请求与刷新；不使用后台定位权限。原生 GPS/网络定位不依赖 Google Play 服务。

首页显示询问目标、定位状态及录音按钮。点地点可选择当前位置、附近 POI，或输入城市/路口/景区名称搜索；同名结果由用户选择，不自动公开。用户手选远处目标不会改变自己的位置。定位过粗时必须手选目标，不能将粗定位当作具体路口；拒绝权限后仍可搜索地点。

目标和录制位置在录音草稿中分别冻结；尚未上传的提问可改目标，已冻结草稿的重试沿用原元数据。回答继承问题的目标，原声录制位置单独保存。无新鲜定位时快照为空，不能靠选目标获得现场资格。已发出问题的目标不会随手机移动而改变。

有人询问时优先显示回应卡片；提示音结束后仍保留回应入口，直到回答、拒绝、取消或过期。`prompted` 不是任务结束，不能从客户端页面消失，也不能反复自动播报。

## 服务端配置

仅在 `server/.env` 配置，不能放入 APK、Git 或公开网页：

```dotenv
SONAR_AMAP_KEY=填写高德Web服务Key
SONAR_LOCATION_TTL=60
SONAR_LOCATION_ACCURACY_M=100
SONAR_NEARBY_RADIUS_M=300
SONAR_ALLOW_MOCK_LOCATION=false
```

AMap Key 缺失返回 503 POI_NOT_CONFIGURED，供应商失败或超时返回 503 POI_UNAVAILABLE；不退回假 POI 或模拟位置。每个定位请求至多一次坐标转换和一次逆地理编码，外部调用不持有 SQLite 写事务。消息轮询不调用地图服务。

内部距离计算统一 GCJ-02；Android 原始 WGS84 通过高德官方转换接口处理一次，原始快照保留自己的坐标系。默认目标为约百米网格的地理区域，标签取道路附近或地址；最近店铺作为可选 POI，不能当作用户必然所在位置。地点ID内部生成，公开地点与作者精确坐标分开。

新版本启动以可重复的加列迁移保留原有用户、帖子、草稿和交付，增加 `places` 表及位置列。原模拟草稿幂等摘要保持兼容；不清空数据库。

## PUT /v1/me/location

鉴权同设备 Bearer。请求：

```json
{
  "expected_revision": 1,
  "location": {
    "latitude": 31.2,
    "longitude": 121.5,
    "coordinate_system": "wgs84",
    "accuracy_m": 10,
    "sampled_at": "2026-10-24T08:00:00Z",
    "source": "android"
  }
}
```

坐标仅为格式示例，不是用户位置。`sampled_at` 为实际采样时间，不能用重试/上传时间伪造刷新；旧于 TTL 或超前服务器 10 秒以上返回 422。`location:null` 立即撤销位置资格，不调用地图。版本冲突 409，客户端重新读取配置后重试，不覆盖静音/互助意愿。

响应 `{profile, places, server_time}`：`places` 为至多六个 `{id,label,latitude,longitude,kind}`，第一项为默认附近区域，其余是周边 POI。坐标均为公共目标的 GCJ-02 坐标；作者原始位置只在本人 profile.presence.location 中返回，不向其他用户交付。

真实 profile.presence 包含 `source=android|android_mock`、`direction=unknown`、`place_id`、`place_name`、原始 `location` 与服务端 `updated_at`。没有可用位置时 `source=unavailable`。采样时间与服务端接收时间独立。

`android_mock` 默认不具备附近资格，即使能够解析地点。仅隔离测试服务器允许 `SONAR_ALLOW_MOCK_LOCATION=true`；测试不是物理GPS验收。

## GET /v1/places?query=上海金科路

设备鉴权后搜索高德，query 长度 2–100 字符，返回 `{places,server_time}`，最多五项。客户端确认选中的 `id` 作为目标。不能直接上传任意经纬度冒充已解析的 POI；未知目标ID返回 422 PLACE_UNKNOWN。

## 既有接口的兼容扩展

- `PUT /v1/me/context`：`presence` 现在可省略，用于只改变 mode/assist/focus；省略时不刷新实际位置。旧 `source=simulated` 请求继续支持，且与真实位置资格隔离。MCP set_focus 同样不刷新物理位置。
- `POST /v1/posts/drafts`：`recorded_location` 为可选位置快照，格式与 location 一致；问题 target_place_id 可用上面接口返回的地点ID，direction 必须 `unknown`。旧 `zhonghuan_jinke + demo_exit` 不变。回答不传目标，继承所属问题，允许附带独立录制位置。
- `GET /v1/questions/{id}`：增加 `target_place:{id,label}`，用于等待/回答界面固定显示原任务目标。
- `GET /v1/inbox`：返回 pending 交付及仍可自愿回答的 prompted 邀请。`actionable=true` 的 prompted 邀请允许回应，但客户端只对 pending 自动播放提示。payload 增加 `location_scope=nearby|simulated`，不包含作者位置快照。

真实附近资格：位置来源允许、采样未过期、精度不差于 100 米，且“目标距离 + 定位精度 ≤ 300 米”，同时开启互助、未静音、问题未过期并且不是本人提问。半径与精度阈值是初始配置，未作道路验证。仅能声称附近，不能声称同车道、同匝道或同方向。

问题发布时对候选用户检查；用户稍后到达时通过位置更新和自己的 inbox 检查召回尚未见过的有效问题。唯一约束防止反复提示；已拒绝、取消或处理过的请求不会再次打扰。移动出范围、位置失效、静音时使邀请失效；发布回答前重新校验。

## 验证与复跑

服务端：`cd server && uv run pytest -q`。新增覆盖空间距离、精度、过期/未来时间、位置信任、坐标转换、隐私、移动、模式、先提问后到达、录音快照与旧草稿重试。

Android 构建使用 Android Studio JBR：

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./android/gradlew -p android assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
```

专用模拟器集成检查（正常服务数据不受影响，需真实 AMap Key）：

```sh
uv run --project server python android/tools/run_nearby_flow.py --serial emulator-5554 --two-clients
```

脚本要求模拟器，默认控制台端口 5554（可 `--console-port`）；注入显式测试坐标，创建隔离临时库和私有凭证，运行真实 AMap，依次切换两个 Android 用户会话，完成录音提问、提示后自愿录音回应、回到提问者播放原声。录音为模拟器 AudioRecord，无真人语音；文字由测试手动输入。报告在忽略目录 `android/build/reports/nearby-flow/`。结束后销毁测试服务器和凭证，应用停止，需按 README 重新配置正常账号。

旧固定地点检查继续使用 `run_live_flow.py`，它显式启用 `--simulated-location`。正常 configure_device 默认真实定位；该模拟参数仅供兼容演示使用。

已验证：真实高德搜索、WGS84 转换、逆地理编码；Android 模拟器定位 API 与同一模拟器上的双用户闭环。未验证：两台实体手机 GPS 与实际扬声器、真实STT、ESP32、道路方向/拓扑、后台唤醒及公网部署。
