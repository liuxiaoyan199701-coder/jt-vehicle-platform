# JT Platform 部署指南

本文档对应当前 `0.1.0-SNAPSHOT` 实现，覆盖端口、防火墙、单进程与分进程启动、持久化目录、裸机、
Docker、Kubernetes、优雅摘除和滚动更新。裸机、容器与 Kubernetes 都只是部署选择，使用的是同一份
业务代码和同一个 `jt-boot-all` JAR。

## 0. 控制台安全裸机基线

`deploy/` 提供单主机的安全发布基线，适用于同机运行 `jt-console`、`jt-platform`、Nginx 和前端静态
文件。脚本不会连接其他服务器；所有公网主机、媒体可达地址、JDK、制品、摘要、TLS 和防火墙选择都
必须由主机上的受限环境文件显式提供。

### 0.1 准备参数与制品

在目标 Linux 主机以 root 创建部署参数文件，权限保持为 `0600`：

```bash
install -d -o root -g root -m 0750 /etc/jt-deploy
install -o root -g root -m 0600 deploy/deploy.env.example /etc/jt-deploy/deploy.env
editor /etc/jt-deploy/deploy.env
```

至少填写以下内容：

- `PUBLIC_HOST`：与正式证书身份一致的控制台域名或地址。
- `MEDIA_REACHABLE_ADDRESS`：终端能连接的媒体地址，不能填写容器或管理网内部地址。
- `JDK_HOME`、`JDK_ARCHIVE_URL`、`JDK_ARCHIVE_SHA256`。
- 唯一 `RELEASE_ID`、网关/控制台 JAR、前端 `tar.gz` 及三个可信 SHA-256 摘要。
- `TLS_MODE=production` 时的完整证书链、私钥和 CA 文件。
- `FIREWALL_MODE=ufw`，或在已有外部防火墙且已核验端口清单时显式设置
  `FIREWALL_MODE=external` 与 `EXTERNAL_FIREWALL_CONFIRMED=true`。

摘要必须从可信发布清单取得，不能从同一个下载响应中临时读取。前端归档的根目录或 `dist/` 中必须
存在 `index.html`。仓库样例中的 `.invalid` 地址和空摘要不能直接部署。

### 0.2 首次初始化与发布顺序

从仓库根目录依次执行：

```bash
export DEPLOY_ENV_FILE=/etc/jt-deploy/deploy.env
deploy/01-probe-mirror.sh
deploy/02-install-jdk.sh
deploy/03-system-setup.sh
deploy/04-firewall-start.sh
deploy/05-deploy-console.sh
deploy/06-enable-https.sh
deploy/07-verify-deployment.sh
```

流程具有以下边界：

1. JDK 和三个应用制品先校验 SHA-256，再解压、暂存或切换。
2. `03-system-setup.sh` 创建不可登录的 `jt-console`、`jt-platform` 账户和各自可写目录，并幂等初始化
   相互独立的管理员凭据与投递密钥。
3. 主机防火墙只公开 `80/443`、`7100/TCP`、`7101/UDP` 和 `7811-7814/TCP`。`7810`、`7815`、
   `8100`、`8109`、`8300` 不得对公网放行。
4. 发布先停止事件生产方，再原子切换并启动控制台；只有控制台数据库健康为 `UP` 后才启动网关。
   网关 Actuator 和媒体 `/health` 都通过后才切换前端。
5. Nginx 候选配置先独立执行 `nginx -t`。生产 TLS 必须通过证书链、有效期、主机身份和密钥配对
   验证；自签名证书只允许显式 `development` 模式。
6. 任一步失败都返回非零状态。应用发布会恢复旧 `current` 链接，Nginx 激活失败会恢复旧配置。

最终浏览器只通过 `https://PUBLIC_HOST` 和同源 WSS 工作。Nginx 明确拒绝 `/internal/**`、
`/device/**`、`/actuator/**` 与 `/ingest/**`，并把 `/api/**`、`/ws/**` 和 `/media-ws` 分别转发到
回环地址上的控制台实时入口和媒体订阅入口。
控制台只信任同机回环 Nginx 提供的转发地址；Nginx 模板会用 `$remote_addr` 覆盖客户端传入的
`X-Forwarded-For`，避免公网请求伪造地址绕过认证限流。若以后在 Nginx 前增加负载均衡，必须显式
重新定义和验证受信代理链，不得直接放宽到整个私网网段。

### 0.3 凭据文件

| 文件 | 所有者与权限 | 内容/用途 |
|---|---|---|
| `/etc/jt-console/jt-console.env` | `root:jt-console 0640` | 部署模式、管理员 BCrypt 哈希、控制台投递密钥 |
| `/etc/jt-platform/jt-platform.env` | `root:jt-platform 0640` | 网关所需的同一投递密钥 |
| `/etc/jt-console/admin-initial-password` | `root:root 0600` | 初始管理员明文，仅供 root 交付和自动验收 |
| `/etc/jt-console/ingest.key` | `root:root 0600` | root 运维工具读取的投递密钥副本 |
| `/etc/jt-console/verify.env` | `root:root 0600` | 验收工具使用的密钥文件路径，不含 token |
| `/etc/jt-console/runtime.env` | `root:jt-console 0640` | JDK、数据库、Origin 和回环网关地址 |
| `/etc/jt-platform/runtime.env` | `root:jt-platform 0640` | JDK、媒体地址、容量和鉴权模式 |

