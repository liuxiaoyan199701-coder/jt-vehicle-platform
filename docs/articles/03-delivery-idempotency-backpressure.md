# 协议网关往业务系统投递消息：幂等、顺序与背压该怎么处理

> 本文是 [jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
> 的设计记录，代码位置均可在仓库中对照。

车辆监控平台通常分两层：**协议网关**负责 JT/T 808/1078 的接入与解析，
**业务系统**负责档案、轨迹、告警这些业务逻辑。两者之间要把协议消息传过去。

看起来就是一个 HTTP POST 的事。但真跑起来会遇到一连串问题：

- 网络抖动重发，业务库里出现重复轨迹点
- 业务系统响应慢了几秒，网关内存暴涨
- 同一台车的「进围栏」和「出围栏」到达顺序颠倒，告警状态错乱
- 业务系统重启，这期间的报警消息全丢了

这些不是靠"加个重试"能解决的，需要在投递层做明确的设计取舍。

---

## 一、幂等：重复投递是必然的，不是异常

只要有重试，就一定会有重复投递。原因很朴素：**网关无法区分「请求没送到」和
「送到了但响应丢了」**。后者重试就会产生重复。

所以接收端必须幂等。信封里带了 `eventId`（UUID），同时作为 `Idempotency-Key` 请求头：

```json
{
  "eventId": "3f1c0a2e-9d44-4a10-91b7-7f0b2c8d5e61",
  "deviceId": "13800138000",
  "messageId": 512,
  "type": "location",
  "payload": { "latitude": 39.912345, "longitude": 116.397128, "speedKph": 65.4 }
}
```

接收端按 `eventId` 判重即可。但这里有个 SQLite 的坑值得说。

### 坑：捕获 DuplicateKeyException 抓不到

第一版是这么写的：

```java
try {
    jdbc.sql("INSERT INTO processed_event (event_id, created_at) VALUES (?, ?)")
        .param(eventId).param(now).update();
    return true;
} catch (DuplicateKeyException duplicate) {   // ← 抓不到
    return false;
}
```

实测重复投递会返回 **HTTP 500**。日志里是这样：

```
org.sqlite.SQLiteException: [SQLITE_CONSTRAINT_PRIMARYKEY]
  A PRIMARY KEY constraint failed (UNIQUE constraint failed: processed_event.event_id)
```

原因是 **SQLite 不提供标准 SQLState**，Spring 的异常翻译器无法把它归类成
`DuplicateKeyException`，最终落到了未分类的 `UncategorizedSQLException`。

这个 bug 的危害不只是返回错状态码：**非 2xx 会触发网关重试**，而重试必然再次冲突，
形成死循环。

### 解法：让冲突不产生异常

```java
public boolean markProcessed(String eventId) {
    int rows = jdbc.sql(
            "INSERT OR IGNORE INTO processed_event (event_id, created_at) VALUES (?, ?)")
        .param(eventId).param(Instant.now().toString())
        .update();
    return rows > 0;    // 冲突时影响行数为 0
}
```

用 SQLite 原生的 `INSERT OR IGNORE`，判重退化成一次普通的行数判断，不依赖任何
异常类型。MySQL/PostgreSQL 可以用 `ON CONFLICT DO NOTHING` 达到同样效果。

> 通用原则：**判重不要依赖异常分类**。不同数据库、不同驱动、不同 Spring 版本对
> 约束冲突的翻译都可能不一样。

去重表要定期清理，保留窗口覆盖网关的重投递窗口即可（默认 24 小时）。

---

## 二、顺序：只保证「同一台车」的顺序

全局有序既做不到也没必要。有意义的是**同一台设备的消息顺序**——
「进围栏」必须排在「出围栏」前面，否则围栏状态机会错乱。

做法是按 deviceId 哈希分 lane（默认 4 条）：

```java
private int stripeFor(String deviceId) {
    return Math.floorMod(deviceId.hashCode(), lanes.size());
}
```

同一台设备永远落在同一条 lane，lane 内严格串行。不同设备之间可以并行。

这个设计的代价要清楚：**同一条 lane 上是队头阻塞的**。某台设备的一条关键消息
一直失败重试，会挡住同 lane 上其他设备的消息。lane 数量就是在
「并发度」和「资源占用」之间取平衡。

---

## 三、背压：队列满了，丢什么？

内存队列一定有上限（默认 1024 条）。满了之后的策略，是整个设计里最需要想清楚的部分。

### 先分类：不是所有消息都一样重要

```java
LOCATION("location", BEST_EFFORT),          // 位置：丢了还会有下一个
HEARTBEAT("heartbeat", BEST_EFFORT),        // 心跳：丢了不影响判断在线
ALARM("alarm", AT_LEAST_ONCE),              // 报警：丢了就是事故
REGISTER("register", AT_LEAST_ONCE),
MULTIMEDIA("multimedia", AT_LEAST_ONCE),
// ...其余均为 AT_LEAST_ONCE
```

**位置和心跳是尽力而为，其余必达。** 这个划分基于一个事实：位置每隔几秒就有新的，
丢一个点对轨迹的影响有限；而一条报警丢了就再也不会重来。

### 为关键消息预留配额

```java
this.bestEffortLimit = options.queueCapacity() - options.criticalReserve();
```

默认 1024 - 64 = 960。也就是说位置消息最多占 960 个槽位，**剩下 64 个永远给关键消息留着**。
这样即使位置洪峰打满队列，报警仍然进得来。

### 队列满时的三级处理

```java
if (queued >= options.queueCapacity()) {
    evicted = newestEvictableBestEffort();          // ① 驱逐一条 best-effort
    if (evicted == null) {
        disposition = retainOverflow(envelope, stripe)   // ② 落盘
                ? ACCEPTED : RETRY_REQUIRED;             // ③ 落盘也失败才拒绝
    } else {
        removePending(evicted);
    }
}
```

1. **先驱逐 best-effort 给关键消息腾位置**
2. 驱逐不了（全是关键消息）就**写入溢出文件**，进程重启后继续投递
3. 连磁盘都写不了，才返回 `RETRY_REQUIRED` 让调用方知道

### 一个反直觉的选择：驱逐最新的，不是最旧的

```java
private Pending newestEvictableBestEffort() {
    Pending newest = null;
    for (Lane lane : lanes) {
        Iterator<Pending> iterator = lane.pending.descendingIterator();   // 从队尾找
        while (iterator.hasNext()) {
            Pending candidate = iterator.next();
            if (!candidate.inFlight && !candidate.envelope.type().isCritical()) {
                if (newest == null || candidate.sequence > newest.sequence) {
                    newest = candidate;
                }
                break;
            }
        }
    }
    return newest;
}
```

缓存淘汰的直觉通常是"丢最旧的"，这里恰恰相反，原因有三：

- **最旧的已经排了很久**，它代表的那段轨迹一旦丢失就断了；而最新的位置后面马上还有更新的
- **从队尾移除不破坏已排队消息的顺序**，从队头删则会在轨迹中间开洞
- **`inFlight` 的不能删**，它已经在网络上了，删掉会造成状态不一致

### 顺序优先于新鲜度

还有一条容易忽略的规则：

```java
} else if (!envelope.type().isCritical() && lanes.get(stripe).spooled > 0) {
    disposition = DROPPED;
    dropReason = "an older critical message is retained for this delivery lane";
}
```

如果某条 lane 有关键消息已经落盘（说明这条 lane 正在积压），那么该 lane 上新来的
best-effort 消息**直接丢弃**。因为让它插到落盘的关键消息前面会破坏顺序，
而为它也落盘则会放大积压。

---

## 四、超时：10 秒的连锁反应

单次投递超时默认 10 秒，配合熔断（连续 5 次失败后打开 30 秒）和指数退避
（200ms → 30s）。

对接收端而言，这意味着一件很重要的事：

> **你的接口慢，代价不是"投递慢一点"，而是那台设备的位置点被直接丢弃。**

推导链条是这样的：接口响应慢 → 该 lane 队头阻塞 → 队列积压 → 位置消息被驱逐或丢弃。
而位置是 best-effort，丢了不会重来。

所以接收端的写法应该是：

```java
@PostMapping("/jt-events")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void receive(@RequestBody MessageEnvelope envelope) {
    if (eventId != null && !events.markProcessed(eventId)) {
        return;                       // 重复投递，直接返回
    }
    try {
        locations.handle(envelope);   // 只做必要的落库，不做任何慢操作
    } catch (IllegalArgumentException | ClassCastException malformed) {
        // 脏数据重试也不会变好，吞掉避免重试风暴
        LOGGER.warn("Discarding malformed envelope {}", eventId);
    }
}
```

三条要点：

1. **立即返回 204**，不要在请求线程里做耗时处理（调外部接口、发通知、算统计）
2. **区分两类错误**：报文本身有问题（解析不出来）返回 2xx 直接丢弃，
   重试多少次都是同样结果；**存储层异常则要让它变成 5xx**，网关重试才有意义
3. **不要把成功响应包装成 200 + 业务错误码**。网关只看 HTTP 状态码，
   返回 200 就认为送达了，消息会被静默丢弃

第 3 点在实践中很容易踩到——如果项目里有全局异常处理器把所有异常都包成
`200 + {code: "5000"}`，投递链路就废了。我们的做法是让全局处理器只作用于
业务 API 的包名，投递入口不在其中：

```java
@RestControllerAdvice(basePackages = "io.github.jtconsole.web")
public class GlobalExceptionHandler { ... }
// IngestController 在 io.github.jtconsole.ingest 包下，有意排除在外
```

---

## 小结

| 问题 | 做法 |
|---|---|
| 重复投递 | `eventId` 幂等；判重用 `INSERT OR IGNORE`，别依赖异常分类 |
| 消息顺序 | 按 deviceId 哈希分 lane，lane 内串行；接受队头阻塞的代价 |
| 队列溢出 | 消息分级 + 为关键消息预留配额；驱逐最新的 best-effort，不是最旧的 |
| 积压加剧 | 该 lane 已有关键消息落盘时，新来的 best-effort 直接丢弃以保序 |
| 慢接收端 | 立即返回 204；脏数据返回 2xx，存储异常返回 5xx |

核心思路其实只有一句：**在设计阶段就明确「什么可以丢」**。

不做这个区分，结局无非两种——要么为了不丢任何消息而让队列无限增长直到 OOM，
要么在压力下随机丢弃，把报警和位置一起丢掉。

---

完整实现见
[jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
（Gitee：[lxygit0731/jt-vehicle-platform](https://gitee.com/lxygit0731/jt-vehicle-platform)）
的 `jt-platform/jt-delivery` 模块与 `jt-console` 的接收端。
仓库里还有一个 `verify-ingest.sh` 可以直接验证未授权拒绝、幂等和精确设备键。
