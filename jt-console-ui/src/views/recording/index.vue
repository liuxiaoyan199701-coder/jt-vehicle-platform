<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useMessage } from 'naive-ui';
import dayjs from 'dayjs';
import { useAuthStore } from '@/store/modules/auth';
import { JT1078Player } from '@jt/player';
import type { OpenStreamRequest, StreamTicket as PlayerTicket } from '@jt/player';
import {
  createRecordingUpload,
  fetchRecordingUploads,
  fetchVehicles,
  openPlaybackStream,
  searchRecordings,
  type DeviceRecordingResource,
  type RecordingRange,
  type RecordingUploadTask,
  type Vehicle
} from '@/service/api';

defineOptions({ name: 'RecordingIndex' });

const message = useMessage();
const route = useRoute();
const authStore = useAuthStore();
const canPlayback = computed(() => authStore.hasPermission('recording:playback'));
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);

const query = ref({
  deviceId: null as string | null,
  channel: 1,
  range: [dayjs().subtract(1, 'hour').valueOf(), dayjs().valueOf()] as [number, number]
});

const platformAvailable = ref<boolean | null>(null);
const platformReason = ref('');
const platformRanges = ref<RecordingRange[]>([]);
const deviceAvailable = ref<boolean | null>(null);
const deviceReason = ref('');
const deviceRanges = ref<DeviceRecordingResource[]>([]);
const searched = ref(false);
const searching = ref(false);
const uploadTasks = ref<RecordingUploadTask[]>([]);
const uploadingKey = ref('');
const uploadedPlaying = ref<RecordingUploadTask | null>(null);

// 回放
const playing = ref<null | RecordingRange | DeviceRecordingResource>(null);
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
  const deviceId = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  const startTime = typeof route.query.startTime === 'string' ? route.query.startTime : '';
  const endTime = typeof route.query.endTime === 'string' ? route.query.endTime : '';
  if (deviceId && startTime && endTime) {
    query.value.deviceId = deviceId;
    query.value.range = [dayjs(startTime).valueOf(), dayjs(endTime).valueOf()];
    await search(Boolean(route.query.autoplay));
  }
});

