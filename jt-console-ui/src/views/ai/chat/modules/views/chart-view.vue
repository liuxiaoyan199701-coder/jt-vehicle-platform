<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useElementSize } from '@vueuse/core';
import { useEcharts } from '@/hooks/common/echarts';
import { buildChartOption, toChartSpec } from './chart-option';

/**
 * 对话里内嵌的统计图表。
 *
 * 这是**快照型**视图：数值由模型在事件里带来，没有可解引用的地址，前端无法重新取数。
 * 因此有两条不能省的约束——图下必须显示数据来源、不提供导出。
 *
 * 平台无法校验模型转述数值的真伪。来源脚注是给用户自行核对的抓手；不给导出，是不把可能出错的
 * 结论固化成可传播的文件。
 */
defineOptions({ name: 'ChartView' });

const props = defineProps<{
  params: Record<string, unknown>;
  mode?: 'inline' | 'panel';
}>();

const spec = computed(() => toChartSpec(props.params));
const { domRef, updateOptions } = useEcharts(() => ({}) as any);
const { width } = useElementSize(domRef);

function render() {
  const current = spec.value;
  if (!current) return;
  updateOptions(() => buildChartOption(current, width.value || 600) as any);
}

onMounted(render);
// 容器变宽变窄会影响标签是否需要旋转，所以宽度也要触发重算。
watch([spec, width], render);
</script>

<template>
  <div class="w-full" role="region" :aria-label="`统计图表：${params.title || ''}`">
    <NAlert v-if="!spec" type="warning" :bordered="false" class="text-13px">
      图表数据格式无法识别。
    </NAlert>
    <template v-else>
      <div ref="domRef" class="w-full" :class="mode === 'panel' ? 'h-full min-h-360px' : 'h-240px'" />
      <!--
        来源脚注是强制的：平台无法校验模型转述的数值，这是用户唯一能自行核对的线索。
        用 {{ }} 输出而不是交给渲染库，自动转义，少一个注入面。
      -->
      <p class="mt-2px text-11px text-gray-500 dark:text-gray-400">数据来源：{{ spec.source }}</p>
    </template>
  </div>
</template>