服务账户不能读取管理员明文，不能改写 `/opt` 下的应用制品，也不能写入对方的数据目录。不要把这些
文件复制到仓库、制品、工单或聊天记录。`init-credentials.sh` 重复执行会保留已有凭据；轮换必须按下面
步骤显式进行。

### 0.4 管理员凭据轮换

> **账号迁入数据库后，本节只适用于「尚未引导过」的全新部署。**
> `JT_CONSOLE_ADMIN_PASSWORD_HASH` 仅在账号表为空时用于创建首个平台管理员；对已经跑起来的部署
> 改这个哈希不会改变任何人的密码。已有部署请在控制台里操作：
> 「系统管理 → 用户管理 → 重置密码」（会立即撤销目标账号全部会话），
> 或由本人在「系统管理 → 个人中心」修改（保留当前会话，踢掉其他设备）。

以下示例通过 root-only 临时文件传递新密码，命令行历史不包含密码值。将 `admin` 替换为实际配置的
管理员用户名：

```bash
umask 077
password_file="$(mktemp /etc/jt-console/.admin-password.XXXXXX)"
openssl rand -base64 36 >"$password_file"
new_hash="$(htpasswd -inBC 12 admin <"$password_file" | awk -F: 'NR == 1 {print $2}')"
sed -i "s|^JT_CONSOLE_ADMIN_PASSWORD_HASH=.*$|JT_CONSOLE_ADMIN_PASSWORD_HASH='${new_hash}'|" \
  /etc/jt-console/jt-console.env
install -o root -g root -m 0600 "$password_file" /etc/jt-console/admin-initial-password
rm -f -- "$password_file"
unset new_hash
chown root:jt-console /etc/jt-console/jt-console.env
chmod 0640 /etc/jt-console/jt-console.env
systemctl restart jt-console
/usr/local/libexec/jt-wait-console-ready
```

控制台会话保存在内存中，重启后旧访问 token 和刷新 token 全部失效。使用新密码重新登录，并运行
`/usr/local/sbin/jt-verify-deployment`。确认交付完成后，可按组织的密钥保管规则转移
`admin-initial-password`；若删除它，自动登录验收会明确失败，其他健康和端口检查不受影响。

### 0.5 投递密钥协调轮换

投递密钥必须在控制台和网关两侧同时更新。先停止生产方，避免轮换窗口产生大量 401 重试：

```bash
systemctl stop jt-platform
umask 077
next_key_file="$(mktemp /etc/jt-console/.ingest-key.XXXXXX)"
openssl rand -hex 32 >"$next_key_file"
new_key="$(tr -d '\r\n' <"$next_key_file")"
sed -i "s|^JT_CONSOLE_INGEST_KEY=.*$|JT_CONSOLE_INGEST_KEY='${new_key}'|" \
  /etc/jt-console/jt-console.env
sed -i "s|^JT_PLATFORM_INGEST_KEY=.*$|JT_PLATFORM_INGEST_KEY='${new_key}'|" \
  /etc/jt-platform/jt-platform.env
install -o root -g root -m 0600 "$next_key_file" /etc/jt-console/ingest.key
rm -f -- "$next_key_file"
unset new_key
chown root:jt-console /etc/jt-console/jt-console.env
chown root:jt-platform /etc/jt-platform/jt-platform.env
chmod 0640 /etc/jt-console/jt-console.env /etc/jt-platform/jt-platform.env
systemctl restart jt-console
/usr/local/libexec/jt-wait-console-ready
systemctl restart jt-platform
```

随后运行部署验收和 `jt-console/tools/verify-ingest.sh`。新密钥成功后，旧密钥请求必须返回 401 且不产生
幂等、轨迹、状态或广播副作用。不要通过命令参数、URL 或日志传递原始密钥。

### 0.6 回滚

`05-deploy-console.sh` 把制品放入版本目录，并维护 `current` 与 `previous` 原子链接。当前发布过程失败会
自动恢复；已完成发布需要人工回滚时，先停止网关投递，再切换三个链接：

```bash
systemctl stop jt-platform

switch_to_previous() {
  current="$1"
  previous="$2"
  target="$(readlink "$previous")"
  test -n "$target"
  temporary="${current}.rollback.$$"
  ln -s -- "$target" "$temporary"
  mv -Tf -- "$temporary" "$current"
}

switch_to_previous /opt/jt-console/current /opt/jt-console/previous
switch_to_previous /opt/jt-platform/current /opt/jt-platform/previous
switch_to_previous /var/www/jt-console/current /var/www/jt-console/previous
systemctl restart jt-console
/usr/local/libexec/jt-wait-console-ready
systemctl restart jt-platform
curl --fail --silent http://127.0.0.1:7810/health
/usr/local/sbin/jt-verify-deployment
```

