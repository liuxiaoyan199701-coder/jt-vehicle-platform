# JT/T 1078 视频在浏览器里播不出来：一个和编码无关的坑

> 本文是 [jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
> 开发过程中的真实排查记录。

现象很简单：JT/T 1078 的裸流已经完整送到浏览器，播放器却报错

```
UNSUPPORTED_CODEC: Video decoder configuration failed
```

按字面意思，第一反应一定是「编码格式不对」。**但真正的原因和编码毫无关系。**

---

## 第一步：先确认流本身没问题

排查视频问题最忌讳猜。先写个脚本直接连 WebSocket 抓裸流，把帧头解析出来：

```js
const socket = new WebSocket(ticket.wsUrl);
socket.binaryType = 'arraybuffer';

socket.addEventListener('message', event => {
  const bytes = new Uint8Array(event.data);
  const magic = String.fromCharCode(...bytes.slice(0, 4));   // "JT78"
  const type  = bytes[4];                                     // 0xF0=SPS 0xF1=PPS 0x00=I帧
  const payload = bytes.slice(8);
  // ...
});
```

抓到的结果：

```
SPS  : 00 00 00 01 67 42 c0 1f da 01 40 16 e8 40 ...
       起始码: 4 字节 00000001
       首字节 0x67 -> H264 NAL type 7 (SPS)
       profile_idc=66  constraints=0xc0  level_idc=31
       => 播放器将使用 codec: avc1.42c01f
PPS  : 00 00 00 01 68 ce 3c 80

统计: 总帧数 1061  I帧 6  P帧 324  音频 727  SPS 2  PPS 2
```

结论很明确：

- **1061 帧完整送达**，链路没问题
- SPS 格式标准，起始码正确，NAL 类型正确
- `avc1.42c01f` 是 **H.264 Baseline Profile Level 3.1** —— 兼容性最好的组合，
  就没有哪个浏览器不支持它

**所以问题不在设备、不在网关、不在编码。**

---

## 第二步：让错误说真话

播放器抛的是 `UNSUPPORTED_CODEC`，但这个错误码是在一个大 try-catch 里包装出来的：

```js
try {
  this.decoder = this.decoderFactory({ ... });   // ← 真正失败的是这一行
  const config = { codec: h264CodecString(sps), description: ... };
  this.decoder.configure(config);
} catch (error) {
  this.onRecoverableError?.(new JTPlayerError('Video decoder configuration failed', {
    code: 'UNSUPPORTED_CODEC',      // ← 把真实原因盖住了
    cause: error
  }));
}
```

`decoderFactory` 内部抛的其实是另一个错误：

```js
function browserVideoDecoderFactory(init) {
  const Decoder = globalThis.VideoDecoder;
  if (!Decoder) {
    throw new JTPlayerError('WebCodecs VideoDecoder is unavailable', {
      code: 'DECODER_UNAVAILABLE', fatal: true
    });
  }
  return new Decoder(init);
}
```

把 `cause` 的信息带进错误消息后，真相浮现：

```
Video decoder configuration failed: WebCodecs VideoDecoder is unavailable
```

**`window.VideoDecoder` 根本不存在。**

> 教训：错误包装时一定要把 `cause` 的信息透出来，否则排查会被错误码带偏。
> 我们最初就因为看到 `UNSUPPORTED_CODEC` 而排除了"API 不可用"这个方向。

---

## 根因：WebCodecs 要求安全上下文

[WebCodecs API](https://developer.mozilla.org/docs/Web/API/WebCodecs_API) 只在
**Secure Context** 下暴露。也就是说，只有以下情况才有 `VideoDecoder`：

- HTTPS
- `http://localhost` 或 `http://127.0.0.1`
- `file://`

而我们当时是通过 `http://47.100.247.30` 访问的——**纯 IP + HTTP，不是安全上下文**，
浏览器压根不提供这个 API。

这也解释了为什么本地开发一直正常：开发服务器跑在 `localhost` 上，属于安全上下文。

---

## 但上了 HTTPS，视频反而更打不开

配好证书后会撞上第二个坑：**混合内容策略**。

网关返回的开流票据里，`wsUrl` 是直连媒体节点的明文地址：

```json
{ "wsUrl": "ws://47.100.247.30:7815/ws?deviceId=...&token=..." }
```

页面一旦是 HTTPS，浏览器会拦掉一切 `ws://` 连接。于是出现一个尴尬局面：

- HTTP 访问 → 没有 WebCodecs，解不了码
- HTTPS 访问 → WebCodecs 有了，但连不上流

**两件事必须一起做**，缺一不可：

### 1. nginx 增加 wss 转发

```nginx
location = /media-ws {
    proxy_pass http://127.0.0.1:7815/ws;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;   # 视频是持续长连接
    proxy_buffering off;        # 不缓冲，降低延迟
}
```

### 2. 后端改写票据里的地址

```java
/**
 * HTTPS 页面下浏览器会按混合内容策略拦掉明文 ws://，不改写视频就打不开。
 * 只替换协议、主机和路径，查询串（含一次性 token）原样保留。
 */
private void rewriteWebsocketUrl(Map<String, Object> ticket) {
    String url = ticket.get("wsUrl").toString();
    int queryStart = url.indexOf('?');
    String query = queryStart >= 0 ? url.substring(queryStart) : "";
    ticket.put("wsUrl", publicWebsocketBaseUrl + query);
}
```

改写后：

```
ws://47.100.247.30:7815/ws?deviceId=1380000&channel=1&streamKind=main
        ↓
wss://47.100.247.30/media-ws?deviceId=1380000&channel=1&streamKind=main
```

一次性 token 在查询串里，原样保留即可。顺带的好处是：**媒体端口不再需要对公网开放**，
浏览器只和 443 打交道。

---

## 顺手修掉的另一个健壮性问题

排查过程中还发现播放器硬编码了硬件解码：

```js
const config = {
  codec: h264CodecString(sps),
  hardwareAcceleration: 'prefer-hardware',   // ← 拿不到就直接失败，没有回退
  optimizeForLatency: true
};
```

按 WebCodecs 规范，实现**可以**在无法满足 `prefer-hardware` 时直接拒绝配置。
在浏览器禁用了硬件加速、跑在虚拟机或远程桌面、显卡驱动缺失的环境里，
即使 codec 完全受支持也会失败。

加上软件解码回退：

```js
try {
  this.decoder.configure({ ...config, hardwareAcceleration: 'prefer-hardware' });
} catch (hardwareError) {
  // 硬件解码不可用，退回软件解码；两者都失败才向上抛
  if (this.decoder.state === 'closed') {
    this.decoder = this.decoderFactory({ ... });
  }
  this.decoder.configure(config);
}
```

---

## 小结

| 症状 | 真实原因 | 解法 |
|---|---|---|
| `VideoDecoder is unavailable` | 非安全上下文 | 用 HTTPS 或 localhost 访问 |
| HTTPS 下连不上流 | 混合内容拦截 `ws://` | nginx 转 wss + 后端改写 wsUrl |
| 特定机器上解码失败 | 无硬件解码器 | 回退到软件解码 |
| 错误码指向错误方向 | 异常包装丢了 cause | 把 cause 信息带进消息 |

还有一个容易误判的点：**「收到几帧就没了」通常不是转发故障**。网关会给新订阅者补发
缓存的 SPS/PPS 和最近一个 I 帧，所以即使设备已经停止推流，连上去也能收到 3 帧。
判断是否真的有流，要看媒体节点的 `currentStreams` 指标，而不是收没收到数据。

---

完整实现见
[jt-vehicle-platform](https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform)
（Gitee：[lxygit0731/jt-vehicle-platform](https://gitee.com/lxygit0731/jt-vehicle-platform)），
其中的抓流脚本 `jt-console/tools/inspect-stream.mjs` 可以直接拿去排查你自己的 1078 流。
