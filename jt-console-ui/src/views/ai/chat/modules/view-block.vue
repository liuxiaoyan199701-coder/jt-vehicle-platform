<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useAppStore } from '@/store/modules/app';
import { useRouter } from 'vue-router';
import type { AiViewEvent } from '@/service/ai-stream';
import { lookupView } from './view-registry';

/**
 * 对话里的一块视图。
 *
 * <p>三件事：按前端白名单查出该渲染哪个组件、**渲染前再判一次权限**、以及决定直接渲染还是
 * 先给一张卡片。
 *
 * <p>权限要在这里判而不只在服务端判，是因为历史还原的窗口是无限大的：几个月前推的视图，
 * 今天这个账号可能已经没有对应权限了。判不过就直接说明，而不是让组件去调接口吃一个拒绝——
 * 那样用户看到的是一块转圈后变红的区域，不知道是自己没权限还是平台坏了。
 *
 * <p>直接渲染还是先给卡片，分界不是「轻 / 重」，而是**有没有真实世界的副作用**：只读查询直接
 * 渲染，而视频开流会通过网关向路上那台车下发指令，那种事必须由人点一下。
 */
defineOptions({ name: 'ViewBlock' });

const props = defineProps<{ view: AiViewEvent }>();
const emit = defineEmits<{ enlarge: [view: AiViewEvent] }>();

const authStore = useAuthStore();
const appStore = useAppStore();
const router = useRouter();

const definition = computed(() => lookupView(props.view.type));
const allowed = computed(() => {
  const required = definition.value?.requiredPermission ?? props.view.requiredPermission;
  return authStore.userInfo.buttons?.includes(required) ?? false;
});
const isCard = computed(() => props.view.presentation === 'reference_card');

/** 参数摘要，让用户点之前就知道要打开什么。 */
const brief = computed(() => {
  const params = props.view.params as Record<string, unknown>;
  const parts: string[] = [];
  if (params.deviceId) parts.push(String(params.deviceId));
  if (params.channel) parts.push(`第 ${params.channel} 路`);
  return parts.join(' · ');
});

/**
 * 放大。
 *
 * 小屏不开面板——面板会挤掉对话区，而全屏抽屉会盖住输入框，那正是本功能要避免的事。
 * 改为跳转到既有页面：那些页面本来就监听路由里的设备号参数。
 */
function enlarge() {
  if (appStore.isMobile) {
    const device = String((props.view.params as Record<string, unknown>).deviceId ?? '');
    if (props.view.type === 'track_map' && device) {
      router.push({ name: 'track', query: { device } });
      return;
    }
    if (props.view.type === 'live_map' && device) {
      router.push({ name: 'monitor', query: { device } });
      return;
    }
  }
  emit('enlarge', props.view);
}
</script>

<template>
  <div class="group mt-8px">
    <div class="mb-4px flex items-center gap-6px text-13px text-gray-600 dark:text-gray-300">
      <SvgIcon :icon="definition?.icon ?? 'mdi:shape-outline'" class="flex-shrink-0" />
      <span class="flex-1 truncate">{{ view.title || definition?.label || view.label }}</span>
      <NButton
        v-if="definition && allowed && definition.enlargeable && !isCard"
        size="tiny"
        quaternary
        class="opacity-0 focus-visible:opacity-100 group-hover:opacity-100"
        aria-label="放大查看"
        @click="enlarge"
      >
        <template #icon>
          <SvgIcon icon="mdi:arrow-expand" />
        </template>
      </NButton>
    </div>

    <!-- 类型不认识：多半是前后端版本不一致，说清楚比显示一块空白强 -->
    <NAlert v-if="!definition" type="warning" :bordered="false" class="text-13px">
      当前版本无法展示「{{ view.label || view.type }}」，可能需要刷新页面或升级。
    </NAlert>
    <NAlert v-else-if="!allowed" type="info" :bordered="false" class="text-13px">
      你没有查看「{{ definition.label }}」的权限。
    </NAlert>

    <!--
      引用卡：只有视频用。它不自动播放——开流会向路上那台车下发指令，
      这种有真实副作用的事不该由 AI 自动触发。
    -->
    <NCard v-else-if="isCard" size="small" :bordered="true" class="border-primary/40">
      <div class="flex items-center gap-12px">
        <SvgIcon :icon="definition.icon" class="flex-shrink-0 text-28px text-primary" />
        <div class="min-w-0 flex-1">
          <p class="truncate text-14px font-medium">{{ definition.label }}</p>
          <p class="truncate text-12px text-gray-600 dark:text-gray-300">{{ brief }}</p>
        </div>
        <NButton size="small" type="primary" @click="enlarge">打开</NButton>
      </div>
      <p class="mt-6px text-12px text-gray-500 dark:text-gray-400">
        打开会向车辆下发开流指令，可能需要等待几秒唤醒设备。
      </p>
    </NCard>

    <component :is="definition.component" v-else :params="view.params" mode="inline" />
  </div>
</template>
