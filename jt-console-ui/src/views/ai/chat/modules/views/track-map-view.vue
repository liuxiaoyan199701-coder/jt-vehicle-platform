<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useInlineMap } from '@/hooks/use-inline-map';
import { fetchTrack } from '@/service/api/console';
import type { TrackPoint } from '@/service/api/console';

/**
 * 对话里内嵌的行驶轨迹地图。
 *
 * 引用型：事件只带设备号与时间窗，完整轨迹由**这里凭用户自己的令牌**去既有接口取——
 * 那边能拿到全部点，而模型手上只有 40 个抽样点，画出来是折的。
 *
 * 内联时只画折线与起终点，**不带回放控制**：控制条里有定宽的倍速选择与信息块，
 * 在气泡宽度里放不下。回放留给放大后的面板。
 */
defineOptions({ name: 'TrackMapView' });

const props = defineProps<{
  params: Record<string, unknown>;
  mode?: 'inline' | 'panel';
}>();

const text = (key: string) => {
  const raw = props.params[key];
  return typeof raw === 'string' ? raw.trim() : '';
};

const points = ref<TrackPoint[]>([]);
const summary = ref('');
const loading = ref(true);
const loadError = ref('');
const cursor = ref(0);
const playing = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

const { containerRef, mount, map, AMap, ready, error, fitView } = useInlineMap({ zoom: 13 });
const carMarker = ref<any>(null);

const canPlay = computed(() => props.mode === 'panel' && points.value.length > 1);

async function load() {
  loading.value = true;
  loadError.value = '';
  const deviceId = text('deviceId');
  // 接口按设备上报的无时区本地时间过滤，格式必须与入库一致，不能用 toISOString。
  const { data, error: failure } = await fetchTrack(deviceId, text('start'), text('end'));
  loading.value = false;
  if (failure || !data) {
    loadError.value = '无法获取轨迹';
    return;
  }
  points.value = data.points ?? [];
  if (!points.value.length) {
    loadError.value = '这段时间没有轨迹点';
    return;
  }
  const parts = [`${data.count} 个点`];
  if (data.distanceKm != null) parts.push(`${data.distanceKm.toFixed(1)} km`);
  if (data.maxSpeedKph != null) parts.push(`最高 ${data.maxSpeedKph.toFixed(0)} km/h`);
  if (data.truncated) parts.push('（已截断）');
  summary.value = parts.join(' · ');
}

/** 必须用 gcj 坐标——直接拿原始经纬度打到高德上会偏出几百米。 */
function path() {
  return points.value
    .filter(p => p.gcjLng != null && p.gcjLat != null)
    .map(p => [p.gcjLng, p.gcjLat] as [number, number]);
}

function endpointMarker(position: [number, number], color: string, label: string) {
  return new AMap.value.Marker({
    position,
    offset: new AMap.value.Pixel(-7, -7),
    content:
      `<div title="${label}" style="width:14px;height:14px;border-radius:50%;background:${color};` +
      `border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.4);"></div>`,
    zIndex: 130
  });
}

function draw() {
  if (!ready.value || !map.value || !AMap.value) return;
  const line = path();
  if (line.length < 2) return;
  map.value.clearMap();

  const polyline = new AMap.value.Polyline({
    path: line,
    strokeColor: '#1890ff',
    strokeWeight: 4,
    strokeOpacity: 0.85,
    lineJoin: 'round',
    showDir: true
  });
  polyline.setMap(map.value);
  endpointMarker(line[0], '#18a058', '起点').setMap(map.value);
  endpointMarker(line[line.length - 1], '#d03050', '终点').setMap(map.value);

  if (canPlay.value) {
    carMarker.value = new AMap.value.Marker({
      position: line[0],
      offset: new AMap.value.Pixel(-8, -8),
      content:
        '<div style="width:16px;height:16px;border-radius:50%;background:#f5a623;' +
        'border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.5);"></div>',
      zIndex: 140
    });
    carMarker.value.setMap(map.value);
  }
  fitView([polyline]);
}

function seek(index: number) {
  const line = path();
  if (!carMarker.value || !line[index]) return;
  cursor.value = index;
  carMarker.value.setPosition(line[index]);
}

function togglePlay() {
  if (playing.value) {
    stopPlayback();
    return;
  }
  const line = path();
  if (cursor.value >= line.length - 1) cursor.value = 0;
  playing.value = true;
  timer = setInterval(() => {
    if (cursor.value >= line.length - 1) {
      stopPlayback();
      return;
    }
    seek(cursor.value + 1);
  }, 200);
}

function stopPlayback() {
  playing.value = false;
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

onMounted(async () => {
  await Promise.all([load(), mount()]);
  draw();
});

watch([ready, points], draw);
// 组件销毁时地图由 hook 负责，但定时器得自己停——否则它会一直指着已经没了的标记。
watch(() => props.mode, stopPlayback);
onBeforeUnmount(stopPlayback);
</script>

<template>
  <div class="w-full" role="region" :aria-label="`行驶轨迹地图：${summary || '加载中'}`">
    <div
      class="relative w-full overflow-hidden rd-6px bg-gray-100 dark:bg-dark-600"
      :class="mode === 'panel' ? 'h-full min-h-320px' : 'h-260px'"
    >
      <div ref="containerRef" class="h-full w-full" />
      <div
        v-if="error || loadError"
        class="absolute inset-0 flex-col-center gap-4px bg-gray-100 px-12px text-center text-13px text-gray-600 dark:bg-dark-600 dark:text-gray-300"
      >
        <SvgIcon icon="mdi:map-marker-path" class="text-24px" />
        <span>{{ error || loadError }}</span>
      </div>
      <div v-else-if="loading" class="absolute inset-0 flex-center">
        <NSpin size="small" />
      </div>
    </div>

    <!-- 回放只在面板里出现：控制条在气泡宽度里放不下 -->
    <div v-if="canPlay" class="mt-6px flex items-center gap-8px">
      <NButton size="small" circle :aria-label="playing ? '暂停回放' : '开始回放'" @click="togglePlay">
        <template #icon>
          <SvgIcon :icon="playing ? 'mdi:pause' : 'mdi:play'" />
        </template>
      </NButton>
      <NSlider
        :value="cursor"
        :min="0"
        :max="Math.max(0, points.length - 1)"
        :tooltip="false"
        class="flex-1"
        @update:value="seek"
      />
      <span class="w-72px text-right text-12px text-gray-600 dark:text-gray-300">
        {{ cursor + 1 }} / {{ points.length }}
      </span>
    </div>
    <p v-if="summary" class="mt-4px text-12px text-gray-600 dark:text-gray-300">{{ summary }}</p>
  </div>
</template>
