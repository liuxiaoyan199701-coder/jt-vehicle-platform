<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import type { FleetSummary } from '@/service/api';
import { formatConsoleTime } from '@/utils/fleet-operations';

defineOptions({ name: 'OverviewBanner' });

const props = defineProps<{
  summary: FleetSummary;
  connected: boolean;
  loading: boolean;
  lastUpdatedAt: string | null;
}>();

defineEmits<{ refresh: [] }>();
const authStore = useAuthStore();
const onlineRate = computed(() =>
  props.summary.fleetVehicles === 0 ? 0 : Math.round((props.summary.online / props.summary.fleetVehicles) * 100)
);
</script>

<template>
  <div class="overview-bar">
    <div class="min-w-0">
      <div class="flex flex-wrap items-center gap-8px">
        <h2 class="m-0 text-20px font-semibold">车队运营总览</h2>
        <NTag :type="connected ? 'success' : 'warning'" size="small" round>
          {{ connected ? '实时链路已连接' : '实时链路重连中' }}
        </NTag>
      </div>
      <div class="mt-4px text-13px text-gray-500">
        {{ authStore.userInfo.userName || '管理员' }} · 在线率 {{ onlineRate }}% ·
        更新 {{ formatConsoleTime(lastUpdatedAt) }}
      </div>
    </div>
    <NButton quaternary circle :loading="loading" title="刷新运营数据" @click="$emit('refresh')">
      <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
    </NButton>
  </div>
</template>

<style scoped>
.overview-bar {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 4px;
}
</style>
