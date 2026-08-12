<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { JT1078Player } from '@jt/player';
import type { OpenStreamRequest, StreamTicket } from '@jt/player';
import { openStream } from '@/service/api';

defineOptions({ name: 'VideoDialog' });

interface VideoTarget {
  deviceId: string;
  plateNo: string;
  channelCount: number;
}

const props = defineProps<{ visible: boolean; target: VideoTarget | null }>();
const emit = defineEmits<{ 'update:visible': [value: boolean] }>();

const canvasRef = ref<HTMLCanvasElement | null>(null);
const channel = ref(1);
const streamKind = ref<'main' | 'sub'>('main');
const state = ref<string>('idle');
const errorMessage = ref('');
const loading = ref(false);

let player: JT1078Player | null = null;

/**
 * 自定义开流：jt-player 默认直接把 openStreamUrl 的响应体当作票据，
 * 而 jt-console 的接口统一包了一层 {code,msg,data}，所以这里接管开流并解包。
 * 顺带把网关的业务错误转成播放器能识别的异常。
 */
const opener = {
  async open(request: OpenStreamRequest): Promise<StreamTicket> {
    const { data, error } = await openStream(
      request.deviceId,
      request.channel,
      (request.streamKind === 'sub' ? 'sub' : 'main') as 'main' | 'sub'
    );
    if (error || !data) {
      throw new Error(error?.message || '开流失败');
    }
    return {
      wsUrl: data.wsUrl,
      token: data.token,
      state: (data.state === 'live' ? 'live' : 'waking') as 'live' | 'waking',
      streamId: data.streamId
    };
  }
};

watch(
  () => props.visible,
  async isVisible => {
    if (isVisible) {
      await nextTick();
      await start();
    } else {
      await stop();
    }
  }
);

watch([channel, streamKind], async () => {
  if (props.visible) {
    await stop();
    await start();
  }
});

async function start() {
  if (!props.target || !canvasRef.value) {
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  state.value = 'opening';

  try {
    player = new JT1078Player({ canvas: canvasRef.value, opener });
    player.on('state', event => {
      state.value = event.state;
    });
    player.on('error', event => {
      errorMessage.value = explainError(event.code, event.message);
    });
    await player.play({
      deviceId: props.target.deviceId,
      channel: channel.value,
      streamKind: streamKind.value
    });
  } catch (playError) {
    errorMessage.value = playError instanceof Error ? playError.message : String(playError);
    state.value = 'error';
  } finally {
    loading.value = false;
  }
}

async function stop() {
  if (player) {
    try {
      await player.stop();
      await player.destroy();
    } catch {
      // 关闭过程中的异常不影响 UI
    }
    player = null;
  }
  state.value = 'idle';
}

function close() {
  emit('update:visible', false);
}

/**
 * 播放器错误码本身不足以指导排查，补上可能的原因。
 * 解码失败尤其容易误判成「视频格式不对」，实际多半是浏览器侧的解码能力问题。
 */
function explainError(code: string, message: string) {
  if (code === 'DECODER_UNAVAILABLE') {
    return `浏览器不支持 WebCodecs，无法解码视频。请使用较新版本的 Chrome/Edge；若通过 IP 以 HTTP 访问，部分浏览器也会禁用该能力。(${message})`;
  }
  if (code === 'UNSUPPORTED_CODEC') {
    return `视频解码器初始化失败。常见原因是浏览器停用了硬件加速（chrome://settings/system）或运行在虚拟机/远程桌面中。原始信息：${message}`;
  }
  if (code === 'DEVICE_NO_RESPONSE') {
    return `终端未响应开流指令，可能未开启该通道或不支持 JT/T 1078。(${message})`;
  }
  return `${code}: ${message}`;
}

const stateText: Record<string, string> = {
  idle: '未开始',
  opening: '正在开流',
  connecting: '连接中',
  waking: '唤醒设备中',
  playing: '播放中',
  reconnecting: '重连中',
  paused: '已暂停',
  stopped: '已停止',
  error: '出错',
  destroyed: '已销毁'
};
</script>

<template>
  <NModal
    :show="visible"
    preset="card"
    :title="`实时视频 - ${target?.plateNo ?? ''}`"
    class="w-860px max-w-[calc(100vw-24px)]"
    @update:show="close"
  >
    <div class="flex flex-col gap-12px">
      <div class="flex items-center gap-12px">
        <span class="text-14px">通道</span>
        <NRadioGroup v-model:value="channel" size="small">
          <NRadioButton v-for="index in target?.channelCount ?? 4" :key="index" :value="index">
            {{ index }}
          </NRadioButton>
        </NRadioGroup>

        <span class="ml-12px text-14px">码流</span>
        <NRadioGroup v-model:value="streamKind" size="small">
          <NRadioButton value="main">主码流</NRadioButton>
          <NRadioButton value="sub">子码流</NRadioButton>
        </NRadioGroup>

        <NTag :type="state === 'playing' ? 'success' : 'info'" size="small" class="ml-auto">
          {{ stateText[state] ?? state }}
        </NTag>
      </div>

      <div class="relative aspect-video w-full rounded bg-black">
        <canvas ref="canvasRef" class="h-full w-full"></canvas>
        <NSpin v-if="loading" class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2" />
      </div>

      <NAlert v-if="errorMessage" type="error" :bordered="false">
        {{ errorMessage }}
      </NAlert>
      <NAlert v-else type="info" :bordered="false" class="text-12px">
        视频需要终端在线并支持 JT/T 1078。若长时间停留在「唤醒设备中」，说明终端未响应开流指令。
      </NAlert>
    </div>
  </NModal>
</template>
