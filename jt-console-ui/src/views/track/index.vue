<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useMessage } from 'naive-ui';
import { fetchLiveStatus, fetchTrack, fetchVehicles, type TrackPoint } from '@/service/api';
import { useAMap } from '@/hooks/use-amap';
import { mergeTrackTargets, type TrackTarget } from '@/utils/track-targets';

defineOptions({ name: 'TrackIndex' });

const message = useMessage();
const route = useRoute();
const mapContainer = ref<HTMLElement | null>(null);
const { map, AMap, ready, error, init } = useAMap();

const trackTargets = ref<TrackTarget[]>([]);
const deviceId = ref<string>('');
const range = ref<[number, number]>([Date.now() - 3600 * 1000 * 6, Date.now()]);
const loading = ref(false);
const points = ref<TrackPoint[]>([]);
const summary = ref<{ distanceKm: number; maxSpeedKph: number; avgSpeedKph: number } | null>(null);
const truncated = ref(false);

// 回放控制
const playing = ref(false);
const cursor = ref(0);
const speedFactor = ref(4);
let timer: ReturnType<typeof setInterval> | null = null;

const polyline = shallowRef<any>(null);
const carMarker = shallowRef<any>(null);

const vehicleOptions = computed(() =>
  trackTargets.value.map(item => ({
    label: `${item.plateNo ?? '未建档'} (${item.deviceId})`,
    value: item.deviceId
  }))
);

const currentPoint = computed(() => points.value[cursor.value] ?? null);

onMounted(async () => {
  const targetsRequest = Promise.all([fetchVehicles(), fetchLiveStatus()]);
  if (mapContainer.value) {
    await init(mapContainer.value);
  }
  const [archived, observed] = await targetsRequest;
  trackTargets.value = mergeTrackTargets(archived.data ?? [], observed.data ?? []);
  const requestedDevice = typeof route.query.device === 'string' ? route.query.device : '';
  if (requestedDevice && trackTargets.value.some(item => item.deviceId === requestedDevice)) {
    deviceId.value = requestedDevice;
  } else if (trackTargets.value.length > 0) {
    deviceId.value = trackTargets.value[0].deviceId;
  }
});

onBeforeUnmount(stopPlayback);

watch(
  () => route.query.device,
  value => {
    const requestedDevice = typeof value === 'string' ? value : '';
    if (requestedDevice && trackTargets.value.some(item => item.deviceId === requestedDevice)) {
      deviceId.value = requestedDevice;
    }
  }
);

