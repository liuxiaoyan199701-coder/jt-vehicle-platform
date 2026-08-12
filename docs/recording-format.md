# JT Platform 裸流录像格式

## 1. 目标与边界

录像直接保存 `jt-media` 帧组装阶段产出的 H.264/H.265/AAC/G.711A 裸帧，不转码、不封装为
MP4/FLV。默认每 30 秒切分一个分片，时长可配置。实时流与终端历史回放流使用同一格式，
通过 `streamKind` 区分。

写入文件前，`RecordSink` 将终端时间统一归一化为 UTC Epoch 微秒：已是 Epoch 微秒的值原样使用，
Epoch 毫秒乘以 1000，合法的 `YYMMDDHHMMSSmmm` BCD 时间按 UTC 解码；其余 JT/T 1078
相对毫秒时间以该流首帧的接收时刻为 UTC 锚点换算。文件内不得保存未归一化的终端原始时间。

每个已提交分片由三个同名文件组成：

```text
<segment-id>.jtr    裸帧记录
<segment-id>.jti    时间戳与关键帧索引
<segment-id>.ok     提交标记与校验元数据
```

读取端只认可存在 `.ok` 的分片。`.part` 文件以及没有 `.ok` 的孤立文件均视为未提交分片，
不得进入检索结果。

## 2. 目录与标识

目录按以下结构组织，所有动态路径段必须经过安全字符编码：

```text
<record-root>/<device-id>/<channel>/<stream-kind>/yyyy/MM/dd/HH/<segment-id>.*
```

`segment-id` 为分片首帧的 UTC Epoch 微秒值加随机后缀，避免同一微秒内并发创建冲突。
一个分片只能包含同一个 `StreamKey`、同一种视频编码配置。编码或参数集发生不兼容变更时
必须立即切片。

## 3. `.jtr` 数据文件

所有多字节整数使用网络字节序（big-endian）。文件头固定部分如下：

| 偏移 | 长度 | 字段 | 说明 |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `JTR1` |
| 4 | 2 | version | 当前为 `1` |
| 6 | 2 | fixedHeaderLength | 当前为 `24` |
| 8 | 8 | startTimestampUs | 首条记录的 UTC Epoch 微秒值 |
| 16 | 4 | descriptorLength | 后续 UTF-8 JSON 描述区长度 |
| 20 | 4 | reserved | 必须写 `0`，读取时忽略 |

描述区紧随固定头，至少包含：

```json
{
  "deviceId": "device-1",
  "channel": 1,
  "streamKind": "main",
  "videoCodec": "H264",
  "audioCodec": "G711A"
}
```

描述区之后是连续帧记录。每条记录包含 20 字节记录头：

| 长度 | 字段 | 说明 |
|---:|---|---|
| 4 | recordLength | 记录头之后的负载字节数 |
| 1 | frameType | 与实时裸流协议的 `MediaFrameType.wireValue` 一致 |
| 1 | codec | `0=UNKNOWN, 1=H264, 2=H265, 3=AAC, 4=G711A` |
| 2 | flags | bit0=关键帧，bit1=参数集，bit2=音频配置，其余保留 |
| 8 | timestampUs | UTC Epoch 微秒值 |
| 4 | payloadLength | 必须等于 `recordLength`，用于截断校验 |
| N | payload | 未修改的裸负载 |

读取时若记录头不完整、两个长度不一致、或剩余字节少于 `payloadLength`，该记录及其后的内容
均视为截断数据，不得输出。

### 参数集前导

视频分片的第一组记录必须依次包含解码所需的最新参数集：

- H.264：SPS、PPS
- H.265：VPS、SPS、PPS

参数集之后的第一帧视频必须是关键帧。创建新分片时尚未具备完整参数集或关键帧，则继续缓存，
不得提交不可独立解码的分片。纯音频分片不要求视频参数集，但 AAC 必须以音频配置记录开头。

## 4. `.jti` 索引文件

索引头固定为 16 字节：

| 偏移 | 长度 | 字段 | 说明 |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `JTI1` |
| 4 | 2 | version | 当前为 `1` |
| 6 | 2 | entryLength | 当前为 `24` |
| 8 | 8 | startTimestampUs | 必须与 `.jtr` 一致 |

其后每个索引项固定 24 字节：

| 长度 | 字段 | 说明 |
|---:|---|---|
| 8 | timestampUs | 帧时间戳 |
| 8 | fileOffset | 对应 `.jtr` 记录头的绝对偏移 |
| 4 | totalRecordLength | 20 字节记录头加负载长度 |
| 4 | flags | 与数据记录 flags 一致 |

至少为全部关键帧和参数集写索引；实现可以为每帧写索引。按时间点跳转时，先在目标时间之前
查找最近的关键帧，再从该偏移顺序读取。索引不得指向文件边界之外，越界分片视为损坏。

## 5. `.ok` 提交标记

`.ok` 为 UTF-8 JSON，至少包含：

```json
{
  "version": 1,
  "startTimestampUs": 0,
  "endTimestampUs": 0,
  "frameCount": 0,
  "keyFrameCount": 0,
  "dataBytes": 0,
  "indexBytes": 0
}
```

`endTimestampUs` 为最后一条完整记录的时间戳。检索服务使用 `.ok` 的起止时间生成可用时间段，
相邻分片之间的间隔不超过一个可配置容差时可以合并为同一时间段。

## 6. 原子提交与崩溃恢复

写入顺序必须为：

1. 创建唯一的 `.jtr.part` 与 `.jti.part`，顺序追加数据与索引。
2. 分片结束时分别 `force/fsync` 两个文件并关闭句柄。
3. 将两个 `.part` 原子重命名为 `.jtr` 与 `.jti`。
4. 写入并 `fsync` `.ok.part`，最后原子重命名为 `.ok`。
5. `RecordSink` 只有在第 4 步成功后才能发布录像元数据。

启动扫描时删除超出恢复宽限期的 `.part` 文件；没有 `.ok` 的 `.jtr/.jti` 不进入检索，
可在后台校验完整性后补交或清理。异常退出最多损失当前未提交分片，已有 `.ok` 的分片保持可用。

## 7. 校验规则

读取或检索前必须校验：magic、version、头长度、描述区上限、时间范围、索引偏移、记录长度、
参数集前导与首个视频关键帧。单个损坏分片只从结果中排除并记录原因，不得使同一路流的其他
分片不可检索或回放。
