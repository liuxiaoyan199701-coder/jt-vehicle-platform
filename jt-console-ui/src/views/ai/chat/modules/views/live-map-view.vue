<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useInlineMap } from '@/hooks/use-inline-map';
import { fetchLiveStatus } from '@/service/api/console';
import { escapeHtmlText } from '@/utils/html';
import type { LiveStatus } from '@/service/api/console';

/**
 * 对话里内嵌的实时位置地图。
 *
 * 数据不由事件携带，而是**这里凭当前用户自己的令牌**去调既有接口——与动作卡片同一套安全模型：
 * 权限、数据范围与审计全部沿用既有通道，AI 侧没有任何特权，也无法指定调用哪个地址。
 */
defineOptions({ name: 'LiveMapView' });

const props = defineProps<{
  params: Record<string, unknown>;
  /** 内联时压扁高度、去掉次要信息；面板里放全。 */
  mode?: 'inline' | 'panel';
}>();

const deviceId = computed(() => {
  const raw = props.params.deviceId;
  return typeof raw === 'string' && raw.trim() ? raw.trim() : null;
});

const vehicles = ref<LiveStatus[]>([]);
const loading = ref(true);
const loadError = ref('');
const { containerRef, mount, map, AMap, ready, error, fitView } = useInlineMap({ zoom: 14 });

const summary = computed(() => {
  if (!vehicles.value.length) return '';
  if (vehicles.value.length === 1) {
    const only = vehicles.value[0];
    const who = only.plateNo || only.deviceId;
    if (only.online) {
      const speed = only.speedKph == null ? '' : ` · ${only.speedKph.toFixed(0)} km/h`;
      return `${who} · 在线${speed}`;
    }
    // 离线车标的是最后一次上报的位置。把当时的速度说成「现在 60 km/h」会让人以为车在跑。
    const at = only.deviceTime ? ` · 最后上报 ${only.deviceTime.replace('T', ' ').slice(0, 16)}` : '';
    return `${who} · 离线${at}`;
  }
  const online = vehicles.value.filter(v => v.online).length;
  return `${vehicles.value.length} 台车 · ${online} 台在线`;
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const { data, error: failure } = await fetchLiveStatus();
    if (failure || !data) {
      loadError.value = '无法获取实时位置';
      return;
    }
    // 事件只给设备号，筛选在前端做：接口本身已经按数据范围过滤过了。
    vehicles.value = deviceId.value ? data.filter(v => v.deviceId === deviceId.value) : data;
    if (!vehicles.value.length) {
      loadError.value = deviceId.value ? '这台车暂无位置数据' : '当前没有车辆上报位置';
    } else if (!vehicles.value.some(v => v.gcjLat != null && v.gcjLng != null)) {
      // 车在档案里、也有状态记录，但从未上报过有效定位——空白地图不说话最让人困惑。
      loadError.value = '这台车还没有上报过有效定位';
    }
  } catch {
    loadError.value = '无法获取实时位置';
  } finally {
    loading.value = false;
  }
}

/**
 * 定位针的 HTML。
 *
 * 用针形而不是监控页那种方向箭头：那里是车队地图，要看的是「往哪开」；这里回答的是
 * 「在哪」，针尖指向具体位置才是对的语义。
 *
 * 离线不能只靠调浅颜色来表达——浅灰在浅色底图上几乎看不见，用户会以为地图上什么都没有。
 * 所以离线用的是**足够深的灰**，白描边与投影两种模式下都保证轮廓，状态改由车牌标签的
 * 底色和下方文案表达。
 */
function pinContent(vehicle: LiveStatus, withLabel: boolean) {
  const color = vehicle.online ? '#18a058' : '#6b7280';
  const label = escapeHtmlText(vehicle.plateNo ?? vehicle.deviceId);
  // 车牌可能是用户填的自由文本，必须转义——它会直接进 innerHTML。
  const chip = withLabel
    ? `<div style="margin-top:1px;padding:1px 6px;background:${color};color:#fff;border-radius:3px;
         font-size:11px;line-height:16px;white-space:nowrap;
         box-shadow:0 1px 3px rgba(0,0,0,.3);">${label}</div>`
    : '';
  return `
    <div style="display:flex;flex-direction:column;align-items:center;pointer-events:none;">
      <svg width="26" height="34" viewBox="0 0 26 34" style="filter:drop-shadow(0 2px 3px rgba(0,0,0,.35));">
        <path d="M13 1C6.9 1 2 5.9 2 12c0 8 11 21 11 21s11-13 11-21c0-6.1-4.9-11-11-11z"
              fill="${color}" stroke="#fff" stroke-width="2"/>
        <circle cx="13" cy="12" r="4.2" fill="#fff"/>
      </svg>
      ${chip}
    </div>`;
}

/** 画点。必须用 gcj 坐标——直接拿原始经纬度打到高德上会偏出几百米。 */
function draw() {
  if (!ready.value || !map.value || !AMap.value) return;
  map.value.clearMap();
  const positioned = vehicles.value.filter(v => v.gcjLng != null && v.gcjLat != null);
  // 车多时不挂车牌，否则标签互相压成一团反而谁都读不出来。
  const withLabel = positioned.length <= 6;
  const markers: any[] = [];
  for (const vehicle of positioned) {
    const marker = new AMap.value.Marker({
      position: [vehicle.gcjLng, vehicle.gcjLat],
      // 针尖在底部中央，锚点要落到针尖而不是图形中心，否则标的是针的腰。
      offset: new AMap.value.Pixel(-13, -34),
      content: pinContent(vehicle, withLabel),
      zIndex: 120
    });
    marker.setMap(map.value);
    markers.push(marker);
  }
  if (markers.length === 1) {
    map.value.setZoomAndCenter(15, markers[0].getPosition());
  } else if (markers.length > 1) {
    fitView(markers);
  }
}

onMounted(async () => {
  await Promise.all([load(), mount()]);
  draw();
});

watch([ready, vehicles], draw);
</script>

<template>
  <div class="w-full" role="region" :aria-label="`实时位置地图：${summary || '加载中'}`">
    <div
      class="relative w-full overflow-hidden rd-6px bg-gray-100 dark:bg-dark-600"
      :class="mode === 'panel' ? 'h-full min-h-320px' : 'h-200px'"
    >
      <div ref="containerRef" class="h-full w-full" />
      <!-- 地图不可用与取数失败分开提示：前者要去配密钥，后者是数据问题，方向完全不同 -->
      <div
        v-if="error || loadError"
        class="absolute inset-0 flex-col-center gap-4px bg-gray-100 px-12px text-center text-13px text-gray-600 dark:bg-dark-600 dark:text-gray-300"
      >
        <SvgIcon icon="mdi:map-marker-off-outline" class="text-24px" />
        <span>{{ error || loadError }}</span>
      </div>
      <div v-else-if="loading" class="absolute inset-0 flex-center">
        <NSpin size="small" />
      </div>
    </div>
    <p v-if="summary" class="mt-4px text-12px text-gray-600 dark:text-gray-300">{{ summary }}</p>
  </div>
</template>
