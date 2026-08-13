<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { fetchLiveStatus, fetchVehicles, type LiveStatus } from '@/service/api';
import { useAMap } from '@/hooks/use-amap';
import { useLiveSocket, type LiveLocationUpdate } from '@/hooks/use-live-socket';
import { escapeHtmlText } from '@/utils/html';
import { isLiveStatusNewer, reconcileLiveStatusSnapshot } from '@/utils/live-status';
import VideoDialog from './modules/video-dialog.vue';
import CommandPanel from './modules/command-panel.vue';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';

defineOptions({ name: 'MonitorIndex' });

const mapContainer = ref<HTMLElement | null>(null);
const route = useRoute();
const { map, AMap, ready, error, init } = useAMap();

const vehicles = ref<LiveStatus[]>([]);
const selectedId = ref<string>('');
const keyword = ref('');
const videoVisible = ref(false);
const videoTarget = ref<{ deviceId: string; plateNo: string; channelCount: number } | null>(null);
const vehicleChannelCounts = ref(new Map<string, number>());
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);
const commandVisible = ref(false);
const commandTarget = ref<LiveStatus | null>(null);
const calibrating = ref(false);
const degraded = ref(false);
const calibrationError = ref('');
const lastCalibratedAt = ref<string | null>(null);
let calibrationPromise: Promise<void> | null = null;
let calibrationTimer: ReturnType<typeof setInterval> | null = null;

/** deviceId -> AMap.Marker，位置更新时复用同一个 marker 平滑移动，而不是重建 */
const markers = shallowRef(new Map<string, any>());

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  if (!word) return vehicles.value;
  return vehicles.value.filter(
    item =>
      item.deviceId.toLowerCase().includes(word) || (item.plateNo ?? '').toLowerCase().includes(word)
  );
});

const onlineCount = computed(() => vehicles.value.filter(item => item.online).length);

const { connected, state: liveState } = useLiveSocket(handleLiveUpdate, {
  onConnected: calibrateVehicles
});

const connectionLabel = computed(() => {
  if (degraded.value) return '数据降级';
  if (connected.value) return '实时';
  return liveState.value === 'connecting' ? '连接中' : '重连中';
});

const connectionTagType = computed(() => {
  if (degraded.value) return 'warning';
  return connected.value ? 'success' : 'default';
});

onMounted(async () => {
  if (mapContainer.value) {
    await init(mapContainer.value);
  }
  await Promise.all([calibrateVehicles(), loadVehicleChannels()]);
  vehicles.value.forEach(renderVehicle);
  focusRequestedVehicle();
  calibrationTimer = setInterval(() => void calibrateVehicles(), 30000);
});

watch(
  () => route.query.device,
  () => focusRequestedVehicle()
);

onBeforeUnmount(() => {
  if (calibrationTimer) {
    clearInterval(calibrationTimer);
    calibrationTimer = null;
  }
});

function calibrateVehicles() {
  if (calibrationPromise) {
    return calibrationPromise;
  }

  calibrating.value = true;
  calibrationPromise = (async () => {
    const { data, error: requestError } = await fetchLiveStatus();
    if (requestError || !data) {
      degraded.value = true;
      calibrationError.value = requestError?.message || '实时状态校准失败';
      return;
    }

    applyFullSnapshot(data);
    degraded.value = false;
    calibrationError.value = '';
    lastCalibratedAt.value = new Date().toISOString();
    vehicles.value.forEach(renderVehicle);
    fitViewIfNeeded();
  })().finally(() => {
    calibrating.value = false;
    calibrationPromise = null;
  });
  return calibrationPromise;
}

function applyFullSnapshot(snapshot: LiveStatus[]) {
  vehicles.value = reconcileLiveStatusSnapshot(vehicles.value, snapshot);
}

/** WebSocket 增量更新：已存在则移动 marker，未见过的设备补进列表 */
function handleLiveUpdate(update: LiveLocationUpdate) {
  const index = vehicles.value.findIndex(item => item.deviceId === update.deviceId);
  if (index >= 0) {
    const current = vehicles.value[index];
    if (isLiveStatusNewer(current.lastSeenAt, update.receivedAt)) {
      return;
    }
    vehicles.value[index] = {
      ...current,
      online: true,
      lastSeenAt: update.receivedAt,
      deviceTime: update.deviceTime,
      lat: update.lat,
      lng: update.lng,
      gcjLat: update.gcjLat,
      gcjLng: update.gcjLng,
      speedKph: update.speedKph,
      direction: update.direction,
      altitude: update.altitude,
      mileage: update.mileage,
      accOn: update.accOn,
      alarmJson: JSON.stringify(update.alarms),
      activeAlarmCount: update.activeAlarmCount
    };
    renderVehicle(vehicles.value[index]);
  } else {
    const added: LiveStatus = {
      deviceId: update.deviceId,
      plateNo: null,
      online: update.online,
      lastSeenAt: update.receivedAt,
      deviceTime: update.deviceTime,
      lat: update.lat,
      lng: update.lng,
      gcjLat: update.gcjLat,
      gcjLng: update.gcjLng,
      speedKph: update.speedKph,
      direction: update.direction,
      altitude: update.altitude,
      mileage: update.mileage,
      accOn: update.accOn,
      positioned: true,
      alarmJson: JSON.stringify(update.alarms),
      statusJson: null,
      activeAlarmCount: update.activeAlarmCount
    };
    vehicles.value.push(added);
    renderVehicle(added);
    void calibrateVehicles();
  }
}