若版本包含不兼容的数据结构变更，必须在启动旧控制台前恢复与旧版本配套的 SQLite 备份。不要通过重新
开放无认证 API、移除投递密钥或恢复模糊设备键来完成回滚。canonical ID 语义变更时，应先停止投递，
避免新旧身份数据混写。

### 0.7 常见故障

- 控制台未就绪：查看 `journalctl -u jt-console`，确认部署模式下管理员 BCrypt 哈希和投递密钥非空，
  `/var/lib/jt-console/data` 可写，`/actuator/health` 同时返回 2xx 与 `"status":"UP"`。
- API 返回 401：浏览器访问 token 可能已过期或被撤销。前端会单飞刷新；刷新也失败时必须重新登录，
  不能填写占位 token。
- 投递持续 401：比较两个受限环境文件的密钥是否来自同一次轮换，确认网关请求头为
  `X-JT-Ingest-Key`，再按控制台、网关顺序重启。不要在排障输出中打印密钥。
- 实时连接失败：确认页面 Origin 与 `JT_CONSOLE_ALLOWED_ORIGINS` 精确一致，Nginx 转发
  `Sec-WebSocket-Protocol`，客户端使用 `jt-console.v1` 与 `bearer.<access-token>` 子协议，而不是 URL
  query。
- 车辆存在但轨迹/状态错位：以协议解码后的 `mobileNo/SIM` 作为 canonical `deviceId`；T0100 终端 ID
  只用于信令路由。`00123` 与 `123` 不得做前导零合并。
- T9101 已下发但无视频：检查终端收到的真实媒体地址和 `7811-7814` 端口、RTP SIM 是否等于
  canonical `mobileNo`，以及配置的逻辑通道是否与开流请求一致。
- 模拟器提示找不到 FFmpeg：在界面选择 `ffmpeg.exe` 的绝对路径或包含它的目录，并重新检测
  DirectShow、`libx264`、`pcm_alaw` 与摄像头/麦克风。网关仅在离线 MP4 导出时依赖 FFmpeg，二者不要
  混淆。
- 部署验收失败：直接运行 `deploy/07-verify-deployment.sh` 获取逐项结果。它检查权限、账户、systemd
  顺序、数据库/网关/媒体健康、TLS、HTTP 跳转、Nginx 私有路径和防火墙端口，不会打印凭据。

## 1. 部署形态

| 形态 | 适用场景 | 进程 |
|---|---|---|
| `standalone` | 本地联调、小规模单机 | 一个进程同时运行 signal、media、api |
| `cluster` | 独立扩缩媒体节点、隔离资源 | 同一 JAR 分别以 `signal`、`media`、`api` 角色启动 |

分进程入口不是三个独立制品。`--jt.runtime.role=signal|media|api` 会选择角色并自动附加 `cluster` profile；
不要只设置 `spring.profiles.active=cluster` 后以默认 `all` 角色启动。

当前分进程实现的边界必须在部署前确认：

- API 角色集中保存媒体实例、流和一次性 token 的状态，媒体角色通过内部 HTTP 访问它。
- 注册表后端可选 `memory`（默认）与 `redis`。`memory` 下 API 角色必须保持单实例、重启丢失状态；
  启用 `redis` 后状态落 Redis，API 可重启不丢状态、可多实例共享（见 13.4）。
- API 角色当前只配置一个 `jt.signal.command-base-url`，设备会话路由也保存在 signal 进程本地，因此当前
  版本只支持一个活动 signal 角色。不要把多个 signal 实例放到负载均衡后宣称无状态高可用。
- media 角色可以按实例号 `1..9` 扩展。每个实例有独立端口段、实例 ID、可达地址和本地存储。

## 2. 构建与配置加载

要求 JDK 25 和 Maven 3.9 或更高版本：

```bash
cd jt-platform
mvn -B -ntp clean package
export JT_JAR="$PWD/jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar"
```

生产配置建议放在 JAR 之外：

```bash
java -jar "$JT_JAR" \
  --spring.config.additional-location=optional:file:/etc/jt-platform/
```

Spring Boot 环境变量映射同样可用，例如 `JT_INSTANCE_NUMBER=2` 对应 `jt.instance.number=2`。
密码、token 和自定义请求头应由密钥管理系统注入，不要写入镜像或版本库。

## 3. 网络拓扑

```text
JT/T 808 终端
    | 7100/TCP, 7101/UDP
    v
四层入口 --------------------> signal-N: 71N1/TCP, 71N2/UDP
                                      ^
                                      | 71N3/TCP 内部指令
业务系统/播放器 ---> api:8100 -------+
                         ^
                         | 8100/TCP 内部共享状态
                         |
                     media-N

终端 -----------> media-N: 78N1..78N4/TCP
播放器 ---------> media-N: 78N5/TCP
运维探针 -------> media-N: 78N0/TCP
```

