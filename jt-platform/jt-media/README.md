# jt-media

JT/T 1078 媒体接入模块：终端推流接收、分片重组、裸流分发、语音对讲与录像。

## 来源声明

本模块的媒体接入与分发行为改写自 **jt1078service / jt1078-stream-server**
（<https://gitee.com/lxygit0731/jt1078-stream-server>，MIT License）。

与 `jt-protocol` 保留上游 `org.yzh.**` 包名不同，本模块在重写过程中改用了
`io.github.jtplatform.media.**` 命名空间，源文件中因此**没有保留上游的文件头注释**。
这份说明用于补足这一点——上游归属仅凭仓库根目录的 `NOTICE` 与
`jt-platform/LICENSE-MIT` 记载，请勿在重构时一并移除。

版权归原 jt1078service 贡献者所有。

## 端口

实例号 `N` 取值 `1..9`，媒体实例 1 使用 `7810..7815`：

| 端口 | 协议 | 用途 |
|---|---|---|
| `78N0` | TCP/HTTP | 管理、健康与容量指标，仅运维内网 |
| `78N1` | TCP | 主码流接入 |
| `78N2` | TCP | 子码流接入 |
| `78N3` | TCP | 历史回放流接入 |
| `78N4` | TCP | 对讲媒体接入 |
| `78N5` | TCP/WebSocket | 裸流播放，默认路径 `/ws` |
| `78N6` | TCP | HTTP-FLV 扩展位，当前未实现、不监听 |

## 注意事项

- 媒体可达地址会写进下发给终端的 9101/9201 指令，必须是设备与播放器真正能连上的地址；
  在 NAT 或容器环境中使用自动探测会得到不可路由的内网地址。
- 录像默认关闭（`realtime-enabled` 与 `playback-enabled` 均为 false）。开启前请注意
  `RecordSink` 在 Netty worker 线程上同步写盘，磁盘抖动会直接影响媒体转发。
- `max-streams` 的默认值面向多核服务器，小规格机器需要按实际转发能力调小。
