// 抓取裸流帧并解析，用于排查播放器解码失败
// 用法: node inspect-stream.mjs <deviceId> [channel] [秒数]
// Node 22+ 内置 WebSocket 与 fetch，无需依赖

import { readFileSync } from 'node:fs';

const BASE = process.env.JT_CONSOLE_BASE ?? 'http://127.0.0.1:8300';
const deviceId = process.argv[2] ?? '1380000';
const channel = Number(process.argv[3] ?? 1);
const seconds = Number(process.argv[4] ?? 25);

function readSecret(name) {
  const direct = process.env[name]?.trim();
  if (direct) return direct;
  const file = process.env[`${name}_FILE`]?.trim();
  return file ? readFileSync(file, 'utf8').trim() : '';
}

async function accessToken() {
  const configured = readSecret('JT_CONSOLE_ACCESS_TOKEN');
  if (configured) return configured;

  const password = readSecret('JT_CONSOLE_ADMIN_PASSWORD');
  if (!password) {
    throw new Error(
      '请提供 JT_CONSOLE_ACCESS_TOKEN(_FILE)，或 JT_CONSOLE_ADMIN_PASSWORD(_FILE) 以安全登录'
    );
  }
  const response = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userName: process.env.JT_CONSOLE_ADMIN_USERNAME?.trim() || 'admin',
      password
    })
  });
  const result = await response.json();
  if (!response.ok || result.code !== '0000' || !result.data?.token) {
    throw new Error(`控制台登录失败（HTTP ${response.status}）`);
  }
  return result.data.token;
}

const FRAME_TYPE = {
  0x00: 'I帧',
  0x01: 'P帧',
  0x02: 'B帧',
  0x03: '音频',
  0xf0: 'SPS',
  0xf1: 'PPS',
  0xf2: 'AudioConfig',
  0xf3: 'VPS'
};

const NAL_TYPE = {
  1: 'non-IDR片',
  5: 'IDR片',
  6: 'SEI',
  7: 'SPS',
  8: 'PPS',
  9: 'AUD(分隔符)',
  32: 'H265-VPS',
  33: 'H265-SPS',
  34: 'H265-PPS'
};

const hex = bytes => [...bytes].map(b => b.toString(16).padStart(2, '0')).join(' ');

console.log(`开流 ${deviceId} 通道 ${channel} ...`);
const token = await accessToken();
const response = await fetch(`${BASE}/api/stream/open`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ deviceId, channel, streamKind: 'main' })
});
const result = await response.json();
if (result.code !== '0000') {
  console.error('开流失败:', result.msg);
  process.exit(1);
}
if (!result.data?.wsUrl) {
  console.error('开流失败: 响应缺少媒体地址');
  process.exit(1);
}
console.log(`state = ${result.data.state}\n`);

const socket = new WebSocket(result.data.wsUrl);
socket.binaryType = 'arraybuffer';

const counts = new Map();
let frames = 0;
let parameterSetsSeen = 0;

socket.addEventListener('open', () => console.log('WebSocket 已连接，等待媒体帧...\n'));

socket.addEventListener('message', event => {
  if (typeof event.data === 'string') {
    console.log('文本消息:', event.data);
    return;
  }
  const bytes = new Uint8Array(event.data);
  if (bytes.length < 8) return;

  const magic = String.fromCharCode(...bytes.slice(0, 4));
  if (magic !== 'JT78') {
    console.log('非 JT78 帧，前 16 字节:', hex(bytes.slice(0, 16)));
    return;
  }

  const type = bytes[4];
  const name = FRAME_TYPE[type] ?? `未知(0x${type.toString(16)})`;
  counts.set(name, (counts.get(name) ?? 0) + 1);
  frames += 1;

  const payload = bytes.slice(8);

  // 参数集只出现一次，是解码器配置的关键，全部打印出来
  if (type === 0xf0 || type === 0xf1 || type === 0xf3) {
    parameterSetsSeen += 1;
    console.log(`── ${name}  长度 ${payload.length} 字节`);
    console.log(`   完整字节: ${hex(payload)}`);

    // 判断是否带 Annex-B 起始码
    const hasStartCode4 = payload[0] === 0 && payload[1] === 0 && payload[2] === 0 && payload[3] === 1;
    const hasStartCode3 = payload[0] === 0 && payload[1] === 0 && payload[2] === 1;
    const body = hasStartCode4 ? payload.slice(4) : hasStartCode3 ? payload.slice(3) : payload;
    console.log(`   起始码: ${hasStartCode4 ? '4 字节 00000001' : hasStartCode3 ? '3 字节 000001' : '无'}`);

    const nalType = body[0] & 0x1f;
    console.log(`   首字节 0x${body[0].toString(16)} -> H264 NAL type ${nalType} (${NAL_TYPE[nalType] ?? '?'})`);

    if (nalType === 7) {
      // 播放器按 data[1..3] 取 profile/compat/level 拼 codec string
      const codec = `avc1.${body[1].toString(16).padStart(2, '0')}${body[2].toString(16).padStart(2, '0')}${body[3].toString(16).padStart(2, '0')}`;
      console.log(`   profile_idc=${body[1]}  constraints=0x${body[2].toString(16)}  level_idc=${body[3]}`);
      console.log(`   => 播放器将使用 codec: ${codec}`);
    } else if (nalType !== 7 && (type === 0xf0)) {
      console.log(`   ⚠ 这一帧标记为 SPS，但 NAL 类型不是 7，播放器会拼出错误的 codec 字符串`);
    }
    console.log();
  }

  if (frames <= 3) {
    console.log(`${name}  payload ${payload.length} 字节  前 16: ${hex(payload.slice(0, 16))}`);
  }
});

socket.addEventListener('error', () => console.error('WebSocket 错误'));
socket.addEventListener('close', e => console.log(`\n连接关闭 code=${e.code}`));

setTimeout(() => {
  console.log('\n===== 统计 =====');
  console.log(`总帧数: ${frames}，参数集帧: ${parameterSetsSeen}`);
  for (const [name, count] of counts) console.log(`  ${name}: ${count}`);
  if (frames === 0) console.log('  未收到任何媒体帧 —— 终端没有推流上来');
  socket.close();
  process.exit(0);
}, seconds * 1000);