function renderVehicle(vehicle: LiveStatus) {
  if (!ready.value || !map.value || vehicle.gcjLng == null || vehicle.gcjLat == null) {
    return;
  }
  const position: [number, number] = [vehicle.gcjLng, vehicle.gcjLat];
  const existing = markers.value.get(vehicle.deviceId);

  if (existing) {
    existing.setPosition(position);
    existing.setContent(markerContent(vehicle));
    return;
  }

  const marker = new AMap.value.Marker({
    position,
    content: markerContent(vehicle),
    offset: new AMap.value.Pixel(-18, -18),
    title: vehicle.plateNo ?? vehicle.deviceId
  });
  marker.on('click', () => {
    selectedId.value = vehicle.deviceId;
  });
  map.value.add(marker);
  markers.value.set(vehicle.deviceId, marker);
}

function markerContent(vehicle: LiveStatus) {
  const color = vehicle.online ? '#18a058' : '#909399';
  const label = escapeHtmlText(vehicle.plateNo ?? vehicle.deviceId);
  const rawDirection = Number(vehicle.direction ?? 0);
  const direction = Number.isFinite(rawDirection) ? ((rawDirection % 360) + 360) % 360 : 0;
  return `
    <div style="display:flex;flex-direction:column;align-items:center;">
      <div style="width:0;height:0;border-left:9px solid transparent;border-right:9px solid transparent;
                  border-bottom:20px solid ${color};filter:drop-shadow(0 1px 2px rgba(0,0,0,.4));
                  transform:rotate(${direction}deg);transform-origin:center;"></div>
      <div style="margin-top:2px;padding:1px 6px;background:${color};color:#fff;border-radius:3px;
                  font-size:12px;white-space:nowrap;">${label}</div>
    </div>`;
}

function fitViewIfNeeded() {
  if (ready.value && map.value && markers.value.size > 0) {
    map.value.setFitView(null, false, [60, 60, 60, 60]);
  }
}

function focusVehicle(vehicle: LiveStatus) {
  selectedId.value = vehicle.deviceId;
  if (ready.value && map.value && vehicle.gcjLng != null && vehicle.gcjLat != null) {
    map.value.setZoomAndCenter(16, [vehicle.gcjLng, vehicle.gcjLat]);
  }
}

function focusRequestedVehicle() {
  const requestedDevice = typeof route.query.device === 'string' ? route.query.device : '';
  const requested = requestedDevice ? vehicles.value.find(item => item.deviceId === requestedDevice) : null;
  if (requested) focusVehicle(requested);
}

async function loadVehicleChannels() {
  const { data } = await fetchVehicles();
  vehicleChannelCounts.value = new Map((data ?? []).map(item => [item.deviceId, item.channelCount]));
}

function openProfile(vehicle: LiveStatus) {
  profileDeviceId.value = vehicle.deviceId;
  profileVisible.value = true;
}

function openCommands(vehicle: LiveStatus) {
  commandTarget.value = vehicle;
  commandVisible.value = true;
}

function openVideo(vehicle: LiveStatus) {
  videoTarget.value = {
    deviceId: vehicle.deviceId,
    plateNo: vehicle.plateNo ?? vehicle.deviceId,
    channelCount: vehicleChannelCounts.value.get(vehicle.deviceId) ?? 4
  };
  videoVisible.value = true;
}

function alarmsOf(vehicle: LiveStatus): string[] {
  if (!vehicle.alarmJson) return [];
  try {
    return JSON.parse(vehicle.alarmJson);
  } catch {
    return [];
  }
}

function formatTime(value: string | null) {
  return value ? value.replace('T', ' ').replace('Z', '') : '-';
}
</script>

