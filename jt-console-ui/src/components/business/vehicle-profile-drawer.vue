<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { fetchVehicleProfile, type AlarmEvent, type VehicleOperationsProfile } from '@/service/api';
import {
  alarmLevelLabel,
  alarmLevelTagType,
  alarmStatusLabel,
  alarmStatusTagType,
  formatConsoleTime,
  normalizeVehicleProfile
} from '@/utils/fleet-operations';
import VideoDialog from '@/views/monitor/modules/video-dialog.vue';

defineOptions({ name: 'VehicleProfileDrawer' });

const props = defineProps<{
  visible: boolean;
  deviceId: string | null;
}>();

const emit = defineEmits<{ 'update:visible': [value: boolean] }>();
const router = useRouter();
const appStore = useAppStore();
const loading = ref(false);
const errorMessage = ref('');
const profile = ref<VehicleOperationsProfile | null>(null);
const videoVisible = ref(false);

const videoTarget = computed(() => {
  if (!profile.value) return null;
  return {
    deviceId: profile.value.vehicle.deviceId,
    plateNo: profile.value.vehicle.plateNo,
    channelCount: profile.value.vehicle.channelCount
  };
});

watch(
  () => [props.visible, props.deviceId] as const,
  ([visible, deviceId]) => {
    if (visible && deviceId) void load(deviceId);
    if (!visible) videoVisible.value = false;
  },
  { immediate: true }
);

async function load(deviceId: string) {
  loading.value = true;
  errorMessage.value = '';
  profile.value = null;
  const { data, error } = await fetchVehicleProfile(deviceId);
  loading.value = false;
  if (error || !data) {
    errorMessage.value = error?.message || '车辆详情加载失败';
    return;
  }
  profile.value = normalizeVehicleProfile(data);
}

function close() {
  emit('update:visible', false);
}

async function navigate(name: 'monitor' | 'track') {
  const deviceId = profile.value?.vehicle.deviceId;
  if (!deviceId) return;
  close();
  await router.push({ name, query: { device: deviceId } });
}

function alarmTitle(alarm: AlarmEvent) {
  return alarm.geofenceName ? `${alarm.title} · ${alarm.geofenceName}` : alarm.title;
}
</script>

