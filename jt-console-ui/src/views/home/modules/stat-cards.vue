<script setup lang="ts">
import { computed } from 'vue';
import type { FleetSummary } from '@/service/api';

defineOptions({ name: 'StatCards' });

const props = defineProps<{ summary: FleetSummary }>();

const cards = computed(() => [
  { key: 'fleet', label: '已建档车辆', value: props.summary.fleetVehicles, suffix: '台', icon: 'lucide:car-front', tone: 'primary' },
  { key: 'online', label: '在线 / 行驶', value: `${props.summary.online} / ${props.summary.moving}`, suffix: '台', icon: 'lucide:radio-tower', tone: 'success' },
  { key: 'idle', label: '静止 / 离线', value: `${props.summary.idle} / ${props.summary.offline}`, suffix: '台', icon: 'lucide:circle-pause', tone: 'neutral' },
  { key: 'unknown', label: '未建档在线', value: props.summary.unknownOnline, suffix: '台', icon: 'lucide:circle-help', tone: 'info' },
  { key: 'alarms', label: '未关闭告警', value: props.summary.openAlarms, suffix: '条', icon: 'lucide:triangle-alert', tone: props.summary.criticalOpenAlarms ? 'danger' : 'warning', detail: `严重 ${props.summary.criticalOpenAlarms} 条` },
  { key: 'distance', label: '今日里程', value: props.summary.todayDistanceKm.toFixed(1), suffix: 'km', icon: 'lucide:gauge', tone: 'primary' }
]);
</script>

<template>
  <div class="metric-grid">
    <div v-for="item in cards" :key="item.key" class="metric-item" :data-tone="item.tone">
      <div class="metric-icon"><SvgIcon :icon="item.icon" /></div>
      <div class="min-w-0">
        <div class="metric-label">{{ item.label }}</div>
        <div class="flex flex-wrap items-baseline gap-4px">
          <span class="metric-value">{{ item.value }}</span>
          <span class="metric-suffix">{{ item.suffix }}</span>
        </div>
        <div v-if="item.detail" class="text-11px text-gray-500">{{ item.detail }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.metric-item {
  display: flex;
  min-width: 0;
  min-height: 92px;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgb(224 224 230);
  border-radius: 6px;
  background: var(--n-color, #fff);
}

.metric-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 6px;
  background: rgb(24 160 88 / 10%);
  color: #18a058;
  font-size: 20px;
}

.metric-item[data-tone='danger'] .metric-icon { background: rgb(208 48 80 / 10%); color: #d03050; }
.metric-item[data-tone='warning'] .metric-icon { background: rgb(240 160 32 / 12%); color: #d9820c; }
.metric-item[data-tone='info'] .metric-icon { background: rgb(32 128 240 / 10%); color: #2080f0; }
.metric-item[data-tone='neutral'] .metric-icon { background: rgb(118 124 130 / 10%); color: #767c82; }
.metric-label { overflow: hidden; color: #666; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.metric-value { color: var(--n-text-color, #222); font-size: 24px; font-weight: 600; line-height: 1.3; }
.metric-suffix { color: #999; font-size: 12px; }

@media (max-width: 1280px) { .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 640px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .metric-item { min-height: 84px; padding: 10px; } .metric-value { font-size: 20px; } }
</style>
