<script setup lang="ts">
import { NVirtualList } from 'naive-ui';
import type { DeviceRow } from '@/utils/device-store';
import DeviceRowItem from './device-row.vue';

defineOptions({ name: 'MonitorDeviceList' });

/**
 * 设备列表：搜索、统计、降级提示，以及虚拟滚动的车辆行。
 *
 * 渲染量只与可视区域有关，与设备总数无关——一万台设备在 DOM 里仍然只有二十来行。
 * 行高必须固定，否则虚拟列表要为每一行测量高度，省下的开销又还回去了；
 * 因此行内的告警只展示首条加计数，不换行堆叠。
 */
const ROW_HEIGHT = 56;

defineProps<{
  rows: DeviceRow[];
  total: number;
  onlineCount: number;
  selectedId: string;
  keyword: string;
  connectionLabel: string;
  connectionTagType: 'success' | 'warning' | 'default';
  degraded: boolean;
  calibrating: boolean;
  calibrationError: string;
  lastCalibratedAt: string | null;
  markerCapped: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:keyword', value: string): void;
  (event: 'retry'): void;
  (event: 'select' | 'video' | 'profile' | 'command', row: DeviceRow): void;
}>();

function formatTime(value: string | null) {
  return value ? value.replace('T', ' ').replace('Z', '') : '-';
}
</script>

<template>
  <NCard :bordered="false" class="w-320px flex-shrink-0" content-class="flex flex-col h-full p-0!">
    <template #header>
      <div class="flex items-center justify-between">
        <span>车辆列表</span>
        <NTag :type="connectionTagType" size="small" round>
          {{ connectionLabel }}
        </NTag>
      </div>
    </template>

    <div class="px-12px pb-8px">
      <NInput
        :value="keyword"
        placeholder="搜索车牌或终端号"
        clearable
        size="small"
        @update:value="emit('update:keyword', $event ?? '')"
      />
      <div class="mt-8px text-12px text-gray-500">
        共 {{ total }} 台，在线 <span class="text-success font-bold">{{ onlineCount }}</span> 台
        <span v-if="keyword.trim()"> · 匹配 {{ rows.length }} 台</span>
      </div>
      <NAlert v-if="degraded" type="warning" :bordered="false" class="mt-8px text-12px">
        {{ calibrationError }}，当前展示最近一次成功数据。
        <NButton text type="primary" size="tiny" :loading="calibrating" @click="emit('retry')">
          立即重试
        </NButton>
      </NAlert>
      <div v-else-if="lastCalibratedAt" class="mt-4px text-11px text-gray-400">
        最近校准：{{ formatTime(lastCalibratedAt) }}
      </div>
      <div v-if="markerCapped" class="mt-4px text-11px text-warning">
        当前视野内车辆过多，仅绘制部分标记，请放大查看。
      </div>
    </div>

    <div class="min-h-0 flex-1">
      <NSpin v-if="calibrating && total === 0" class="mt-40px" />
      <NEmpty
        v-else-if="rows.length === 0 && !calibrationError"
        :description="keyword.trim() ? '没有匹配的车辆' : '暂无车辆'"
        class="mt-40px"
      />
      <NVirtualList
        v-else
        :items="rows"
        :item-size="ROW_HEIGHT"
        key-field="deviceId"
        class="h-full"
      >
        <template #default="{ item }">
          <div :style="{ height: `${ROW_HEIGHT}px` }">
            <DeviceRowItem
              :row="item as DeviceRow"
              :selected="selectedId === (item as DeviceRow).deviceId"
              @select="emit('select', $event)"
              @video="emit('video', $event)"
              @profile="emit('profile', $event)"
              @command="emit('command', $event)"
            />
          </div>
        </template>
      </NVirtualList>
    </div>
  </NCard>
</template>