<template>
  <NDrawer :show="visible" :width="appStore.isMobile ? '100%' : 560" placement="right" @update:show="close">
    <NDrawerContent :title="profile?.vehicle.plateNo || '车辆运营详情'" closable>
      <div v-if="loading" class="h-240px flex-center">
        <NSpin size="large" />
      </div>

      <NResult v-else-if="errorMessage" status="error" title="详情加载失败" :description="errorMessage">
        <template #footer>
          <NButton type="primary" @click="deviceId && load(deviceId)">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            重试
          </NButton>
        </template>
      </NResult>

      <div v-else-if="profile" class="flex flex-col gap-16px">
        <NAlert v-if="!profile.status" type="info" :bordered="false">
          该车辆已建档，但尚无位置或状态上报。
        </NAlert>
        <div class="flex flex-wrap items-center gap-8px">
          <NTag :type="profile.status?.online ? 'success' : 'default'" round>
            {{ profile.status?.online ? '在线' : '离线' }}
          </NTag>
          <NTag :type="profile.status?.positioned ? 'info' : 'default'">
            {{ profile.status?.positioned ? '已定位' : '未定位' }}
          </NTag>
          <NTag :type="profile.status?.accOn ? 'warning' : 'default'">
            ACC {{ profile.status?.accOn ? 'ON' : 'OFF' }}
          </NTag>
          <NTag v-if="profile.openAlarmCount" type="error">
            {{ profile.openAlarmCount }} 条未关闭告警
          </NTag>
        </div>

        <NDescriptions label-placement="left" :column="2" bordered size="small" class="profile-descriptions">
          <NDescriptionsItem label="车牌号">{{ profile.vehicle.plateNo }}</NDescriptionsItem>
          <NDescriptionsItem label="终端号">{{ profile.vehicle.deviceId }}</NDescriptionsItem>
          <NDescriptionsItem label="车牌颜色">{{ profile.vehicle.plateColor || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="品牌型号">{{ profile.vehicle.brand || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="最后上报" :span="2">
            {{ formatConsoleTime(profile.status?.lastSeenAt) }}
          </NDescriptionsItem>
          <NDescriptionsItem label="当前速度">
            {{ profile.status?.speedKph?.toFixed(1) ?? '-' }} km/h
          </NDescriptionsItem>
          <NDescriptionsItem label="累计里程">
            {{ profile.status?.mileage?.toFixed(1) ?? '-' }} km
          </NDescriptionsItem>
        </NDescriptions>

        <div>
          <div class="mb-8px text-14px font-medium">今日运营</div>
          <NGrid cols="2 m:5" responsive="screen" :x-gap="8" :y-gap="12">
            <NGi>
              <NStatistic label="今日里程" :value="profile.today.distanceKm" :precision="1">
                <template #suffix><span class="text-12px">km</span></template>
              </NStatistic>
            </NGi>
            <NGi><NStatistic label="位置点" :value="profile.today.pointCount" /></NGi>
            <NGi><NStatistic label="行驶点" :value="profile.today.movingPoints" /></NGi>
            <NGi>
              <NStatistic label="最高速" :value="profile.today.maxSpeedKph" :precision="1">
                <template #suffix><span class="text-12px">km/h</span></template>
              </NStatistic>
            </NGi>
            <NGi><NStatistic label="新增告警" :value="profile.today.alarmCount" /></NGi>
          </NGrid>
        </div>

        <div>
          <div class="mb-8px text-14px font-medium">近七日</div>
          <NGrid cols="2 m:4" responsive="screen" :x-gap="8" :y-gap="12">
            <NGi>
              <NStatistic label="7日里程" :value="profile.last7Days.distanceKm" :precision="1">
                <template #suffix><span class="text-12px">km</span></template>
              </NStatistic>
            </NGi>
            <NGi><NStatistic label="7日活跃" :value="profile.last7Days.activeDays" suffix=" 天" /></NGi>
            <NGi>
              <NStatistic label="7日最高速" :value="profile.last7Days.maxSpeedKph" :precision="1">
                <template #suffix><span class="text-12px">km/h</span></template>
              </NStatistic>
            </NGi>
            <NGi><NStatistic label="7日告警" :value="profile.last7Days.alarmCount" /></NGi>
          </NGrid>
        </div>

        <div>
          <div class="mb-8px flex items-center justify-between">
            <span class="text-14px font-medium">最近告警</span>
            <span class="text-12px text-gray-400">今日 {{ profile.today.alarmCount }} 条</span>
          </div>
          <NEmpty v-if="profile.recentAlarms.length === 0" description="暂无告警" class="py-20px" />
          <NList v-else bordered>
            <NListItem v-for="alarm in profile.recentAlarms" :key="alarm.id">
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-6px">
                  <NTag :type="alarmLevelTagType(alarm.level)" size="small">
                    {{ alarmLevelLabel(alarm.level) }}
                  </NTag>
                  <span class="min-w-0 truncate font-medium">{{ alarmTitle(alarm) }}</span>
                  <NTag :type="alarmStatusTagType(alarm.status)" size="small" :bordered="false">
                    {{ alarmStatusLabel(alarm.status) }}
                  </NTag>
                </div>
                <div class="mt-4px text-12px text-gray-500">{{ formatConsoleTime(alarm.occurredAt) }}</div>
              </div>
            </NListItem>
          </NList>
        </div>
      </div>

      <template #footer>
        <NSpace v-if="profile" justify="end" wrap>
          <NButton @click="navigate('monitor')">
            <template #icon><SvgIcon icon="lucide:map-pin" /></template>
            定位
          </NButton>
          <NButton @click="navigate('track')">
            <template #icon><SvgIcon icon="lucide:route" /></template>
            轨迹
          </NButton>
          <NTooltip :disabled="Boolean(profile.status?.online)">
            <template #trigger>
              <NButton type="primary" :disabled="!profile.status?.online" @click="videoVisible = true">
                <template #icon><SvgIcon icon="lucide:video" /></template>
                视频
              </NButton>
            </template>
            设备离线，无法开流
          </NTooltip>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>

  <VideoDialog v-model:visible="videoVisible" :target="videoTarget" />
</template>

<style scoped>
@media (max-width: 640px) {
  .profile-descriptions :deep(.n-descriptions-table-content) {
    table-layout: fixed;
  }
}
</style>
