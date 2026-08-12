<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { fetchDashboardOverview, type AlarmEvent, type DashboardOverview } from '@/service/api';
import { useLiveSocket } from '@/hooks/use-live-socket';
import { normalizeDashboardOverview } from '@/utils/fleet-operations';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';
import OverviewBanner from './modules/overview-banner.vue';
import StatCards from './modules/stat-cards.vue';
import FleetTrendChart from './modules/fleet-trend-chart.vue';
import AlarmLevelChart from './modules/alarm-level-chart.vue';
import RecentAlarms from './modules/recent-alarms.vue';

defineOptions({ name: 'HomeIndex' });

const router = useRouter();
const overview = ref<DashboardOverview>(normalizeDashboardOverview(undefined));
const loading = ref(false);
const degraded = ref(false);
const errorMessage = ref('');
const lastUpdatedAt = ref<string | null>(null);
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);
let refreshTimer: ReturnType<typeof setInterval> | null = null;
let loadPromise: Promise<void> | null = null;

const { connected } = useLiveSocket(() => undefined, { onConnected: load });

const shortcuts = [
  { label: '实时监控', icon: 'lucide:map-pin', route: 'monitor' },
  { label: '告警中心', icon: 'lucide:triangle-alert', route: 'alarm' },
  { label: '电子围栏', icon: 'lucide:circle-dot-dashed', route: 'geofence' },
  { label: '车辆档案', icon: 'lucide:car-front', route: 'vehicle' },
  { label: '轨迹回放', icon: 'lucide:route', route: 'track' }
] as const;

const latestCritical = computed(() =>
  overview.value.recentAlarms.find(item => item.level === 'CRITICAL' && item.status !== 'CLOSED')
);

onMounted(async () => {
  await load();
  refreshTimer = setInterval(() => void load(), 30000);
});

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});

function load() {
  if (loadPromise) return loadPromise;
  loading.value = true;
  loadPromise = (async () => {
    const { data, error } = await fetchDashboardOverview();
    if (error || !data) {
      degraded.value = true;
      errorMessage.value = error?.message || '运营数据加载失败';
      return;
    }
    overview.value = normalizeDashboardOverview(data);
    degraded.value = false;
    errorMessage.value = '';
    lastUpdatedAt.value = new Date().toISOString();
  })().finally(() => {
    loading.value = false;
    loadPromise = null;
  });
  return loadPromise;
}

function openVehicle(deviceId: string) {
  profileDeviceId.value = deviceId;
  profileVisible.value = true;
}

function openAlarm(alarm: AlarmEvent) {
  void router.push({ name: 'alarm', query: { alarm: String(alarm.id) } });
}
</script>

<template>
  <NSpace vertical :size="12">
    <OverviewBanner
      :summary="overview.summary"
      :connected="connected"
      :loading="loading"
      :last-updated-at="lastUpdatedAt"
      @refresh="load"
    />

    <NAlert v-if="degraded" type="warning" :bordered="false">
      {{ errorMessage }}，当前保留最近一次成功数据。
      <NButton text type="primary" :loading="loading" @click="load">立即重试</NButton>
    </NAlert>

    <NAlert v-if="latestCritical" type="error" :bordered="false" class="dashboard-alert">
      <template #header>有严重告警待处置</template>
      <div class="flex flex-wrap items-center justify-between gap-8px">
        <span>{{ latestCritical.plateNo || latestCritical.deviceId }} · {{ latestCritical.title }}</span>
        <NButton size="small" type="error" secondary @click="openAlarm(latestCritical)">查看告警</NButton>
      </div>
    </NAlert>

    <StatCards :summary="overview.summary" />

    <NGrid cols="1 m:24" responsive="screen" :x-gap="12" :y-gap="12">
      <NGi span="m:16">
        <FleetTrendChart :items="overview.dailyTrend" />
      </NGi>
      <NGi span="m:8">
        <AlarmLevelChart :items="overview.alarmLevels" />
      </NGi>
    </NGrid>

    <NGrid cols="1 m:24" responsive="screen" :x-gap="12" :y-gap="12">
      <NGi span="m:16">
        <RecentAlarms :alarms="overview.recentAlarms" @open-alarm="openAlarm" @open-vehicle="openVehicle" />
      </NGi>
      <NGi span="m:8">
        <NCard title="快捷入口" :bordered="false" size="small" class="h-full">
          <div class="shortcut-grid">
            <NButton
              v-for="item in shortcuts"
              :key="item.route"
              class="shortcut-button"
              secondary
              @click="router.push({ name: item.route })"
            >
              <template #icon><SvgIcon :icon="item.icon" /></template>
              {{ item.label }}
            </NButton>
          </div>
        </NCard>
      </NGi>
    </NGrid>
  </NSpace>

  <VehicleProfileDrawer
    v-model:visible="profileVisible"
    :device-id="profileDeviceId"
  />
</template>

<style scoped>
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.shortcut-button {
  width: 100%;
  min-width: 0;
  justify-content: flex-start;
}

@media (max-width: 480px) {
  .shortcut-grid {
    grid-template-columns: 1fr;
  }
}
</style>
