# JT/T 808 对接踩坑：设备明明连上了，平台上却找不到这台车

> 本文是 [jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
> 开发过程中的真实排查记录。所有日志和代码位置都来自实际运行的系统。

做部标车辆监控，几乎每个人都会遇到这么一幕：

终端配置好了，网关日志里明明白白写着注册成功，业务库里却查不到这台车；或者车辆列表里
多出一台"幽灵设备"，永远离线、永远没有坐标。

这类问题的根因往往不在协议解析，而在**「设备标识」这件看似最简单的事上**。

---

## 现象一：注册成功了，车辆列表里没有

网关日志：

```
<<<<< Connected/116.30.230.230:1649
<<<<< Registered/116.30.230.230:1649/1380000/138000000000
```

投递也在正常工作，业务后端每 30 秒收到一条消息。但监控页面上一台车都没有。

打开自建的诊断接口看看到底收到了什么：

```json
{
  "summary": [
    { "messageId": "0x0002", "name": "终端心跳", "count": 1 }
  ],
  "recent": [
    { "at": "07:33:07", "deviceId": "1380000", "messageIdHex": "0x0002",
      "type": "heartbeat", "outcome": "touched" }
  ]
}
```

**只有心跳，没有 0x0200 位置汇报。**

这台"终端"其实是一个视频推流模拟器：它实现了注册、鉴权、心跳和 1078 音视频，
但从不上报位置。而业务后端当时的设计是「设备记录由位置汇报创建」，于是这台设备
在界面上完全不可见。

### 为什么会这么设计

因为要躲另一个坑（见下文的"幽灵设备"）。当时的实现是：非位置消息只更新已有记录，
不新建。代价就是纯视频类终端永远不会出现。

### 正确的做法

非位置报文也要能建立设备记录，只是不带坐标：

```java
if (!envelope.isLocationReport()) {
    // 心跳、鉴权等非位置消息只刷新在线时间。
    // 这里必须 UPSERT 而不是只 UPDATE——有些终端从不上报位置，
    // 不建行的话它在界面上完全不可见，也就无从对它开流看视频。
    statuses.touch(deviceId, receivedAt);
    return LocationHandlingResult.withoutLiveUpdate("touched");
}
```

改完之后，这类设备显示为「在线 · 未定位」，地图上不打点，但可以正常开流看视频。

> 这里能放心 UPSERT，前提是所有报文的 `deviceId` 都指向同一个标识。
> 早期实现不满足这个前提，才不得不对注册报文做特判——见下一节。

---

## 现象二：同一台车出现两条记录，其中一条永远离线

更隐蔽的一个坑。设备列表里出现了 `SIM0000` 这样的记录，它永远离线、永远没有坐标，
而真实设备是另一条 `13800138000`。

根因在于**投递信封里的 `deviceId` 取自哪个字段**。早期实现是这样的：

```java
// 早期版本：优先取会话里绑定的 DeviceDO.deviceId
if (device != null && hasText(device.getDeviceId())) {
    return device.getDeviceId();       // ← 注册时被设成了 T0100 的终端 ID
}
return message.getClientId();          // 手机号
```

而注册报文在建立会话时是这样填的（`JT808Endpoint.java`）：

```java
DeviceDO presentedDevice = new DeviceDO()
        .setMobileNo(message.getClientId())    // BCD 终端手机号
        .setDeviceId(message.getDeviceId());   // ← 终端自报的「终端 ID」
```

于是同一台设备在不同阶段给出了不同的标识，业务侧一 UPSERT 就多出一条永不更新的记录。

当时的补丁是「注册报文不参与设备记录创建」，能挡住幽灵设备，但治标不治本——
因为**根子上就不该让业务标识在生命周期中发生变化**。

### 最终的做法：确立 canonical ID

后来把规则收敛成一句话：**业务标识只认协议解码后的 `mobileNo/SIM`**。

```java
private static String resolveDeviceId(Session session, JTMessage message) {
    DeviceDO device = session.getAttribute(SessionKey.Device);
    if (device != null && hasText(device.getMobileNo())) {
        return device.getMobileNo();          // 始终是手机号
    }
    if (hasText(message.getClientId())) {
        return message.getClientId();         // 同样是手机号
    }
    // 拿不到 canonical 标识就直接失败，而不是退而求其次用别名
    throw new IllegalArgumentException(
        "message does not provide a canonical mobileNo/SIM identity");
}
```

关键变化有两点：

1. **不再回退到终端 ID**。厂商终端 ID 只是信令路由别名，它能定位一个 808 会话，
   但不能作为业务主键——更不能作为媒体 `StreamKey` 或 1078 RTP 里的 SIM 替代值
2. **取不到就抛错**，而不是找个别名凑合。宁可让问题在投递阶段暴露，
   也不要放一条身份可疑的数据进业务库

这样一来，注册、鉴权、心跳、位置汇报拿到的都是同一个 `deviceId`，幽灵设备从源头消失，
业务侧也不需要针对特定报文类型做特判了。

---

## 现象三：建了档，但位置数据关联不上

这是最容易让人怀疑人生的一个。

车辆档案里填的终端号是 `013800138000`（跟设备上配置的一模一样），
但上报的位置就是关联不到这台车。查库才发现，轨迹表里存的是 `13800138000`——**前导零没了**。

### 根因：BCD 是固定宽度编码

JT/T 808 的终端手机号字段是 `BCD[6]`，即 6 字节压缩十进制，固定表示 12 位数字。
位数不足时**在编码阶段左侧补零**凑满宽度，解码时这些填充零是否保留，取决于具体实现。

关键在于认清一件事：**这些补位零属于 wire format，不属于设备身份**。

### 我们最初的错误解法

第一版补丁是在查询里做去零比较：

```sql
LEFT JOIN vehicle v ON ltrim(v.device_id, '0') = ltrim(s.device_id, '0')
```

能跑通，但有两个问题：

1. **用不上索引前缀**，数据量上来后轨迹查询会明显变慢
2. **更严重的是语义错误**：它会把 `00123` 和 `123` 当成同一台设备

第二点是致命的。终端 ID 允许厂商自定义，`00123` 完全可以是一个真实存在、
与 `123` 无关的设备编号。为了兼容显示问题而在业务层做数值化处理，等于擅自
修改了设备身份，在多租户或大车队场景下会造成数据串号。

### 最终的做法：精确匹配，把边界划在协议层

后来我们把规则改成了：

> canonical `deviceId` 是协议解码后的 `mobileNo/SIM`。控制台只对它做首尾空白清理，
> 随后执行**精确字符串**存储和比较。协议解码后的 `00123` 与 `123` 是两个设备，
> 禁止再做 `ltrim`、数值转换或正则去零。

代码简单到只剩下 trim：

```java
private static String canonicalDeviceId(String deviceId) {
    if (deviceId == null) {
        throw new IllegalArgumentException("终端号不能为空");
    }
    String trimmed = deviceId.trim();
    if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("终端号不能为空");
    }
    return trimmed;   // 不做任何去零或数值转换
}
```

**填充零的处理责任归协议解码层**，业务层拿到什么就是什么。这样既不会误合并设备，
也保住了索引。

那么用户该填什么？**填平台实际收到的值**。这就是为什么要有一个诊断接口——
不用猜、不用查文档，直接看：

```
GET /api/diagnostics/events
→ { "deviceId": "13800138000", "messageIdHex": "0x0002", "outcome": "touched" }
```

照抄这个 `deviceId` 建档即可。

---

## 现象四：白名单填了设备号，设备还是连不上

启用 `local-list` 设备鉴权后，把终端手机号填进白名单，设备却一直注册失败，
返回 `result=4`（数据库中无该终端）。

因为白名单比对的**不是手机号**：

```java
private static String terminalIdOf(DeviceDO device) {
    if (!isBlank(device.getDeviceId())) {
        return device.getDeviceId().trim();     // ← 优先取终端 ID
    }
    if (!isBlank(device.getMobileNo())) {
        return device.getMobileNo().trim();     // 终端 ID 为空才回退到手机号
    }
    return null;
}
```

**优先取终端注册报文里自报的「终端 ID」**，只有该字段为空时才回退到手机号。
所以白名单里要填的是终端 ID，不是手机号。

排查这类问题最快的办法不是翻文档，而是**看平台实际收到的是什么**。这也是为什么
值得做一个诊断接口——它直接告诉你每条报文的 deviceId 和处理结果，照抄即可。

---

## 小结：三个 ID，别混淆

| 名称 | 来源 | 典型值 | 用途 |
|---|---|---|---|
| 终端手机号（canonical ID） | 消息头 `BCD[6]`/`BCD[10]` | `13800138000` | 报文寻址、媒体 StreamKey、业务主键 |
| 终端 ID | 0x0100 消息体 7 字节字段 | `SIM0000`、`1380000` | 注册标识、白名单比对、信令路由别名 |
| 业务设备 ID | 鉴权后由平台绑定 | 自定义 | 关联业务档案 |

要特别注意：**终端 ID 只是信令别名，不能当作媒体 StreamKey 或 RTP 的 SIM 替代值**。
平台可以通过任一别名向设备下发 9101 开流指令，但设备发送 1078 RTP 时编码的始终是
终端手机号。把两者混用，会出现「指令下发成功但流对不上」的问题。

对接时建议按这个顺序确认：

1. **先看平台实际收到的 deviceId 是什么**（诊断接口 / 日志），不要假设
2. **建档用这个实际值**，不要用你以为的号码
3. **精确字符串比较，不做去零或数值转换** —— 填充零属于编码格式，
   而 `00123` 和 `123` 可能是两台不同的设备
4. **注册报文不参与设备记录创建**，避免幽灵设备
5. **纯视频终端也要能建记录**，否则它在界面上不存在

---

这些坑的完整实现都在
[jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
里（Gitee 镜像：[lxygit0731/jt-vehicle-platform](https://gitee.com/lxygit0731/jt-vehicle-platform)），
包含协议网关、业务后端、控制台前端和浏览器裸流播放器，`docker compose up` 可以直接跑起来。
如果你也在做部标对接，欢迎交流踩坑经验。