调度结果包含具体 media 节点的地址与端口。设备推流和客户端 WebSocket 都必须直连这个节点，不能再经
普通负载均衡随机转发，否则连接可能落到不拥有该流的节点。

## 4. 端口规范

`N` 是实例号，范围为 `1..9`。例如 media 实例 1 使用 `7810..7816`，实例 2 使用
`7820..7826`。

### 4.1 媒体实例

| 端口 | 协议 | 用途 | 暴露范围 |
|---|---|---|---|
| `78N0` | TCP/HTTP | 管理、健康和容量指标 | 仅运维内网 |
| `78N1` | TCP | 主码流接入 | 终端直连真实节点 |
| `78N2` | TCP | 子码流接入 | 终端直连真实节点 |
| `78N3` | TCP | 终端历史回放流接入 | 终端直连真实节点 |
| `78N4` | TCP | 终端对讲媒体接入 | 终端直连真实节点 |
| `78N5` | TCP/WebSocket | 裸流播放、回放与对讲客户端连接，默认路径 `/ws` | 播放器直连真实节点 |
| `78N6` | TCP | HTTP-FLV 扩展位 | 当前未实现，不监听也不必放行 |

`78N0` 提供 `/health`、`/actuator/health`、`/metrics/capacity` 和 `/metrics/recording`。

### 4.2 信令实例与统一入口

| 端口 | 协议 | 用途 | 暴露范围 |
|---|---|---|---|
| `71N0` | TCP/HTTP | signal Actuator 管理端口 | 仅运维内网 |
| `71N1` | TCP | JT/T 808 TCP 后端 | 仅四层入口或终端网络 |
| `71N2` | UDP | JT/T 808 UDP 后端 | 仅四层入口或终端网络 |
| `71N3` | TCP/HTTP | 内部信令命令与 `/device/**` 下行控制接口 | 仅 API 与受信业务后端 |
| `7100` | TCP | JT/T 808 统一 TCP 入口 | 终端网络 |
| `7101` | UDP | JT/T 808 统一 UDP 入口 | 终端网络 |

`standalone` 直接监听 `7100/7101`。`cluster` 下 signal-N 默认监听 `71N1/71N2`，外部四层入口把
`7100/7101` 转发到这两个后端；也可以在只有一个 signal 的环境显式覆盖监听端口，但必须保持对外端口
不变。

### 4.3 API 与管理

| 端口 | 协议 | 用途 | 暴露范围 |
|---|---|---|---|
| `8100` | TCP/HTTP | REST API；cluster 下同时承载内部共享状态接口 | 业务网络；内部路径须隔离 |
| `8109` | TCP/HTTP | API/standalone Actuator | 仅运维内网 |

当前 `/internal/cluster-state/**` 和 signal 的 `/internal/streams/**` 没有应用层认证。生产环境必须通过
网络 ACL、Kubernetes NetworkPolicy 或反向代理限制来源，并在公网代理上拒绝 `/internal/**`。

### 4.4 防火墙流向

| 来源 | 目标 | 必须允许 |
|---|---|---|
| JT/T 808 终端 | 统一信令入口 | `7100/TCP`、`7101/UDP` |
| 四层信令入口 | signal-N | `71N1/TCP`、`71N2/UDP` |
| API 角色、受信业务后端 | signal-N | `71N3/TCP` |
| media-N | API 角色 | `8100/TCP` |
| 终端 | 被调度的 media-N | `78N1..78N4/TCP` |
| 播放器 | 被调度的 media-N | `78N5/TCP` |
| 运维探针 | 各角色 | `71N0/TCP`、`78N0/TCP`、`8109/TCP` |
| signal 角色 | 设备档案 API、投递 API | 目标 HTTPS 端口，按启用项放行 |
| signal 角色 | RocketMQ NameServer 与 Broker | NameServer 及 Broker 实际公布端口，按启用项放行 |
| API 角色 | JWKS | 目标 HTTPS 端口，启用 JWT 时放行 |

`78N1..78N5` 的防火墙规则必须指向真实节点 IP。不要只放行负载均衡地址。

## 5. 单进程启动

默认 profile 是 `standalone`，以下两条命令等价：

```bash
java -jar "$JT_JAR"
java -jar "$JT_JAR" --spring.profiles.active=standalone
```

默认监听：

- JT/T 808：`7100/TCP`、`7101/UDP`
- media-1：`7810..7815/TCP`
- REST API：`8100/TCP`
- Actuator：`8109/TCP`

健康检查：

```bash
curl --fail http://127.0.0.1:8109/actuator/health
curl --fail http://127.0.0.1:7810/health
```