/** 后端按 device_time 过滤，而 device_time 是设备上报的无时区本地时间，这里必须用本地时间格式化 */
function toLocalIso(timestamp: number) {
  const date = new Date(timestamp);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

async function search() {
  if (!deviceId.value) {
    message.warning('请选择车辆');
    return;
  }
  stopPlayback();
  loading.value = true;

  const { data, error: queryError } = await fetchTrack(
    deviceId.value,
    toLocalIso(range.value[0]),
    toLocalIso(range.value[1])
  );
  loading.value = false;

  if (queryError || !data) {
    message.error(queryError?.message || '查询失败');
    return;
  }

  points.value = data.points;
  truncated.value = data.truncated;
  summary.value = {
    distanceKm: data.distanceKm,
    maxSpeedKph: data.maxSpeedKph,
    avgSpeedKph: data.avgSpeedKph
  };
  cursor.value = 0;

  if (data.points.length === 0) {
    message.info('该时间段没有轨迹数据');
    clearOverlays();
    return;
  }
  if (data.truncated) {
    message.warning('轨迹点数已达上限，仅显示前 20000 个点');
  }
  drawTrack();
}

function clearOverlays() {
  if (!map.value) return;
  if (polyline.value) {
    map.value.remove(polyline.value);
    polyline.value = null;
  }
  if (carMarker.value) {
    map.value.remove(carMarker.value);
    carMarker.value = null;
  }
}

function drawTrack() {
  if (!ready.value || !map.value) {
    return;
  }
  clearOverlays();
  const path = points.value.map(point => [point.gcjLng, point.gcjLat]);

  polyline.value = new AMap.value.Polyline({
    path,
    strokeColor: '#1890ff',
    strokeWeight: 5,
    strokeOpacity: 0.85,
    lineJoin: 'round',
    showDir: true
  });
  map.value.add(polyline.value);

  carMarker.value = new AMap.value.Marker({
    position: path[0],
    offset: new AMap.value.Pixel(-12, -12),
    content:
      '<div style="width:24px;height:24px;border-radius:50%;background:#f5222d;border:3px solid #fff;box-shadow:0 0 6px rgba(0,0,0,.5)"></div>'
  });
  map.value.add(carMarker.value);
  map.value.setFitView([polyline.value], false, [60, 60, 60, 60]);
}

function togglePlayback() {
  if (playing.value) {
    stopPlayback();
  } else {
    startPlayback();
  }
}

function startPlayback() {
  if (points.value.length < 2) {
    return;
  }
  if (cursor.value >= points.value.length - 1) {
    cursor.value = 0;
  }
  playing.value = true;
  timer = setInterval(() => {
    if (cursor.value >= points.value.length - 1) {
      stopPlayback();
      return;
    }
    cursor.value += 1;
    moveCarTo(cursor.value);
  }, 1000 / speedFactor.value);
}

function stopPlayback() {
  playing.value = false;
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function moveCarTo(index: number) {
  const point = points.value[index];
  if (point && carMarker.value) {
    carMarker.value.setPosition([point.gcjLng, point.gcjLat]);
    map.value?.setCenter([point.gcjLng, point.gcjLat]);
  }
}

function onSliderChange(value: number) {
  cursor.value = value;
  moveCarTo(value);
}
</script>

<template>
  <div class="h-full flex flex-col gap-12px">
    <NCard :bordered="false" size="small">
      <div class="flex flex-wrap items-center gap-12px">
        <NSelect
          v-model:value="deviceId"
          :options="vehicleOptions"
          placeholder="选择车辆"
          class="w-260px"
          filterable
        />
        <NDatePicker v-model:value="range" type="datetimerange" clearable class="w-400px" />
        <NButton type="primary" :loading="loading" @click="search">查询轨迹</NButton>

        <template v-if="summary">
          <NDivider vertical />
          <NStatistic label="里程" :value="summary.distanceKm" tabular-nums>
            <template #suffix><span class="text-12px">km</span></template>
          </NStatistic>
          <NStatistic label="最高速" :value="summary.maxSpeedKph" tabular-nums>
            <template #suffix><span class="text-12px">km/h</span></template>
          </NStatistic>
          <NStatistic label="平均速" :value="summary.avgSpeedKph" tabular-nums>
            <template #suffix><span class="text-12px">km/h</span></template>
          </NStatistic>
          <NStatistic label="轨迹点" :value="points.length" tabular-nums />
        </template>
      </div>
    </NCard>

    <NCard :bordered="false" class="flex-1" content-class="p-0! h-full">
      <div v-if="error" class="h-full flex-center flex-col gap-12px p-24px">
        <NAlert type="warning" title="地图不可用" class="max-w-480px">{{ error }}</NAlert>
      </div>
      <div v-else ref="mapContainer" class="h-full w-full"></div>
    </NCard>

    <NCard v-if="points.length > 1" :bordered="false" size="small">
      <div class="flex items-center gap-16px">
        <NButton circle :type="playing ? 'warning' : 'primary'" @click="togglePlayback">
          <span>{{ playing ? '⏸' : '▶' }}</span>
        </NButton>
        <NSlider
          :value="cursor"
          :min="0"
          :max="points.length - 1"
          :tooltip="false"
          class="flex-1"
          @update:value="onSliderChange"
        />
        <NSelect
          v-model:value="speedFactor"
          class="w-100px"
          size="small"
          :options="[
            { label: '1x', value: 1 },
            { label: '4x', value: 4 },
            { label: '8x', value: 8 },
            { label: '16x', value: 16 }
          ]"
          @update:value="() => { if (playing) { stopPlayback(); startPlayback(); } }"
        />
        <div class="w-330px text-13px text-gray-600">
          <span>{{ currentPoint?.deviceTime ?? '-' }}</span>
          <span class="ml-12px">{{ currentPoint?.speedKph?.toFixed(1) ?? '-' }} km/h</span>
          <span class="ml-12px">{{ cursor + 1 }} / {{ points.length }}</span>
        </div>
      </div>
    </NCard>
  </div>
</template>
