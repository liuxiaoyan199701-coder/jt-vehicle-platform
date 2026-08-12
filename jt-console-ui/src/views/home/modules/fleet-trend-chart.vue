<script setup lang="ts">
import { watch } from 'vue';
import type { FleetDailyTrend } from '@/service/api';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'FleetTrendChart' });
const props = defineProps<{ items: FleetDailyTrend[] }>();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: { trigger: 'axis' },
  legend: { top: 0, right: 0 },
  grid: { left: 48, right: 44, top: 44, bottom: 30 },
  xAxis: { type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: [
    { type: 'value', name: 'km', minInterval: 1 },
    { type: 'value', name: '台 / 条', minInterval: 1 }
  ],
  series: [
    { name: '里程', type: 'line', smooth: true, symbolSize: 6, areaStyle: { opacity: 0.08 }, data: [] as number[] },
    { name: '活跃车辆', type: 'line', smooth: true, yAxisIndex: 1, data: [] as number[] },
    { name: '新增告警', type: 'bar', yAxisIndex: 1, barMaxWidth: 20, data: [] as number[] }
  ]
}));

watch(
  () => props.items,
  items => {
    void updateOptions(options => {
      options.xAxis.data = items.map(item => item.date.slice(5));
      options.series[0].data = items.map(item => item.distanceKm);
      options.series[1].data = items.map(item => item.activeVehicles);
      options.series[2].data = items.map(item => item.newAlarms);
      return options;
    });
  },
  { immediate: true, deep: true }
);
</script>

<template>
  <NCard title="近七日运营趋势" :bordered="false" size="small" class="h-full">
    <div ref="domRef" class="h-300px w-full"></div>
  </NCard>
</template>
