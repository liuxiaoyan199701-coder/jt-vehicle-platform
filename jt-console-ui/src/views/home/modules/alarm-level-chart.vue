<script setup lang="ts">
import { watch } from 'vue';
import type { AlarmLevelCount } from '@/service/api';
import { useEcharts } from '@/hooks/common/echarts';
import { alarmLevelLabel } from '@/utils/fleet-operations';

defineOptions({ name: 'AlarmLevelChart' });
const props = defineProps<{ items: AlarmLevelCount[] }>();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, left: 'center' },
  series: [{
    name: '未关闭告警',
    type: 'pie',
    radius: ['48%', '72%'],
    center: ['50%', '44%'],
    color: ['#d03050', '#f0a020', '#2080f0', '#909399'],
    label: { show: true, formatter: '{c}' },
    data: [] as { name: string; value: number }[]
  }]
}));

watch(
  () => props.items,
  items => {
    void updateOptions(options => {
      options.series[0].data = items.map(item => ({ name: alarmLevelLabel(item.level), value: item.count }));
      return options;
    });
  },
  { immediate: true, deep: true }
);
</script>

<template>
  <NCard title="未关闭告警分布" :bordered="false" size="small" class="h-full">
    <div ref="domRef" class="h-300px w-full"></div>
  </NCard>
</template>
