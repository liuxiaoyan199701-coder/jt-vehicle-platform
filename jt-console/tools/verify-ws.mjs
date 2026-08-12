// 验证 jt-console 的实时位置推送（经 nginx 的 WebSocket 转发）
// 用法: JT_CONSOLE_ACCESS_TOKEN_FILE=/run/secrets/access-token node verify-ws.mjs wss://console.example.com/ws/live
// 可用 JT_CONSOLE_ORIGIN 覆盖从 WebSocket URL 推导的页面 Origin。
// Node 22+ 内置 WebSocket，无需依赖。

import { readFileSync } from 'node:fs';

const url = process.argv[2] ?? 'ws://127.0.0.1:8300/ws/live';
const timeoutSeconds = Number(process.argv[3] ?? 60);
const tokenFile = process.env.JT_CONSOLE_ACCESS_TOKEN_FILE?.trim();
const token = process.env.JT_CONSOLE_ACCESS_TOKEN?.trim() ||
  (tokenFile ? readFileSync(tokenFile, 'utf8').trim() : '');
const endpoint = new URL(url);
const inferredOrigin = `${endpoint.protocol === 'wss:' ? 'https:' : 'http:'}//${endpoint.host}`;
const origin = process.env.JT_CONSOLE_ORIGIN?.trim() || inferredOrigin;

if (!token) {
  console.error('请通过 JT_CONSOLE_ACCESS_TOKEN 或 JT_CONSOLE_ACCESS_TOKEN_FILE 提供短期访问 token');
  process.exit(2);
}

if (!Number.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
  console.error('监听秒数必须为正数');
  process.exit(2);
}

console.log(`连接 ${url}，监听 ${timeoutSeconds} 秒...`);

const socket = new WebSocket(url, {
  protocols: ['jt-console.v1', `bearer.${token}`],
  headers: { Origin: origin }
});
let received = 0;

socket.addEventListener('open', () => console.log('✓ 鉴权 WebSocket 已连接（nginx Upgrade 转发正常）'));

socket.addEventListener('message', event => {
  received += 1;
  try {
    const message = JSON.parse(event.data);
    if (message.type === 'location') {
      const d = message.data;
      console.log(
        `[${received}] ${d.deviceId}  WGS84 ${d.lat.toFixed(6)},${d.lng.toFixed(6)}` +
          `  ->  GCJ02 ${d.gcjLat.toFixed(6)},${d.gcjLng.toFixed(6)}` +
          `  ${d.speedKph?.toFixed(1) ?? '-'} km/h  方向 ${d.direction}°`
      );
    } else {
      console.log(`[${received}] 其他消息:`, event.data.slice(0, 120));
    }
  } catch {
    console.log(`[${received}] 非 JSON 消息:`, String(event.data).slice(0, 120));
  }
});

socket.addEventListener('error', () => console.error('✗ WebSocket 错误'));
socket.addEventListener('close', event => console.log(`连接关闭 code=${event.code}`));

setTimeout(() => {
  console.log(`\n结果：共收到 ${received} 条实时推送`);
  socket.close();
  process.exit(received > 0 ? 0 : 1);
}, timeoutSeconds * 1000);