async function search(autoplay = false) {
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
  searched.value = true;
  if (error || !data) {
    message.error(error?.message || '检索失败');
    platformAvailable.value = false;
    platformReason.value = error?.message || '检索失败';
    platformRanges.value = [];
    deviceAvailable.value = false;
    deviceReason.value = error?.message || '检索失败';
    deviceRanges.value = [];
    return;
  }
  platformAvailable.value = data.platform.available;
  platformReason.value = data.platform.reason ?? '';
  platformRanges.value = data.platform.segments ?? [];
  deviceAvailable.value = data.device.available;
  deviceReason.value = data.device.reason ?? '';
  deviceRanges.value = data.device.resources ?? [];
  await refreshUploads();
  if (autoplay && platformRanges.value.length) {
    // 告警入口传入的是 ±5 分钟窗口；从窗口起点开流，而不是从列表第一片自己的起点开流。
    await play({ startTime: start, endTime: end });
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

async function refreshUploads() {
  if (!query.value.deviceId) {
    uploadTasks.value = [];
    return;
  }
  const { data } = await fetchRecordingUploads(query.value.deviceId);
  uploadTasks.value = data ?? [];
}

async function upload(range: DeviceRecordingResource, index: number) {
  if (!query.value.deviceId) return;
  uploadingKey.value = `${index}`;
  const { data, error } = await createRecordingUpload(query.value.deviceId, range);
  uploadingKey.value = '';
  if (error || !data) {
    message.error(error?.message || '上传指令下发失败');
    return;
  }
  message.success('上传任务已下发，等待终端完成');
  await refreshUploads();
}

async function play(range: RecordingRange | DeviceRecordingResource) {
  if (!query.value.deviceId) return;
  await stopPlayback();
  playing.value = range;
  playbackVisible.value = true;
  playbackError.value = '';
  playbackState.value = 'opening';
  await nextTick();
  if (!canvasRef.value) {
    playbackError.value = '播放器画布初始化失败';
    return;
  }

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

const uploadStateText: Record<string, string> = {
  CREATED: '待下发',
  DISPATCHED: '终端处理中',
  FILE_RECEIVED: '文件已到达',
  COMPLETED: '已完成',
  FAILED: '失败'
};

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
        <NButton type="primary" size="small" :loading="searching" @click="search(false)">检索</NButton>
      </div>

      <NSpin :show="searching">
        <NEmpty v-if="!searched && !searching" description="选择车辆与时间后检索录像" class="py-40px" />
        <div v-else-if="!searching" class="flex flex-col gap-16px pt-8px">
          <section>
            <div class="mb-6px flex items-center gap-8px">
              <strong>平台侧录像</strong>
              <NTag :type="platformAvailable ? 'success' : 'error'" size="small">
                {{ platformAvailable ? '可用' : '不可用' }}
              </NTag>
              <span v-if="platformReason" class="text-12px text-#999">{{ platformReason }}</span>
            </div>
            <NEmpty v-if="platformAvailable && platformRanges.length === 0" description="该时段没有平台侧分片" size="small" />
            <div v-for="(range, index) in platformRanges" :key="`platform-${index}`" class="recording-row">
              <div class="flex items-center justify-between gap-8px">
                <span class="text-13px">
                  {{ fmt(range.startTime) }} ~ {{ fmt(range.endTime) }}
                  <span class="text-#999">（{{ Math.max(0, dayjs(range.endTime).diff(dayjs(range.startTime), 'second')) }} 秒）</span>
                </span>
                <NButton v-if="canPlayback" size="tiny" type="primary" @click="play(range)">回放</NButton>
              </div>
            </div>
          </section>

          <section>
            <div class="mb-6px flex items-center gap-8px">
              <strong>设备侧录像</strong>
              <NTag :type="deviceAvailable ? 'success' : 'warning'" size="small">
                {{ deviceAvailable ? '可用' : '不可用' }}
              </NTag>
              <span v-if="deviceReason" class="text-12px text-#999">{{ deviceReason }}</span>
            </div>
            <NEmpty v-if="deviceAvailable && deviceRanges.length === 0" description="终端返回的资源列表为空" size="small" />
            <div v-for="(range, index) in deviceRanges" :key="`device-${index}`" class="recording-row">
              <div class="flex items-center justify-between gap-8px">
                <span class="text-13px">
                  通道 {{ range.channel }} · {{ fmt(range.startTime) }} ~ {{ fmt(range.endTime) }}
                  <span class="text-#999">（{{ Math.max(0, dayjs(range.endTime).diff(dayjs(range.startTime), 'second')) }} 秒，{{ range.size }} 字节）</span>
                </span>
                <div v-if="canPlayback" class="flex gap-6px">
                  <NButton size="tiny" @click="play(range)">回放</NButton>
                  <NButton
                    size="tiny"
                    type="primary"
                    :loading="uploadingKey === `${index}`"
                    @click="upload(range, index)"
                  >上传</NButton>
                </div>
              </div>
            </div>
          </section>

          <section v-if="query.deviceId">
            <div class="mb-6px flex items-center justify-between">
              <strong>文件上传任务</strong>
              <NButton size="tiny" @click="refreshUploads">刷新状态</NButton>
            </div>
            <NEmpty v-if="uploadTasks.length === 0" description="暂无上传任务" size="small" />
            <div v-for="task in uploadTasks" :key="task.id" class="recording-row">
              <div class="flex items-center justify-between gap-8px">
                <div class="text-13px">
                  <NTag
                    size="small"
                    :type="task.status === 'COMPLETED' ? 'success' : task.status === 'FAILED' ? 'error' : 'info'"
                  >{{ uploadStateText[task.status] ?? task.status }}</NTag>
                  <span class="ml-8px">通道 {{ task.channelNo }} · {{ fmt(task.startAt) }} ~ {{ fmt(task.endAt) }}</span>
                  <span v-if="task.fileName" class="ml-8px text-#999">{{ task.fileName }}</span>
                </div>
                <div v-if="task.accessAddress" class="flex gap-6px">
                  <NButton size="tiny" @click="uploadedPlaying = task">回放文件</NButton>
                  <NButton tag="a" size="tiny" :href="task.accessAddress" target="_blank" download>下载</NButton>
                </div>
              </div>
            </div>
          </section>
        </div>
      </NSpin>
    </NCard>

    <NModal
      :show="Boolean(uploadedPlaying)"
      preset="card"
      title="上传录像文件"
      class="w-860px max-w-[calc(100vw-24px)]"
      @update:show="value => { if (!value) uploadedPlaying = null; }"
    >
      <video
        v-if="uploadedPlaying?.accessAddress"
        :src="uploadedPlaying.accessAddress"
        controls
        preload="metadata"
        class="aspect-video w-full rounded bg-black"
      ></video>
    </NModal>

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