<template>
  <div class="h-full flex gap-12px">
    <NCard :bordered="false" class="w-320px flex-shrink-0" content-class="flex flex-col h-full p-0!">
      <template #header>
        <div class="flex items-center justify-between">
          <span>车辆列表</span>
          <NTag :type="connectionTagType" size="small" round>
            {{ connectionLabel }}
          </NTag>
        </div>
      </template>

      <div class="px-12px pb-8px">
        <NInput v-model:value="keyword" placeholder="搜索车牌或终端号" clearable size="small" />
        <div class="mt-8px text-12px text-gray-500">
          共 {{ vehicles.length }} 台，在线 <span class="text-success font-bold">{{ onlineCount }}</span> 台
        </div>
        <NAlert v-if="degraded" type="warning" :bordered="false" class="mt-8px text-12px">
          {{ calibrationError }}，当前展示最近一次成功数据。
          <NButton text type="primary" size="tiny" :loading="calibrating" @click="calibrateVehicles">
            立即重试
          </NButton>
        </NAlert>
        <div v-else-if="lastCalibratedAt" class="mt-4px text-11px text-gray-400">
          最近校准：{{ formatTime(lastCalibratedAt) }}
        </div>
      </div>

      <NScrollbar class="flex-1">
        <NSpin v-if="calibrating && vehicles.length === 0" class="mt-40px" />
        <NEmpty
          v-else-if="filtered.length === 0 && !calibrationError"
          description="暂无车辆"
          class="mt-40px"
        />
        <div
          v-for="vehicle in filtered"
          :key="vehicle.deviceId"
          class="cursor-pointer border-b border-gray-100 px-12px py-8px transition hover:bg-gray-50"
          :class="{ 'bg-primary-50!': selectedId === vehicle.deviceId }"
          @click="focusVehicle(vehicle)"
        >
          <div class="flex items-center justify-between">
            <span class="font-medium">{{ vehicle.plateNo ?? vehicle.deviceId }}</span>
            <NBadge :dot="true" :type="vehicle.online ? 'success' : 'default'" />
          </div>
          <div v-if="vehicle.gcjLat == null" class="mt-2px text-12px text-warning">
            未定位 · 终端未上报位置
          </div>
          <div v-else class="mt-2px text-12px text-gray-500">
            {{ vehicle.speedKph?.toFixed(1) ?? '-' }} km/h · {{ formatTime(vehicle.deviceTime) }}
          </div>
          <div v-if="alarmsOf(vehicle).length" class="mt-4px">
            <NTag v-for="alarm in alarmsOf(vehicle)" :key="alarm" type="error" size="tiny" class="mr-4px">
              {{ alarm }}
            </NTag>
          </div>
          <NTag v-else-if="vehicle.activeAlarmCount" type="error" size="tiny" class="mt-4px">
            {{ vehicle.activeAlarmCount }} 条活动告警
          </NTag>
          <NTooltip v-if="!vehicle.online" placement="right">
            <template #trigger>
              <NButton size="tiny" quaternary disabled class="mt-4px">查看视频</NButton>
            </template>
            设备离线，无法开流
          </NTooltip>
          <NButton
            v-else
            size="tiny"
            quaternary
            type="primary"
            class="mt-4px"
            @click.stop="openVideo(vehicle)"
          >
            查看视频
          </NButton>
          <NButton size="tiny" quaternary class="mt-4px" @click.stop="openProfile(vehicle)">
            运营详情
          </NButton>
          <NTooltip :disabled="vehicle.online" placement="right">
            <template #trigger>
              <NButton
                size="tiny"
                quaternary
                type="warning"
                class="mt-4px"
                :disabled="!vehicle.online"
                @click.stop="openCommands(vehicle)"
              >
                远程控制
              </NButton>
            </template>
            设备离线，无法下发指令
          </NTooltip>
        </div>
      </NScrollbar>
    </NCard>

    <NCard :bordered="false" class="flex-1" content-class="p-0! h-full">
      <div v-if="error" class="h-full flex-center flex-col gap-12px p-24px">
        <NAlert type="warning" title="地图不可用" class="max-w-480px">
          {{ error }}
        </NAlert>
        <div class="text-13px text-gray-500">
          车辆数据和实时推送不受影响，可在车辆列表中查看位置与速度。
        </div>
      </div>
      <div v-else ref="mapContainer" class="h-full w-full"></div>
    </NCard>

    <VideoDialog v-model:visible="videoVisible" :target="videoTarget" />
    <VehicleProfileDrawer v-model:visible="profileVisible" :device-id="profileDeviceId" />
    <CommandPanel v-model:visible="commandVisible" :vehicle="commandTarget" />
  </div>
</template>

<style scoped>
:deep(.n-card__content) {
  height: 100%;
}
</style>
