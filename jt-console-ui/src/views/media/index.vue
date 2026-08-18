<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { NButton, NEmpty, NSpin, NTag, useMessage } from 'naive-ui';
import { useRoute } from 'vue-router';
import type { MediaFileItem, Vehicle } from '@/service/api/console';
import { fetchMedia, fetchVehicles } from '@/service/api/console';
import { useAMap } from '@/hooks/use-amap';

/**
 * 多媒体：终端上传的抓拍照片。
 *
 * <p>此前这些照片只能在「监控页 → 指令面板 → 拍照」标签里看到，且只看得到当前选中那一台车最近的
 * 二十张。告警触发的抓拍一旦刷过去就再也找不回来，而那恰恰是事后要看的那一张。
 *
 * <p>位置是 v7 之后才落库的，历史照片没有坐标——所以地图是按需出现的，不是恒定占一块地方。
 */
defineOptions({ name: 'MediaPage' });

const message = useMessage();
const route = useRoute();

const loading = ref(false);
const items = ref<MediaFileItem[]>([]);
const total = ref(0);
const vehicles = ref<Vehicle[]>([]);
const preview = ref<MediaFileItem | null>(null);

const filters = reactive({
  deviceId: (route.query.device as string) || null,
  channelId: null as number | null,
  trigger: null as 'manual' | 'alarm' | null,
  range: null as [number, number] | null,
  page: 1,
  pageSize: 24
});

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

const triggerOptions = [
  { label: '指令或定时', value: 'manual' },
  { label: '报警触发', value: 'alarm' }
];

const pagination = computed(() => ({
  page: filters.page,
  pageSize: filters.pageSize,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [12, 24, 48],
  prefix: ({ itemCount }: { itemCount?: number }) => `共 ${itemCount ?? 0} 张`,
  onUpdatePage: (page: number) => {
    filters.page = page;
    void load();
  },
  onUpdatePageSize: (pageSize: number) => {
    filters.pageSize = pageSize;
    filters.page = 1;
    void load();
  }
}));

function toIso(value: number) {
  // 后端按东八区解释不带偏移的时间，这里就给不带偏移的本地串，避免来回换算。
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchMedia({
      deviceId: filters.deviceId ?? undefined,
      channelId: filters.channelId ?? undefined,
      trigger: filters.trigger ?? undefined,
      start: filters.range ? toIso(filters.range[0]) : undefined,
      end: filters.range ? toIso(filters.range[1]) : undefined,
      page: filters.page,
      pageSize: filters.pageSize
    });
    items.value = data?.items ?? [];
    total.value = data?.total ?? 0;
  } finally {
    loading.value = false;
  }
}

function search() {
  filters.page = 1;
  void load();
}

function reset() {
  filters.deviceId = null;
  filters.channelId = null;
  filters.trigger = null;
  filters.range = null;
  search();
}

/**
 * 大图弹窗里的定位地图。只有带坐标的照片才建实例。
 *
 * <p>弹窗打开的瞬间容器还没有尺寸，此时建地图会得到一个一像素宽的图。所以要等 DOM 落定
 * 再 init——`useInlineMap` 正是为这个场景写的，但它按容器尺寸自动挂载，更适合常驻容器；
 * 这里容器随弹窗生灭，显式 init/destroy 更直白。
 */
const mapContainer = ref<HTMLElement | null>(null);
const { map, AMap, ready, init, destroy } = useAMap({ controls: false, fitPadding: 30 });

async function openPreview(item: MediaFileItem) {
  preview.value = item;
  if (item.gcjLat == null || item.gcjLng == null) return;
  await nextTick();
  if (!mapContainer.value) return;
  try {
    await init(mapContainer.value);
    if (!ready.value || !map.value || !AMap.value) return;
    // 必须用 GCJ-02：高德底图是 GCJ 坐标系，拿 WGS-84 原值画会偏几百米。
    const position: [number, number] = [item.gcjLng!, item.gcjLat!];
    map.value.add(new AMap.value.Marker({ position, anchor: 'bottom-center' }));
    map.value.setCenter(position);
    map.value.setZoom(16);
  } catch {
    message.warning('地图加载失败，位置信息仍可在下方查看');
  }
}

function closePreview() {
  preview.value = null;
  // 弹窗关闭即销毁：地图实例会一直占着 GPU 与内存，连开几十张照片会明显拖慢页面。
  destroy();
}

