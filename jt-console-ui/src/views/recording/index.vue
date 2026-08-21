<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import dayjs from 'dayjs';
import { JT1078Player } from '@jt/player';
import type { OpenStreamRequest, StreamTicket as PlayerTicket } from '@jt/player';
import {
  fetchVehicles,
  openPlaybackStream,
  searchRecordings,
  type RecordingRange,
  type Vehicle
} from '@/service/api';

defineOptions({ name: 'RecordingIndex' });

const message = useMessage();
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);

const query = ref({
  deviceId: null as string | null,
  channel: 1,
  range: [dayjs().subtract(1, 'hour').valueOf(), dayjs().valueOf()] as [number, number]
});

const ranges = ref<RecordingRange[]>([]);
const searching = ref(false);

// 回放
const playing = ref<null | RecordingRange>(null);
const playbackVisible = ref(false);
const playbackState = ref('idle');
const playbackError = ref('');
const canvasRef = ref<HTMLCanvasElement | null>(null);
const seekPosition = ref('');
let player: JT1078Player | null = null;

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

onMounted(async () => {
  const { data } = await fetchVehicles();
  vehicles.value = data ?? [];
});

async function search() {
  if (!query.value.deviceId) {
    message.warning('请选择车辆');
    return;
  }
  searching.value = true;
  const start = dayjs(query.value.range[0]).toISOString();
  const end = dayjs(query.value.range[1]).toISOString();
  const { data, error } = await searchRecordings(
    query.value.deviceId, query.value.channel, start, end
  );
  searching.value = false;
  if (error) {
    message.error(error.message || '检索失败');
    ranges.value = [];
    return;
  }
  ranges.value = data ?? [];
  if (!ranges.value.length) {
    message.info('该时间范围内没有录像');
  }
}

const opener = {
  async open(request: OpenStreamRequest): Promise<PlayerTicket> {
    const { data, error } = await openPlaybackStream(
      request.deviceId,
      request.channel,
      request.startTime ?? '',
      request.endTime ?? ''
    );
    if (error || !data) {
      throw new Error(error?.message || '回放开流失败');
    }
    return {
      wsUrl: data.wsUrl,
      token: data.token,
      state: data.state === 'live' ? 'live' : 'waking',
      streamId: data.streamId
    };
  }
};

async function play(range: RecordingRange) {
  if (!query.value.deviceId || !canvasRef.value) return;
  await stopPlayback();
  playing.value = range;
  playbackVisible.value = true;
  playbackError.value = '';
  playbackState.value = 'opening';

  try {
    player = new JT1078Player({ canvas: canvasRef.value, opener });
    player.on('state', event => {
      playbackState.value = event.state;
    });
    player.on('error', event => {
      playbackError.value = `${event.code}: ${event.message}`;
    });
    await player.playback({
      deviceId: query.value.deviceId,
      channel: query.value.channel,
      startTime: range.startTime,
      endTime: range.endTime
    });
  } catch (playError) {
    playbackError.value = playError instanceof Error ? playError.message : String(playError);
    playbackState.value = 'error';
  }
}

async function stopPlayback() {
  if (player) {
    try {
      await player.stop();
      await player.destroy();
    } catch {
      // 忽略关闭异常
    }
    player = null;
  }
  playbackState.value = 'idle';
  playbackError.value = '';
  playing.value = null;
  seekPosition.value = '';
}

function closePlayback() {
  stopPlayback();
  playbackVisible.value = false;
}

function pauseResume() {
  if (!player) return;
  if (playbackState.value === 'paused') {
    player.resumePlayback();
  } else if (playbackState.value === 'playing') {
    player.pausePlayback();
  }
}

function seek() {
  if (!player || !seekPosition.value) return;
  player.seekPlayback(seekPosition.value);
}

function fmt(iso: string) {
  return dayjs(iso).format('MM-DD HH:mm:ss');
}

const stateText: Record<string, string> = {
  idle: '未开始',
  opening: '正在开流',
  connecting: '连接中',
  waking: '等待数据',
  playing: '播放中',
  paused: '已暂停',
  error: '出错',
  stopped: '已停止',
  destroyed: '已销毁'
};
</script>

<template>
  <div class="p-16px">
    <NCard :bordered="false" size="small">
      <template #header><span>录像回放</span></template>

      <div class="mb-12px flex flex-wrap items-center gap-12px">
        <NSelect
          v-model:value="query.deviceId"
          :options="vehicleOptions"
          filterable
          clearable
          placeholder="选择车辆"
          class="w-220px"
        />
        <span class="text-13px text-#666">通道</span>
        <NInputNumber v-model:value="query.channel" :min="1" :max="255" class="w-80px" />
        <NDatePicker v-model:value="query.range" type="datetimerange" clearable class="w-320px" />
        <NButton type="primary" size="small" :loading="searching" @click="search">检索</NButton>
      </div>

      <NSpin :show="searching">
        <NEmpty v-if="!searching && ranges.length === 0" description="选择车辆与时间后检索录像" class="py-40px" />
        <div v-for="(range, index) in ranges" :key="index" class="recording-row">
          <div class="flex items-center justify-between gap-8px">
            <span class="text-13px">
              {{ fmt(range.startTime) }} ~ {{ fmt(range.endTime) }}
              <span class="text-#999">（{{ Math.max(0, dayjs(range.endTime).diff(dayjs(range.startTime), 'second')) }} 秒）</span>
            </span>
            <NButton size="tiny" type="primary" @click="play(range)">回放</NButton>
          </div>
        </div>
      </NSpin>
    </NCard>

    <NModal
      v-model:show="playbackVisible"
      preset="card"
      title="录像回放"
      class="w-860px max-w-[calc(100vw-24px)]"
      @update:show="closePlayback"
    >
      <div class="flex flex-col gap-12px">
        <div class="flex items-center gap-12px">
          <NTag :type="playbackState === 'playing' ? 'success' : 'info'" size="small">
            {{ stateText[playbackState] ?? playbackState }}
          </NTag>
          <NButton size="small" @click="pauseResume">
            {{ playbackState === 'paused' ? '继续' : '暂停' }}
          </NButton>
          <NInput v-model:value="seekPosition" placeholder="跳转到时间 (ISO)" class="w-260px" size="small" />
          <NButton size="small" @click="seek">跳转</NButton>
        </div>

        <div class="relative aspect-video w-full rounded bg-black">
          <canvas ref="canvasRef" class="h-full w-full"></canvas>
        </div>

        <NAlert v-if="playbackError" type="error" :bordered="false">{{ playbackError }}</NAlert>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.recording-row { padding: 10px 12px; border-top: 1px solid rgb(239 239 245); }
.recording-row:hover { background: rgb(24 160 88 / 7%); }
</style>