在 NAT、多网卡或容器环境中，启动前按第 7 节设置媒体可达地址。自动探测得到容器 IP 或管理网 IP
时，设备虽然收到开流指令也无法连接。

## 6. 分进程启动

### 6.1 角色与依赖

| 角色 | 关键入站 | 关键出站 |
|---|---|---|
| `api` | `8100`、`8109` | signal 的 `71N3`、JWKS |
| `signal` | `71N0..71N3` 中实际监听的四个端口 | 设备档案 API、消息投递目标 |
| `media` | `78N0..78N5` | API 的 `8100` 内部共享状态 |

先启动 API，再启动 signal，最后启动 media。media 在启动阶段会立即向 API 注册，API 不可达会导致
media 启动失败。signal 与 API 的先后不影响进程创建，但 API 发起开流前 signal 必须健康。

### 6.2 实例 1 命令

以下命令分别在三个终端或三台主机运行。域名必须替换为部署网络中的真实地址。

API：

```bash
java -jar "$JT_JAR" \
  --jt.runtime.role=api \
  --jt.signal.command-base-url=http://signal-1.internal:7113
```

signal-1：

```bash
java -jar "$JT_JAR" \
  --jt.runtime.role=signal \
  --jt.instance.number=1 \
  --jt.signal.instance-id=signal-1 \
  --server.port=7113 \
  --management.server.port=7110
```

media-1：

```bash
export JT_REACHABLE_ADDRESS=203.0.113.10
java -jar "$JT_JAR" \
  --jt.runtime.role=media \
  --jt.instance.number=1 \
  --jt.media.instance-id=media-1 \
  --jt.cluster.api-base-url=http://api.internal:8100 \
  --jt.media.reachable-address.source=env
```

健康检查：

```bash
curl --fail http://api.internal:8109/actuator/health
curl --fail http://signal-1.internal:7110/actuator/health
curl --fail http://203.0.113.10:7810/health
```

### 6.3 其他实例号

signal-N 的 Netty 接入端口会根据 `jt.instance.number` 自动计算，但 Spring 管理端口与命令端口不能从
实例号自动插值。启动 signal-N 时必须同时设置：

```text
--management.server.port=71N0
--server.port=71N3
```

media-N 的 `78N0..78N6` 全部由实例号计算。每个 media 还必须使用唯一的
`jt.media.instance-id`、可达地址和存储目录。实例号超出 `1..9` 会启动失败。

## 7. 媒体可达地址

该地址会注册到调度层，并写入发给终端的 9101/9201 目标及开流响应。它必须是设备和播放器真正能
连接的主机名或 IP，不能是容器内部地址、不可路由的 Pod IP 或管理网地址。

自动探测适用于简单裸机：

```yaml
jt:
  media:
    reachable-address:
      source: auto
```

静态配置：

```yaml
jt:
  media:
    reachable-address:
      source: static
      value: media-1.example.com
```

环境变量注入，推荐用于容器和 Kubernetes：

```yaml
jt:
  media:
    reachable-address:
      source: env
      env-name: JT_REACHABLE_ADDRESS
```

```bash
export JT_REACHABLE_ADDRESS=203.0.113.10
```

只设置 `JT_REACHABLE_ADDRESS` 而仍使用默认 `source: auto` 不会覆盖探测结果，必须同时把来源设置为
`env`，或者使用 `static` 与 `value`。

## 8. 存储规划

所有默认相对路径都相对于进程工作目录。生产环境建议改成绝对路径，并让不同 media 实例使用独立目录。

| 默认路径 | 内容 | 建议 |
|---|---|---|
| `recordings/` | 裸流录像分片、索引与提交标记 | media 独占持久卷，高 IOPS，监控容量 |
| `recording-exports/` | 离线 MP4 导出 | media 持久卷或有生命周期的导出卷 |
| `data/signal/multimedia/` | JT/T 808 终端多媒体 | signal 持久卷 |
| `data/signal/alarm-attachments/` | 主动安全报警附件 | signal 持久卷 |
| `data/delivery-overflow/` | 关键消息队列溢出文件 | signal 持久卷，不可使用临时文件系统 |

推荐配置示例：

```yaml
jt:
  media:
    recording:
      root: /var/lib/jt-platform/media-1/recordings
      export-root: /var/lib/jt-platform/media-1/recording-exports
      segment-duration: 30s
      retention-days: 7
      max-bytes: 107374182400
      retention-check-interval: 5m
      ffmpeg-command: /usr/local/bin/ffmpeg
  signal:
    storage:
      multimedia-path: /var/lib/jt-platform/signal-1/multimedia
      alarm-attachment-path: /var/lib/jt-platform/signal-1/alarm-attachments
  delivery:
    api:
      overflow-directory: /var/lib/jt-platform/signal-1/delivery-overflow/api
    rocket-mq:
      overflow-directory: /var/lib/jt-platform/signal-1/delivery-overflow/rocket-mq
```

