# JT Vehicle Platform

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-42b883.svg)](https://vuejs.org/)

[English](README.en.md)

**开箱即用的部标车联网平台**。终端按 JT/T 808 接入，视频按 JT/T 1078 推流，
浏览器里直接看实时地图、实时视频和历史轨迹——**不依赖 Redis、消息队列或任何外部业务系统**。

> 一条命令跑起全栈：
> ```bash
> docker compose up -d --build     # 然后打开 http://localhost
> ```

### AI 助手：一句话运维，答案直接画出来

用中文问平台上的任何数据，也可以让它帮你建档、建围栏、处置告警。
**答案不只是文字——位置、路线、趋势会直接渲染在对话里**，输入框始终可用，可以边看边追问。

问「这台车在哪」，除了中文地址，地图直接长在回答下面：

![AI 对话内嵌实时位置](docs/images/ai-live-map.png)

问「看看昨天的轨迹」，折线、起终点、里程与最高速一并给出：

![AI 对话内嵌行驶轨迹](docs/images/ai-track-map.png)

要看画面时给一张卡片而不是直接开流——**开流会向路上那台车下发指令，这种事由人点一下**。
点开后在右侧面板播放，对话区照常可以继续输入：

![AI 对话中的实时视频](docs/images/ai-live-video.png)

改动数据一律走确认卡片：AI 只能**提议**，执行由前端用当前用户自己的令牌调既有接口完成，
因此权限、数据范围与审计全部沿用既有通道，AI 侧没有任何特权。

### 轨迹回放

按车辆和时间段查询历史轨迹，地图上绘制行驶路线并支持 1x–16x 倍速回放，
同时给出里程、最高速、平均速与轨迹点数。设备上报的是 WGS-84 坐标，入库时转换为
GCJ-02 后再渲染，因此不会出现整体偏移。

![轨迹回放](docs/images/track-playback.png)

### 运营看板

聚合建档车辆、在线/行驶、静止/离线、未建档在线、未关闭告警与当日里程，
并展示近七日运营趋势、告警分级分布和最近告警动态。

![运营看板](docs/images/dashboard.png)

## 它解决什么问题

做部标车辆监控，通常要自己啃 JT/T 808 的分包与转义、1078 的裸流重组、终端 ID 与手机号的映射、
浏览器端的 H.264 解码……这些坑本项目都已经趟过并且有对应的代码和文档。

拿到手就是一套能跑的完整链路：

```
车载终端 ──JT/T 808/1078──▶ 协议网关 ──HTTP 投递──▶ 业务后端 ──REST/WS──▶ 控制台前端
                              │                      (SQLite)                 │
                              └────────── 裸流 WebSocket ──────────────────────┘
```

| 模块 | 职责 | 默认端口 |
|---|---|---|
| `jt-platform` | JT/T 808 信令、JT/T 1078 媒体、开流调度与事件投递 | `7100`、`7101`、`7810-7815`、`8100`、`8109` |
| `jt-console` | 认证、车辆/轨迹/状态、运营统计、告警/围栏、实时广播与开流代理 | `8300` |
| `jt-console-ui` | 运营看板、实时监控、告警处置、电子围栏、轨迹回放与视频播放 | `9527`（开发）/ `443`（部署） |
| `packages/jt-player` | 零运行时依赖的浏览器裸流播放器 SDK（含 Vue3、React 适配） | — |
| `jt-terminal-simulator` | 用电脑摄像头模拟 808 终端与 1078 推流，无真车也能验证 | 桌面客户端 |

技术基线：JDK 25 · Spring Boot 4.1 · Netty · Vue 3 · SQLite。

## 核心能力

- JT/T 808 2011、2013、2019 终端接入，兼容 JT/T 1078、苏标主动安全相关消息。
- H.264、H.265、AAC、G.711A 媒体接入，按主码流、子码流、历史回放和对讲分端口监听。
- 两步式开流与一次性媒体 token，客户端直连被调度的媒体节点。
- 裸流 WebSocket 播放、独占或混音对讲、裸流分片录像、检索、回放和离线 MP4 导出。
- API 与 RocketMQ 可独立或同时启用的协议消息投递。
- 车队运营看板聚合建档、在线、行驶、静止、未处理告警和当日里程，并展示近七日趋势、告警分布和最近动态。
- JT/T 808 协议告警和围栏告警持久化、去重、组合筛选、分级及确认/关闭处置。
- GCJ-02 圆形电子围栏创建、启停、车辆分配、进出边界与围栏内限速判定。
- 车辆运营详情聚合档案、最新状态、当日/近七日指标和最近告警，并提供监控、轨迹和视频快捷入口。
- AI 助手用自然语言查询与运维平台：流式对话、跨会话留存，改动数据一律经确认卡片由用户执行。
- AI 回答内嵌实时位置、行驶轨迹、统计图表与实时视频；有副作用的视图（开流）必须用户显式点击。
- 同一个可执行 JAR 支持 `standalone` 单进程和 `api`、`signal`、`media` 分进程部署。

## 快速开始（Docker，推荐）

只需要装了 Docker，不用准备 JDK、Maven 和 Node：

```bash
git clone https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform.git
cd jt-vehicle-platform
docker compose up -d --build
```

首次构建要下载 Maven 与 npm 依赖，耗时较长；之后会命中缓存。启动完成后：

```bash
# 取管理员账号（本地模式下每次启动随机生成，不使用默认口令）
docker compose logs console | grep "jt-console] 用户名\|jt-console] 密码"
```

浏览器打开 **http://localhost** 登录即可。

终端接入地址是宿主机的 `7100/TCP`（或 `7101/UDP`），视频推流端口 `7811-7814`。
没有真实车机时，用 [终端推流模拟器](docs/terminal-simulator.md) 就能跑通整条链路。

> **必须用 `localhost` 访问**：视频播放依赖浏览器的 WebCodecs，而它只在安全上下文中可用。
> `http://localhost` 属于安全上下文，换成 IP 或域名访问就必须配 HTTPS，否则视频无法解码。
>
> 接入**真实车机**时还要设置宿主机可达地址，否则终端收到开流指令也连不上：
> ```bash
> MEDIA_REACHABLE_ADDRESS=192.168.1.10 docker compose up -d
> ```
>
> 这套编排面向本地体验：设备鉴权为 `allow-all`、开流鉴权关闭、走明文 HTTP。
> 生产部署请用 `deploy/` 下的脚本，它带证书校验、蓝绿发布与失败自动回滚。

## 从源码启动

### 1. 准备环境

- JDK 25 或更高版本，确认 `java -version` 指向正确的 JDK。
- Maven 3.9 或更高版本。
- 空闲端口：`7100/TCP`、`7101/UDP`、`7810-7815/TCP`、`8100/TCP`、`8109/TCP`。

网关本身不依赖 `ffmpeg` 完成启动、终端接入、录像、检索或裸流回放；只有离线 MP4 导出需要它。
Windows 摄像头推流模拟器需要用户另外安装带 `dshow`、`libx264` 和 `pcm_alaw` 的 FFmpeg，详见
[终端推流模拟器](docs/terminal-simulator.md)。仓库和便携包都不会捆绑 `ffmpeg.exe`。

### 2. 构建

```bash
cd jt-platform
mvn -B -ntp clean package
```

构建产物为：

```text
jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar
```

### 3. 启动

```bash
java -jar jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar
```

未指定 profile 或角色时默认以 `standalone` 启动，信令、媒体与 API 位于同一进程。

### 4. 检查健康状态

```bash
curl --fail http://127.0.0.1:8109/actuator/health
curl --fail http://127.0.0.1:7810/health
```

两个请求都应返回包含 `"status":"UP"` 的 JSON。此时 JT/T 808 终端可接入 `7100/TCP` 或
`7101/UDP`，业务侧 REST API 位于 `8100/TCP`。

## 安全启动业务控制台

本地开发允许控制台在未配置管理员哈希时生成本次进程有效的一次性密码，并只在启动终端显示。
投递密钥必须由控制台和网关共同使用，不能复用管理员密码或浏览器 token。先在两个启动终端中设置
同一个随机值，例如自行生成至少 32 字节随机密钥后设置：

```powershell
$env:JT_CONSOLE_INGEST_KEY = "<same-random-ingest-key>"
```

启动控制台后端：

```powershell
$env:JAVA_HOME = "D:\path\to\jdk-25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd jt-console
mvn -B -ntp spring-boot:run
```

记录启动终端显示的一次性管理员密码。另开终端启动前端：

```powershell
cd jt-console-ui
pnpm install --frozen-lockfile
pnpm dev
```

浏览器访问 `http://127.0.0.1:9527`。开发配置把 HTTP 请求发往 `127.0.0.1:8300`，实时连接使用
`ws://127.0.0.1:8300/ws/live`；服务端只允许配置清单内的 Origin，并在 WebSocket 子协议中校验短期
访问 token。前端遇到 HTTP 401 时只执行一次刷新轮换，刷新失败会清理会话并返回登录页。

需要让网关向控制台投递事件时，在网关外部配置中启用 API 通道并使用同一密钥：

```yaml
jt:
  delivery:
    channels: [api]
    api:
      endpoint: http://127.0.0.1:8300/ingest/jt-events
      headers:
        X-JT-Ingest-Key: "${JT_CONSOLE_INGEST_KEY}"
```

控制台的车辆、轨迹、监控、诊断和开流 API 默认都要求 Bearer token；`/ingest/jt-events` 只接受独立
投递密钥。不要把 token 放进 URL、日志、源码或前端环境文件。可使用
`jt-console/tools/verify-ingest.sh` 验证未授权拒绝、登录、幂等和精确设备键。

### 账号、角色与多租户

账号存在数据库里，不再来自环境变量。`JT_CONSOLE_ADMIN_USERNAME` / `JT_CONSOLE_ADMIN_PASSWORD_HASH`
只在**账号表为空**时用于引导首个平台管理员；引导完成后它们不再参与认证——两套真值来源会在改密后
留下「旧密码仍能登录」的后门。

- **权限**由后端代码定义权限码目录，启动时同步进库；界面只能勾选，不能新建。
- **角色**是数据：四个内置角色（平台管理员 / 租户管理员 / 租户操作员 / 租户只读）由代码同步维护、
  不可改删；租户可自建角色并选择数据范围（本租户全部 / 本部门及以下 / 本部门 / 自定义部门）。
- **账号可绑定多角色**，权限取并集、数据范围取并集。角色调整最迟 30 秒生效；禁用账号、停用租户、
  重置密码则**即时**撤销会话（含实时 WebSocket）。
- **租户是硬边界**：`vehicle.tenant_id` 是「设备 → 租户」的唯一权威映射，轨迹、状态、告警、统计、
  多媒体都经它过滤。越权访问一律返回「不存在」，不用 403——403 会告诉对方「这个标识存在，只是不属于你」。
- **部门是租户内的软范围**，车辆可挂部门；未分配部门的车辆只对「本租户全部」范围可见。
- **岗位只是人事标签**，不参与任何权限判定。

自助注册默认关闭（`jt.console.registration.enabled`）：它是公网可达的写入口，应由部署方按商务需要
显式开启。开启后受图形验证码、来源限流与人工审批三重约束，注册成功也只是进入待审批。

### 从单租户版本升级

启动时按 `PRAGMA user_version` 自动执行增量迁移，无需手工操作：建全部新表、给车辆/车队/围栏补
归属列、创建默认套餐与默认租户、把存量数据全部归入默认租户、并用环境变量引导平台管理员。
**升级后原管理员用户名密码直接可用，所见与升级前完全一致**（平台管理员跨租户可见全部数据）。

每步迁移单事务，失败即回滚并中止启动、版本号不推进。回滚到旧版本时新列不被旧 SQL 引用、新表被忽略；
仍建议升级前备份 `data/jt-console.db`。

## 运营功能与 API

以下运营端点均位于控制台，默认继承 Bearer 认证：

| 功能 | 方法与路径 | 说明 |
|---|---|---|
| 运营总览 | `GET /api/dashboard/overview` | 车队状态快照、连续七日趋势、告警分布和最近动态 |
| 告警查询 | `GET /api/alarms` | 支持 `status`、`level`、`source`、`deviceId`、`type`、`keyword`、`start`、`end`、`page`、`pageSize` 组合筛选 |
| 告警详情 | `GET /api/alarms/{id}` | 返回告警发生、位置和处置审计信息 |
| 告警处置 | `POST /api/alarms/{id}/acknowledge`<br>`POST /api/alarms/{id}/close` | 请求体均为 `{ "note": "..." }` |
| 围栏集合 | `GET /api/geofences`<br>`POST /api/geofences` | 查询或创建圆形围栏 |
| 围栏资源 | `GET /api/geofences/{id}`<br>`PUT /api/geofences/{id}`<br>`DELETE /api/geofences/{id}` | 读取、更新或删除围栏 |
| 围栏车辆 | `PUT /api/geofences/{id}/vehicles` | 以 `{ "deviceIds": [...] }` 整体替换已建档车辆分配 |
| 围栏启停 | `PUT /api/geofences/{id}/enabled` | 以 `{ "enabled": true }` 启用或停用围栏 |
| 车辆运营详情 | `GET /api/vehicles/{deviceId}/profile` | 按精确 canonical `deviceId` 聚合档案、状态、运营指标和最近告警 |

协议报警只在活动状态从无到有时创建告警，持续活动只更新最后出现时间，解除后再次活动才创建新记录。
告警从 `OPEN` 可确认为 `ACKNOWLEDGED`，`OPEN` 或 `ACKNOWLEDGED` 均可关闭为 `CLOSED`；处置保留备注、操作者和时间，已关闭告警不能重复处置。

围栏使用 GCJ-02 圆心和球面距离判定。车辆的第一次有效位置只建立内外基线，后续真实跨界才按开关生成进入或离开告警；围栏内持续超速按活动条件去重。停用围栏会停止后续判定并解除相关活动条件，删除围栏会清理分配和运行状态，但保留已产生的告警历史。

按日运营汇总、近七日趋势和时间筛选使用可配置的业务时区，默认为 `Asia/Shanghai`：

```yaml
jt:
  console:
    operations:
      zone-id: Asia/Shanghai
```

## 设备标识规则

- canonical `deviceId` 是 JT/T 808 报文头和 JT/T 1078 RTP 头共同携带的、协议解码后的
  `mobileNo/SIM`。
- T0100 的厂家终端 ID 是信令路由别名，可以定位同一 808 会话，但不能作为媒体 `StreamKey` 或 RTP
  SIM 替代值。
- 控制台只对 canonical ID 做首尾空白清理，随后执行精确字符串存储和比较。协议解码后的 `00123`
  与 `123` 是两个设备，禁止再做 `ltrim`、数值转换或正则去零。
- 固定宽度 BCD 编码增加或移除的填充零属于 wire format；精确字符串语义从协议层完成解码后开始。

模拟器应分别填写 T0100 终端 ID 与 `mobileNo`。平台可以通过任一信令别名下发 T9101，但模拟器发送
1078 RTP 时始终编码 `mobileNo/SIM`。

## 默认零依赖配置

| 能力 | 默认值 | 行为 |
|---|---|---|
| 设备鉴权 | `allow-all` | 不访问设备档案服务，适合本地联调 |
| 开流鉴权 | `disabled` | 不访问 JWKS，适合本地联调 |
| 消息投递 | `channels: []` | 不访问 API 或 MQ |
| 流注册表 | `memory` | 运行时状态保存在内存中 |
| 录像 | 实时流与历史流均关闭 | 不产生录像文件 |

这些默认值用于开箱验证，不是生产安全基线。生产环境至少应启用设备鉴权、开流鉴权，并限制管理端口
和内部接口的访问来源。

## 企业能力开关

配置可写入外部 `application.yml`，启动时使用：

```bash
java -jar jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=optional:file:/etc/jt-platform/
```

### AI 助手

默认关闭。启用需要同时给出模型开关与密钥，两者都不冗余——只给密钥不开开关，助手不会装配；
只开开关不给密钥，启动就会失败：

```bash
export JT_AI_CHAT_MODEL=deepseek
export JT_AI_API_KEY=<你的密钥>
# 可选：默认 deepseek-v4-flash
export JT_AI_MODEL=deepseek-v4-flash
```

对话里内嵌的地图还需要高德 Key（与服务端逆地理编码用的是**两把不同类型**的 Key，别弄混）：

```bash
export JT_CONSOLE_AMAP_KEY=<Web端 JS API 类型的 Key>       # 前端地图渲染
export JT_CONSOLE_AMAP_SECURITY_CODE=<该 Key 的安全密钥>
export JT_CONSOLE_AMAP_WEB_KEY=<Web服务 类型的 Key>        # 服务端逆地理编码
```

**密钥一律走环境变量，不入仓**。未配置地图 Key 时对话里的地图会显示一句提示，其余功能不受影响。

助手能做什么由**发起人自己的角色权限**决定：它看不到的车就提议不了，也展示不了。
每轮对话最多展示 4 个视图，同一台车的同类视图只出一张。

### 设备鉴权

小规模静态名单可以直接配置终端 ID：

```yaml
jt:
  auth:
    device:
      mode: local-list
      local-list:
        - "013800138000"
```

接入业务系统时使用远程设备信息查询：

```yaml
jt:
  auth:
    device:
      mode: remote-api
      remote:
        endpoint: https://business.example.com/api/devices/by-terminal
        unavailable-policy: deny
        connect-timeout: 2s
        request-timeout: 3s
        cache-ttl: 5m
        cache-maximum-size: 100000
```

网关发起 `GET <endpoint>?terminalId=<terminalId>`。成功响应是设备信息对象，至少包含非空
`deviceId`，可以包含 `terminalId`、`mobileNo`、`plateNo`、`tenantCode`、`tenantActive`；响应不得包含
`allowed`、`authorized`、`accessAllowed` 或 `decision` 等权限判定字段——档案服务只陈述事实，
判定始终留在网关，控制台故障才能靠 `unavailable-policy` 降级而不是把设备全部踢下线。
生产环境建议使用 `deny`，在档案服务不可用时拒绝接入。

`tenantActive=false`（租户被停用或已到期）的设备一律拒绝。查无此档的设备按
`unregistered-device-policy` 处理，默认 `reject`；若交付流程是「先接入、后建档」，显式改为 `allow`：

```yaml
jt:
  auth:
    device:
      remote:
        unregistered-device-policy: allow
```

本仓库的 jt-console 自带该档案接口，把 `endpoint` 指向它即可：

```yaml
jt:
  auth:
    device:
      mode: remote-api
      remote:
        endpoint: http://127.0.0.1:8300/gateway/device-registry
        unavailable-policy: allow
        headers:
          X-JT-Registry-Key: "${JT_CONSOLE_DEVICE_REGISTRY_KEY}"
```

同一把密钥要配到控制台的 `jt.console.tenancy.device-registry-key`。它与投递密钥、管理员凭据、
浏览器 token 相互独立——这条链路的调用方是网关进程而不是人。留空表示该接口不开放。

租户被停用或到期时，控制台会调用网关的 `POST /internal/devices/disconnect` 断开其设备；
该接口用 `jt.signal.admin-key` 校验同一把密钥。断连只是加速手段，真正的拒绝发生在设备鉴权侧。

### 开流鉴权

```yaml
jt:
  auth:
    stream:
      mode: jwt
      jwks-uri: https://auth.example.com/.well-known/jwks.json
      jwks-cache-ttl: 10m
      token-ttl: 60s
```

当前只接受 RS256，完整约定见 [开流 JWT 对接契约](docs/jwt-auth-contract.md)。

### 消息投递

```yaml
jt:
  delivery:
    channels: [api, rocket-mq]
    api:
      endpoint: https://business.example.com/api/jt/messages
    rocket-mq:
      name-server: mq.example.com:9876
      topic: jt-messages
      producer-group: jt-platform-producer
      namespace: ""
```

两个通道可以只启用一个。关键消息在内存队列满时会写入相对工作目录下的
`data/delivery-overflow/`，部署时应为其提供持久化空间。

### 共享注册表与分进程

默认注册表配置为：

```yaml
jt:
  registry:
    type: memory
```

在分进程模式下，API 角色持有集中内存状态，媒体角色通过内部 HTTP 访问它。当前版本尚未提供 Redis
实现，设置 `type: redis` 会在启动时明确失败；因此 API 角色只能运行一个实例，重启会丢失运行时流、
媒体实例和一次性 token 状态。真实分进程命令、当前扩展边界和滚动顺序见
[部署指南](docs/deployment.md)。

## 工程结构

| 路径 | 职责 |
|---|---|
| `jt-platform/jt-protocol` | 协议消息模型与编解码 |
| `jt-platform/jt-common` | 跨模块端口、模型、注册表和调度接口 |
| `jt-platform/jt-delivery` | API、RocketMQ 投递及可靠性控制 |
| `jt-platform/jt-signal` | JT/T 808 信令接入、设备鉴权与指令下发 |
| `jt-platform/jt-media` | JT/T 1078 接入、分发、对讲与录像 |
| `jt-platform/jt-api` | 开流与录像相关 REST API |
| `jt-platform/jt-boot-all` | 单进程及分角色启动入口 |
| `packages/jt-player` | 浏览器裸流播放器 SDK 及 Vue 3、React 适配层 |

## 更多文档

- [部署指南](docs/deployment.md)
- [架构说明](docs/architecture.html)
- [协议消息覆盖清单](docs/protocol-message-coverage.md)
- [裸流录像格式](docs/recording-format.md)
- [播放器 SDK](packages/jt-player/README.md)

## 致谢

本项目建立在以下开源项目之上，没有它们就没有这个平台：

| 上游项目 | 许可证 | 在本项目中的位置 |
|---|---|---|
| [yezhihao/jt808-server](https://github.com/yezhihao/jt808-server) | Apache-2.0 | JT/T 808、JT/T 1078、苏标协议模型与编解码，保留原 `org.yzh.**` 包名；同时以 `protostar`、`netmc` 两个依赖形式引入 |
| [jt1078-stream-server](https://gitee.com/lxygit0731/jt1078-stream-server) | MIT | `jt-platform/jt-media` 的媒体接入与分发、`packages/jt-player` 播放器（由本项目作者的早期项目重写而来） |
| [SoybeanAdmin](https://github.com/soybeanjs/soybean-admin) v2.2.0 | MIT © 2021 Soybean | `jt-console-ui` 的布局、主题、路由与请求层 |

以及 Spring Boot、Netty、RocketMQ、Vue、NaiveUI、UnoCSS、ECharts 等基础设施。
完整的第三方清单、许可证类型与归属边界见 [NOTICE](NOTICE)。

> 其中 `jt1078-stream-server` 是本项目作者更早的媒体流服务实现，本项目在它的基础上
> 重写了媒体接入与分发部分，并补齐了协议网关、业务后端与控制台，形成完整平台。
> 只需要裸流服务、不需要整套平台的话，可以直接用那个更轻量的项目。

地图能力由[高德开放平台](https://lbs.amap.com)的 JavaScript API 提供，属于商业服务而非开源组件，
需自行申请 key，用法见 [jt-console-ui/README.md](jt-console-ui/README.md)。

## 安全提示

仓库中的默认配置面向**本地联调**，不是生产安全基线。对外部署前至少要确认：

- 设备鉴权不再是 `allow-all`（改为 `local-list` 或 `remote-api`），开流鉴权不再是 `disabled`
- 管理端口（`7810`、`8109`）与 `/internal/**`、`/device/**` 不可从公网访问
- 控制台管理员密码与投递密钥由 `deploy/init-credentials.sh` 生成，不使用任何默认口令
- 前端的高德 key 写在 `.env.prod.local` / `.env.test.local` 而非受版本控制的 `.env.prod`（Vite 加载顺序决定只有 `.*.local` 能覆盖模式文件里的空值）

`deploy/` 下的脚本提供了一套带 SHA256 校验、蓝绿发布与失败自动回滚的部署流程，
参数全部来自 `deploy/deploy.env.example`，复制后填入自己的主机信息即可使用。

## 许可证

本项目整体以 [Apache License 2.0](LICENSE) 发布。

项目合并了 Apache-2.0 与 MIT 两类上游代码。选择 Apache-2.0 是因为其中包含 Apache-2.0 的衍生
代码，该许可证不允许降级发布；MIT 代码可以合法并入。各子目录保留其原有许可证文件
（[jt-console-ui/LICENSE](jt-console-ui/LICENSE)、[packages/jt-player/LICENSE](packages/jt-player/LICENSE)、
[jt-platform/LICENSE-MIT](jt-platform/LICENSE-MIT)），具体来源与边界见 [NOTICE](NOTICE)。

## 踩坑笔记

对接过程中遇到的真实问题与排查过程，也是本项目的设计依据：

- [设备明明连上了，平台上却找不到这台车](docs/articles/01-device-id-pitfall.md)
  —— 终端手机号、终端 ID 与业务设备 ID 的区别，BCD 前导零丢失，白名单比对的到底是哪个字段
- [JT/T 1078 视频在浏览器里播不出来：一个和编码无关的坑](docs/articles/02-webcodecs-secure-context.md)
  —— WebCodecs 的安全上下文要求，以及上了 HTTPS 之后混合内容策略带来的连锁问题
- [协议网关往业务系统投递消息：幂等、顺序与背压该怎么处理](docs/articles/03-delivery-idempotency-backpressure.md)
  —— 为什么判重不能依赖异常分类，队列满时该丢什么，以及接收端响应慢为何会导致位置点被丢弃
