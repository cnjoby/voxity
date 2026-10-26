# 音频格式核对：历史WAV与新Ogg Opus

日期：2026-10-26，Asia/Shanghai。当前生产：`20261026-opus-contract-031`。030已支持Opus；031补齐OpenAPI版本、二进制类型与上传描述，增加确切的Opus utterance验证。

## Android与历史素材

当前Android支持WAV和Ogg Opus：历史WAV直接播放；Ogg下载后解码为PCM/WAV，再进入增益、回声参考和播放链路。素材仍须满足权限、有效期和来源规则，合成素材不会获得真人回复资格。

历史文件按保存格式返回，不批量转码。文件格式与OpenAPI软件版本分别描述。

## ESP32格式合同

| 保存/上传格式 | GET响应 | 首部 | 终端处理 |
| --- | --- | --- | --- |
| PCM WAV | `audio/wav` | `RIFF` | 解析fmt/data，输出PCM |
| Ogg Opus | `audio/ogg` | `OggS` | Ogg解封装、Opus解码，输出PCM |

只播放现有WAV样本可继续现有实现。接收新Opus内容时需处理对应格式；已有Linx Opus解码器可复用，但HTTP的Ogg封装仍须解析。Linx WSS使用裸Opus包，两种封装不能混用。不支持的格式应报告播放失败，不报played。

## 当前验证证据

- 历史记录`p_2f564e48abdd66ad79c837ab`重新鉴权下载：200、`audio/wav`、133164字节、16kHz/16-bit/单声道、4.16秒，保持原格式。
- 服务器真实curl隔离验证：Ogg Opus utterance返回200并生成question；对应下载返回200、`audio/ogg`、`OggS`、22503字节，与上传字节及哈希完全相同。
- 隔离实例只监听回环地址，使用临时数据库和合成音频；进程和临时库均已清理，没有向生产演示点位发布测试消息。
- 031候选以voxity用户运行隔离测试，含Opus utterance、草稿、存储、下载及门禁，全部通过。本地后端258项测试及Ruff通过。
- 生产OpenAPI的`info.version`现为`0.3.0`；两个下载接口列出WAV/Ogg二进制响应，multipart描述包含两类上传格式。先前0.1.0及JSON响应描述是文档未同步。

测试utterance还需正确Bearer、会话ID、revision、UUID、metadata和授权。格式通过不保证业务发布，应同时查看status/action。415/INVALID_AUDIO为音频校验，401/403为鉴权/授权，409/410为状态/时效，422为字段问题。

预生成私人回复的核验门禁仍未放行，与Ogg格式支持独立；验证格式不必开启`client_managed_voice`或扩大生产授权。

031切换前后159条消息/159份音频、身份和配置一致，数据库完整性与公网检查通过。服务器备份：`/var/backups/voxity/opus-contract-20261026/`；本地证据：`output/opus-contract-20261026/`。本轮未操作手机或ESP32。