默认录像保留 7 天且最大占用 100 GiB，任一阈值触发都会从最旧分片开始清理。某个阈值设为 `0` 会
禁用该阈值；两者都为 `0` 时不执行自动清理。容量估算可使用：

```text
每日字节数 = 平均总码率(bit/s) / 8 * 86400 * 同时录像路数
规划容量 = 每日字节数 * 保留天数 * 1.2 以上余量
```

`ffmpeg` 只在离线 MP4 导出任务执行时调用，不参与实时接入、录制、检索或裸流回放。没有导出需求
可以不安装。需要导出时建议将 `ffmpeg` 与 `ffprobe` 放在同一版本目录，并限制导出并发以保护磁盘与 CPU。

## 9. 裸机与 systemd

### 9.1 目录和权限

建议使用不可登录的专用账户：

```bash
sudo install -d -o jt-platform -g jt-platform -m 0750 /opt/jt-platform
sudo install -d -o jt-platform -g jt-platform -m 0750 /etc/jt-platform
sudo install -d -o jt-platform -g jt-platform -m 0750 /var/lib/jt-platform
sudo install -o root -g root -m 0644 \
  jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar \
  /opt/jt-platform/app.jar
```

录像和溢出目录需要运行账户读写；JAR 与配置应避免被运行账户修改。确认 `ulimit -n` 足以覆盖终端长
连接、媒体连接和文件句柄，生产环境通常从 `65536` 起评估。

### 9.2 systemd 模板

`/etc/systemd/system/jt-platform@.service`：

```ini
[Unit]
Description=JT Platform (%i)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=jt-platform
Group=jt-platform
WorkingDirectory=/var/lib/jt-platform/%i
EnvironmentFile=-/etc/jt-platform/%i.env
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
ExecStart=/usr/bin/java -jar /opt/jt-platform/app.jar --spring.config.additional-location=optional:file:/etc/jt-platform/%i/
Restart=on-failure
RestartSec=5s
TimeoutStopSec=300s
KillSignal=SIGTERM
SuccessExitStatus=143
LimitNOFILE=65536
UMask=0027

[Install]
WantedBy=multi-user.target
```

创建对应工作目录后，单进程的 `/etc/jt-platform/standalone.env` 至少包含：

```dotenv
JT_RUNTIME_ROLE=all
```

分进程环境文件示例：

```dotenv
# api.env
JT_RUNTIME_ROLE=api
JT_SIGNAL_COMMAND_BASE_URL=http://signal-1.internal:7113
```

```dotenv
# signal-1.env
JT_RUNTIME_ROLE=signal
JT_INSTANCE_NUMBER=1
JT_SIGNAL_INSTANCE_ID=signal-1
SERVER_PORT=7113
MANAGEMENT_SERVER_PORT=7110
```

```dotenv
# media-1.env
JT_RUNTIME_ROLE=media
JT_INSTANCE_NUMBER=1
JT_MEDIA_INSTANCE_ID=media-1
JT_CLUSTER_API_BASE_URL=http://api.internal:8100
JT_MEDIA_REACHABLE_ADDRESS_SOURCE=env
JT_REACHABLE_ADDRESS=203.0.113.10
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now jt-platform@api
sudo systemctl enable --now jt-platform@signal-1
sudo systemctl enable --now jt-platform@media-1
```

启动顺序仍为 API、signal、media。单进程只启用 `jt-platform@standalone`。

## 10. Docker

推荐构建不可变镜像。最小 Dockerfile 可以使用：

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /var/lib/jt-platform
COPY jt-boot-all/target/jt-boot-all-0.1.0-SNAPSHOT.jar /opt/jt-platform/app.jar
ENTRYPOINT ["java", "-jar", "/opt/jt-platform/app.jar"]
```

将上述内容放在 `jt-platform/Dockerfile`，然后在 `jt-platform` 目录构建：

```bash
docker build -t jt-platform:0.1.0 .
```

### 10.1 Host network

Linux 上优先使用 host network，端口与上报地址最直观：

```bash
docker run -d --name jt-platform \
  --network host \
  --restart unless-stopped \
  -e JT_MEDIA_REACHABLE_ADDRESS_SOURCE=env \
  -e JT_REACHABLE_ADDRESS=203.0.113.10 \
  -v /srv/jt-platform:/var/lib/jt-platform \
  jt-platform:0.1.0
```

Docker Desktop 的 host network 行为与原生 Linux 不同，应使用显式端口发布并实测 UDP。

### 10.2 Bridge network

bridge 模式必须一对一发布所有实际监听的 TCP/UDP 端口，尤其不能遗漏 UDP 和媒体直连端口：

```bash
docker run -d --name jt-platform \
  --restart unless-stopped \
  -p 7100:7100/tcp \
  -p 7101:7101/udp \
  -p 7810-7815:7810-7815/tcp \
  -p 8100:8100/tcp \
  -p 8109:8109/tcp \
  -e JT_MEDIA_REACHABLE_ADDRESS_SOURCE=env \
  -e JT_REACHABLE_ADDRESS=203.0.113.10 \
  -v /srv/jt-platform:/var/lib/jt-platform \
  jt-platform:0.1.0
