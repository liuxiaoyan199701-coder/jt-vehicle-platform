<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { JT1078Player } from '@jt/player';
import type { OpenStreamRequest, StreamTicket } from '@jt/player';
import { openStream } from '@/service/api/console';

/**
 * 实时视频。**只在放大面板里实例化**，对话气泡里放的是一张引用卡。
 *
 * 这是全部视图里资源最重的一路：一个播放器等于一个长连接 + 一个硬件解码器 + 一个音频上下文，
 * 而后两者都有浏览器级的并发上限。已识别的泄漏路径逐条堵在这里，其余（切换会话、切换视图、
 * 路由离开）由面板与对话页负责。
 */
defineOptions({ name: 'LiveVideoView' });

const props = defineProps<{
  params: Record<string, unknown>;
  mode?: 'inline' | 'panel';
}>();

const canvasRef = ref<HTMLCanvasElement | null>(null);
const state = ref('idle');
const errorMessage = ref('');
const paused = ref(false);
let player: JT1078Player | null = null;
let hiddenSince = 0;

const deviceId = String(props.params.deviceId ?? '').trim();
const channel = Number(props.params.channel ?? 1);

/** 控制台把响应统一包成 {code,msg,data}，而 SDK 默认把响应体当票据，所以要自己解一层。 */
const opener = {
  async open(request: OpenStreamRequest): Promise<StreamTicket> {
    const { data, error } = await openStream(request.deviceId, request.channel, 'main');
    if (error || !data) throw new Error(error?.message || '开流失败');
    return {
      wsUrl: data.wsUrl,
      token: data.token,
      state: data.state === 'live' ? 'live' : 'waking',
      streamId: data.streamId
    };
  }
};

function explain(code: string, fallback: string) {
  if (code === 'DECODER_UNAVAILABLE') return '当前浏览器不支持视频解码，请用新版 Chrome/Edge，并确保通过 HTTPS 访问。';
  if (code === 'UNSUPPORTED_CODEC') return '这路码流的编码格式当前浏览器无法解码。';
  if (code === 'AUTH_FAILED') return '开流凭据无效，请重试。';
  return fallback || '播放失败';
}

async function start() {
  if (player || !canvasRef.value) return;
  errorMessage.value = '';
  player = new JT1078Player({
    canvas: canvasRef.value,
    opener,
    // 必须显式传 null 才不会创建音频上下文——传 undefined 或干脆不传都会创建，
    // 而浏览器每个文档只允许个位数个。**这一行不是冗余参数，别顺手删掉。**
    audioOutput: null
  });
  player.on('state', event => {
    state.value = event.state;
  });
  player.on('error', event => {
    errorMessage.value = explain(event.code, event.message);
  });
  try {
    await player.play({ deviceId, channel, streamKind: 'main' });
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : '播放失败';
  }
}

/**
 * 停止并释放。
 *
 * 销毁是异步的而框架不会等它——组件同步卸载后，销毁流程里还会去碰已经没了的画布。
 * 所以每一步都各自兜住异常，不让一个失败挡住后面的释放。
 */
async function teardown() {
  const current = player;
  player = null;
  if (!current) return;
  try {
    await current.stop();
  } catch {
    // 已经停了或连接早断了，继续往下释放。
  }
  try {
    await current.destroy();
  } catch {
    // 同上。释放失败也不该抛给界面。
  }
}

/**
 * 标签页切走后不能继续拉流。
 *
 * 画面不刷新了，但连接还在收、解码队列还在堆——2 Mbps 挂十分钟就是一百多兆流量和内存。
 */
function onVisibility() {
  if (document.hidden) {
    hiddenSince = Date.now();
    return;
  }
  hiddenSince = 0;
}

let idleTimer: ReturnType<typeof setInterval> | null = null;

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility);
  idleTimer = setInterval(() => {
    if (document.hidden && hiddenSince && Date.now() - hiddenSince > 30_000 && player) {
      paused.value = true;
      void teardown();
    }
  }, 5_000);
  void start();
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility);
  if (idleTimer) clearInterval(idleTimer);
  void teardown();
});

function resume() {
  paused.value = false;
  void start();
}
</script>

<template>
  <div class="w-full" role="region" :aria-label="`实时视频：${deviceId} 第 ${channel} 路`">
    <div class="relative w-full overflow-hidden rd-6px bg-black" :class="mode === 'panel' ? 'aspect-video' : 'h-200px'">
      <canvas ref="canvasRef" class="h-full w-full" />
      <div
        v-if="errorMessage"
        class="absolute inset-0 flex-col-center gap-6px bg-black/80 px-16px text-center text-13px text-white"
      >
        <SvgIcon icon="mdi:video-off-outline" class="text-24px" />
        <span>{{ errorMessage }}</span>
      </div>
      <div v-else-if="paused" class="absolute inset-0 flex-col-center gap-8px bg-black/80 text-white">
        <span class="text-13px">已暂停（页面切走超过 30 秒）</span>
        <NButton size="small" secondary @click="resume">继续播放</NButton>
      </div>
      <div v-else-if="state !== 'playing'" class="absolute inset-0 flex-col-center gap-6px text-white">
        <NSpin size="small" />
        <span class="text-12px">{{ state === 'waking' ? '正在唤醒设备…' : '连接中…' }}</span>
      </div>
    </div>
    <p class="mt-4px text-12px text-gray-600 dark:text-gray-300">
      {{ deviceId }} · 第 {{ channel }} 路 · 静音播放
    </p>
  </div>
</template>
