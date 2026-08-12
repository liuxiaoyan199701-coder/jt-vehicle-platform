<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'FleetStatusChart' });

const props = defineProps<{
  moving: number;
  idle: number;
  offline: number;
}>();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: { trigger: 'item' },
  legend: {
    bottom: '2%',
    left: 'center',
    itemStyle: { borderWidth: 0 }
  },
  series: [
    {
      color: ['#f68057', '#4ade80', '#a1a1aa'],
      name: '车辆状态',
      type: 'pie',
      radius: ['45%', '72%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 10, borderColor: '#fff', borderWidth: 1 },
      label: { show: false, position: 'center' },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      labelLine: { show: false },
      data: [] as { name: string; value: number }[]
    }
  ]
}));

function render() {
  updateOptions(opts => {
    opts.series[0].data = [
      { name: '行驶中', value: props.moving },
      { name: '在线静止', value: props.idle },
      { name: '离线', value: props.offline }
    ];
    return opts;
  });
}

// 数据是异步拉回来的，首次渲染时通常还是 0，必须跟随 props 变化重绘
watch(() => [props.moving, props.idle, props.offline], render, { immediate: true });
</script>

<template>
  <NCard title="车辆状态分布" :bordered="false" size="small" class="card-wrapper h-full">
    <div ref="domRef" class="h-300px overflow-hidden"></div>
  </NCard>
</template>