```

不要发布当前未监听的 `7816`。分进程容器使用相同角色参数；media-N 必须保持 `78N0..78N5` 宿主端口
与容器端口相同，并把宿主真实可达地址写入 `JT_REACHABLE_ADDRESS`。容器间内部调用使用稳定容器 DNS，
不能使用容器自身的 `127.0.0.1`。

## 11. Kubernetes

### 11.1 工作负载选择

| 角色 | 建议控制器 | 副本约束 | 服务 |
|---|---|---|---|
| API | Deployment | 当前固定 `1` | ClusterIP，公网代理只转发业务路径 |
| signal-N | StatefulSet | 当前只部署一个活动 signal，固定 `1` 副本 | Headless 用于内部定向；外部四层入口承载 7100/7101 |
| media-N | StatefulSet | 每个实例号一个工作负载，固定 `1` 副本 | Headless 保持身份；媒体端口由节点直接暴露 |

StatefulSet 与 Headless Service 用于稳定 Pod 身份和内部定向寻址，不代表媒体流可以经过 Service 随机
转发。当前端口编号依赖实例号，建议用 Helm/Kustomize 为每个 `N` 渲染一组固定配置，不要直接增加同一
media-N StatefulSet 的副本数，否则实例 ID 和端口会冲突。

### 11.2 media-N 模板要点

以下片段展示 media-1。增加 media-2 时必须同步替换实例号、实例 ID、端口段和 PVC：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: jt-media-1
spec:
  clusterIP: None
  selector:
    app: jt-media
    jt-instance: media-1
  ports:
    - {name: management, port: 7810, targetPort: 7810}
    - {name: main, port: 7811, targetPort: 7811}
    - {name: sub, port: 7812, targetPort: 7812}
    - {name: playback, port: 7813, targetPort: 7813}
    - {name: talkback, port: 7814, targetPort: 7814}
    - {name: websocket, port: 7815, targetPort: 7815}
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: jt-media-1
spec:
  serviceName: jt-media-1
  replicas: 1
  selector:
    matchLabels:
      app: jt-media
      jt-instance: media-1
  template:
    metadata:
      labels:
        app: jt-media
        jt-instance: media-1
    spec:
      hostNetwork: true
      dnsPolicy: ClusterFirstWithHostNet
      terminationGracePeriodSeconds: 300
      containers:
        - name: jt-platform
          image: jt-platform:0.1.0
          args:
            - --jt.runtime.role=media
            - --jt.instance.number=1
            - --jt.media.instance-id=media-1
            - --jt.cluster.api-base-url=http://jt-api:8100
            - --jt.media.reachable-address.source=env
            - --jt.media.recording.root=/var/lib/jt-platform/recordings
            - --jt.media.recording.export-root=/var/lib/jt-platform/recording-exports
          env:
            - name: JT_REACHABLE_ADDRESS
              valueFrom:
                fieldRef:
                  fieldPath: status.hostIP
          readinessProbe:
            httpGet: {path: /health, port: 7810}
          livenessProbe:
            httpGet: {path: /health, port: 7810}
          volumeMounts:
            - name: data
              mountPath: /var/lib/jt-platform
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: [ReadWriteOnce]
        resources:
          requests:
            storage: 120Gi
```

`status.hostIP` 只有在设备网络能路由到节点 IP 时才正确。若节点有内外网地址、NAT 或云厂商弹性 IP，
应通过每节点配置或部署系统注入真正可达地址，不能盲目使用 `status.hostIP`。

如果不能使用 `hostNetwork`，可以为每个固定实例逐端口配置相同端口号的 `hostPort` 或外部一对一 NAT。
普通 ClusterIP/LoadBalancer Service 或 Ingress 不适合 `78N1..78N5`：它们可能把同一路连接转到错误节点，
Ingress 也不能覆盖所有 JT/T 1078 TCP 通路。Headless Service 的 Pod DNS 仅用于内部定向管理。

### 11.3 signal 与 API

signal-1 StatefulSet 使用 `7110..7113`，API 通过稳定 DNS 设置：

```text
JT_SIGNAL_COMMAND_BASE_URL=http://jt-signal-1-0.jt-signal-1:7113
```

对外使用四层 Service 或外部负载均衡，把 `7100/TCP` 转发到 `7111/TCP`、把 `7101/UDP` 转发到
`7112/UDP`。部分云负载均衡不支持在一个 Service 中混合 TCP 与 UDP，需要拆成两个 Service。

API 使用单副本 Deployment 和 ClusterIP `jt-api:8100`。公网 Ingress 或网关只转发业务 API，明确拒绝
`/internal/**`；`8109` 只提供给探针与运维网络。NetworkPolicy 至少应允许 media -> API:8100、
API 或指定业务后端 -> signal:7113，并拒绝其他命名空间访问内部管理端口。

