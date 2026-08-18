<script setup lang="ts">
import { computed } from 'vue';
import type { AiViewEvent } from '@/service/ai-stream';
import { lookupView } from './view-registry';

/**
 * 放大区域。**单实例**——同时只承载一个视图。
 *
 * 它不是富内容的默认归宿（那是气泡本身），只承载用户主动放大的那一个。这样不打开时对话区是
 * 满宽的，也让地图、图表、视频共用同一个放大出口，不必为每类视图各设计一套展开方式。
 *
 * <p>用 `v-if` + `:key` 而不是 `v-show` 缓存：地图实例与播放器都占着 GPU 与连接，
 * 留着「切回来更快」远不值得。带 key 还能保证切换时**先卸载后挂载**，不会出现两个实例并存。
 */
defineOptions({ name: 'ViewPanel' });

const props = defineProps<{ view: AiViewEvent | null }>();
const emit = defineEmits<{ close: [] }>();

const definition = computed(() => (props.view ? lookupView(props.view.type) : null));
</script>

<template>
  <NCard
    v-if="view && definition"
    :bordered="false"
    size="small"
    class="w-420px flex-shrink-0 card-wrapper"
    content-class="flex-col-stretch overflow-hidden"
  >
    <template #header>
      <span class="truncate text-14px">{{ view.title || definition.label }}</span>
    </template>
    <template #header-extra>
      <NButton size="tiny" quaternary aria-label="关闭放大区域" @click="emit('close')">
        <template #icon>
          <SvgIcon icon="mdi:close" />
        </template>
      </NButton>
    </template>
    <div class="flex-1 overflow-y-auto">
      <component :is="definition.component" :key="view.viewId" :params="view.params" mode="panel" />
    </div>
  </NCard>
</template>
