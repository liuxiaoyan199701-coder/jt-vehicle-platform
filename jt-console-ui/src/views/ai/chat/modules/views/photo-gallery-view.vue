<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { NEmpty, NSpin, NTag } from 'naive-ui';
import type { MediaFileItem } from '@/service/api/console';
import { fetchMedia } from '@/service/api/console';

/**
 * 对话里的抓拍照片墙。
 *
 * <p>引用型视图：事件里只带设备号与时间窗，照片由这里拿**用户自己的令牌**去取。
 * 图片本身绝不进事件——既撑爆对话留痕，也让权限校验形同虚设。
 *
 * <p>照片内容的文字描述由服务端在工具结果里给出，已经在气泡的文字答案里了，这里不重复呈现。
 * 这也是「不让用户感知到两个模型」的一部分：用户看到的是「答案 + 照片」，不是
 * 「答案 + 识别报告 + 照片」。
 */
defineOptions({ name: 'PhotoGalleryView' });

const props = defineProps<{
  params: Record<string, unknown>;
  mode: 'inline' | 'panel';
}>();

const loading = ref(true);
const items = ref<MediaFileItem[]>([]);
const failed = ref(false);

/** 内联时只放一行，面板里放开。列表本身在服务端已按时间倒序。 */
const INLINE_LIMIT = 6;

onMounted(async () => {
  try {
    const { data } = await fetchMedia({
      deviceId: String(props.params.deviceId ?? ''),
      start: props.params.start ? String(props.params.start) : undefined,
      end: props.params.end ? String(props.params.end) : undefined,
      channelId: props.params.channel ? Number(props.params.channel) : undefined,
      page: 1,
      pageSize: props.mode === 'inline' ? INLINE_LIMIT : 24
    });
    items.value = data?.items ?? [];
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
});

function shortTime(value: string) {
  return value.replace('T', ' ').slice(5, 16);
}
</script>

<template>
  <div
    class="rounded bg-gray-50 p-8px dark:bg-gray-800/40"
    role="region"
    :aria-label="`${params.deviceId} 的抓拍照片`"
  >
    <NSpin :show="loading" :size="20">
      <div v-if="failed" class="py-16px text-center text-13px text-gray-500">照片加载失败</div>
      <div
        v-else-if="items.length"
        class="grid gap-6px"
        :style="{
          gridTemplateColumns: mode === 'inline' ? 'repeat(auto-fill, minmax(110px, 1fr))' : 'repeat(auto-fill, minmax(180px, 1fr))'
        }"
      >
        <a
          v-for="item in items"
          :key="item.id"
          :href="item.accessAddress ?? undefined"
          target="_blank"
          rel="noopener noreferrer"
          class="group block overflow-hidden rounded"
          :class="item.accessAddress ? 'cursor-pointer' : 'cursor-default'"
        >
          <img
            v-if="item.accessAddress"
            :src="item.accessAddress"
            :alt="`${item.deviceId} 于 ${shortTime(item.capturedAt)} 的抓拍`"
            class="aspect-video w-full bg-gray-200 object-cover transition-opacity group-hover:opacity-85 dark:bg-gray-700"
            loading="lazy"
          />
          <div
            v-else
            class="aspect-video w-full flex-center bg-gray-200 text-11px text-gray-500 dark:bg-gray-700"
          >
            无访问地址
          </div>
          <div class="mt-2px flex items-center gap-4px px-2px">
            <span class="truncate text-11px text-gray-600 dark:text-gray-300">
              {{ shortTime(item.capturedAt) }}
            </span>
            <NTag v-if="(item.eventCode ?? 0) >= 2" type="warning" size="tiny" :bordered="false">警</NTag>
          </div>
        </a>
      </div>
      <NEmpty v-else-if="!loading" description="这段时间没有抓拍" size="small" class="py-12px" />
    </NSpin>
  </div>
</template>