## 12. 优雅摘除与滚动更新

### 12.1 media 摘除

cluster 模式下，先把 media 标为 draining，停止分配新流：

```bash
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl --fail -X POST http://api.internal:8100/internal/cluster-state/media/draining \
  -H 'Content-Type: application/json' \
  --data "{\"instanceId\":\"media-1\",\"heartbeatAt\":\"$NOW\"}"
```

随后轮询 `http://media-1:7810/health` 中的 `currentStreams`，等其降到 `0`，再发送 SIGTERM。正常关闭
也会自动把实例标为 draining，但关闭动作本身不会等待存量流排空，因此编排系统必须在终止前完成上述
步骤。超过业务允许的最长排空时间后，再按运维策略强制结束残余流。

Kubernetes 的 `preStop` 可以调用内部摘除接口并等待 `currentStreams=0`，但镜像中需要具备对应 HTTP
客户端，并确保 `terminationGracePeriodSeconds` 大于最大排空时间。systemd 的 `TimeoutStopSec` 和
`docker stop -t` 也应使用同一上限。

### 12.2 signal 摘除

先从 `7100/7101` 四层入口移除 signal 后端，停止新终端连接；等待既有连接自然迁移或到达维护窗口，
再停止进程。当前版本只有一个活动 signal 命令目标，signal 更新会触发终端重连，不能通过简单增加副本
实现无损滚动。

### 12.3 滚动节奏

1. 一次只处理一个 media 实例：标记 draining、等待 `currentStreams=0`、更新并等待 `/health` Ready。
2. 确认替代 media 已完成心跳注册，再处理下一个 media；不要同时停止全部 media。
3. signal 从四层入口摘除后单独更新；不要与全部 media 同时重启。
4. API 最后更新。由于注册表在 API 内存中，更新 API 前必须排空媒体流；API 恢复后等待所有 media
   心跳重新注册，再恢复开流入口。
5. 每一步都检查 API Actuator、signal Actuator、media `/health` 和端口连通性，失败即停止继续滚动。

## 13. 生产能力配置

### 13.1 设备鉴权

生产环境推荐 `remote-api` 并显式选择故障策略：

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

该配置放在 standalone 或 signal 角色。远端接口只返回设备事实，不返回权限判定字段。

### 13.2 开流 JWT

```yaml
jt:
  auth:
    stream:
      mode: jwt
      jwks-uri: https://auth.example.com/.well-known/jwks.json
      jwks-cache-ttl: 10m
      token-ttl: 60s
```

该配置放在 standalone，或在 cluster 中放到 API 与 media 的公共配置。完整签名约定见
[开流 JWT 对接契约](jwt-auth-contract.md)。

### 13.3 消息投递

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

配置放在 standalone 或 signal 角色。只启用实际部署的通道，确保溢出目录持久化并监控积压。

### 13.4 共享注册表

默认零依赖：流注册表、媒体实例注册表与一次性 token 都存在进程内存里。

```yaml
jt:
  registry:
    type: memory
```

standalone 使用进程内存；cluster 的 API 角色持有集中内存状态，media 通过
`jt.cluster.api-base-url` 访问。`memory` 下 API 角色必须保持单实例，API 重启会丢失全部运行时状态。

需要 API 高可用（重启不丢流状态、多 API 实例共享）时，改用 Redis 后端：

```yaml
jt:
  registry:
    type: redis
    redis:
      host: 127.0.0.1
      port: 6379
      password: ""
      database: 0
      key-prefix: "jt:registry:"
```

Redis 只在 API 角色（及 standalone）被访问，媒体角色仍经内部 HTTP 访问 API，不直连 Redis。
媒体实例心跳以带 TTL 的键落 Redis，过期自动摘除，不依赖 API 进程内的定时清理。`type=redis` 但
Redis 不可达时会在启动阶段明确失败，绝不静默降级回 memory。

回滚：把 `jt.registry.type` 改回 `memory` 即可，代价是退回 API 单实例 + 重启丢状态。

## 14. 上线检查清单

- JDK 为 25，JAR、配置和运行目录权限分离。
- `JT_REACHABLE_ADDRESS` 从终端网与播放器网实测可达，`78N1..78N5` 没有经过随机负载均衡。
- TCP 和 UDP 防火墙规则分别验证，特别是 `7101/UDP`。
- 管理端口与 `/internal/**` 仅内网可达，公网入口启用 TLS、鉴权和路径拦截。
- 设备鉴权不再使用 `allow-all`，开流鉴权不再使用 `disabled`。
- 录像、附件、导出和投递溢出目录均在持久卷上，容量与 inode 有监控和告警。
- 只在需要 MP4 导出时安装并验证 `ffmpeg`，执行账户无不必要权限。
- 已演练 media 摘除、终端重连、API 状态丢失后的恢复和备份恢复流程。
- 没有同时滚动全部 signal 与 media，API 内存状态限制已纳入维护窗口。