function shortTime(value: string) {
  return value.replace('T', ' ').slice(0, 19);
}

onMounted(async () => {
  const result = await fetchVehicles();
  vehicles.value = result.data ?? [];
  await load();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px">
    <NCard :bordered="false" size="small">
      <div class="flex flex-wrap items-center gap-12px">
        <NSelect
          v-model:value="filters.deviceId"
          :options="vehicleOptions"
          filterable
          clearable
          placeholder="车辆（不选则查全部）"
          class="w-240px"
        />
        <NInputNumber
          v-model:value="filters.channelId"
          :min="1"
          :max="255"
          clearable
          placeholder="通道"
          class="w-120px"
        />
        <NSelect
          v-model:value="filters.trigger"
          :options="triggerOptions"
          clearable
          placeholder="触发方式"
          class="w-150px"
        />
        <NDatePicker v-model:value="filters.range" type="datetimerange" clearable class="w-380px" />
        <NButton type="primary" @click="search">查询</NButton>
        <NButton @click="reset">重置</NButton>
      </div>
    </NCard>

    <NCard title="抓拍照片" :bordered="false" size="small" class="flex-1">
      <NSpin :show="loading">
        <div v-if="items.length" class="grid gap-12px" style="grid-template-columns: repeat(auto-fill, minmax(200px, 1fr))">
          <div
            v-for="item in items"
            :key="item.id"
            class="group cursor-pointer overflow-hidden rounded border border-gray-200 transition-colors hover:border-primary dark:border-gray-700"
            @click="openPreview(item)"
          >
            <!-- accessAddress 为空是「网关未配置访问地址」，给出说明而不是一张裂图 -->
            <img
              v-if="item.accessAddress"
              :src="item.accessAddress"
              :alt="`${item.deviceId} 于 ${shortTime(item.capturedAt)} 的抓拍`"
              class="aspect-video w-full bg-gray-100 object-cover dark:bg-gray-800"
              loading="lazy"
            />
            <div v-else class="aspect-video w-full flex-center bg-gray-100 text-12px text-gray-500 dark:bg-gray-800">
              未配置访问地址
            </div>
            <div class="p-8px">
              <p class="truncate text-13px font-medium">{{ item.deviceId }}</p>
              <p class="mt-2px truncate text-11px text-gray-500 dark:text-gray-400">
                {{ shortTime(item.capturedAt) }}
                <span v-if="item.channelId"> · 通道{{ item.channelId }}</span>
              </p>
              <div class="mt-4px flex items-center gap-4px">
                <NTag v-if="(item.eventCode ?? 0) >= 2" type="warning" size="tiny" :bordered="false">报警触发</NTag>
                <NTag v-if="item.gcjLat != null" type="success" size="tiny" :bordered="false">有位置</NTag>
              </div>
            </div>
          </div>
        </div>
        <NEmpty v-else-if="!loading" description="没有符合条件的抓拍照片" class="py-48px" />
      </NSpin>

      <div v-if="total > 0" class="mt-16px flex justify-end">
        <NPagination v-bind="pagination" />
      </div>
    </NCard>

    <NModal
      :show="Boolean(preview)"
      preset="card"
      style="max-width: 900px"
      :title="preview ? `${preview.deviceId} · ${shortTime(preview.capturedAt)}` : ''"
      @update:show="closePreview"
    >
      <div v-if="preview" class="flex flex-col gap-12px">
        <img
          v-if="preview.accessAddress"
          :src="preview.accessAddress"
          :alt="`${preview.deviceId} 的抓拍大图`"
          class="max-h-60vh w-full rounded object-contain"
        />
        <!-- 有坐标才画地图：v7 之前的历史照片没有位置，恒定占一块空地图既误导又浪费 -->
        <div v-if="preview.gcjLat != null" ref="mapContainer" class="h-220px w-full rounded" />
        <p v-else class="text-12px text-gray-500">该照片没有位置信息（设备拍摄时未定位，或早于平台记录位置的版本）。</p>
        <div class="text-12px text-gray-600 dark:text-gray-300">
          通道 {{ preview.channelId ?? '—' }} ·
          {{ (preview.eventCode ?? 0) >= 2 ? '报警触发' : '指令或定时' }} ·
          {{ preview.fileFormat ?? preview.fileType }}
        </div>
      </div>
    </NModal>
  </div>
</template>
