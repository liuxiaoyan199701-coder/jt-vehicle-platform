<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { fetchVehicles } from '@/service/api';
import { useAMap } from '@/hooks/use-amap';
import { useDeviceFeed } from '@/hooks/use-device-feed';
import type { DeviceRow } from '@/utils/device-store';
import VideoDialog from './modules/video-dialog.vue';
import CommandPanel from './modules/command-panel.vue';
import DeviceList from './modules/device-list.vue';
import { createVehicleMarkerLayer, type VehicleMarkerLayer } from './modules/vehicle-marker-layer';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';

defineOptions({ name: 'MonitorIndex' });

const mapContainer = ref<HTMLElement | null>(null);
const route = useRoute();
const { map, AMap, ready, error, init } = useAMap();

const feed = useDeviceFeed();
const {
  store,
  rows,
  total,
  onlineCount,
  calibrating,
  degraded,
  calibrationError,
  lastCalibratedAt,
  connectionLabel,
  connectionTagType,
  calibrate
} = feed;

const selectedId = ref('');
const keyword = ref('');
const markerCapped = ref(false);
const videoVisible = ref(false);
const videoTarget = ref<{ deviceId: string; plateNo: string; channelCount: number } | null>(null);
const vehicleChannelCounts = ref(new Map<string, number>());
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);
const commandVisible = ref(false);
const commandTarget = ref<DeviceRow | null>(null);

const markerLayer = shallowRef<VehicleMarkerLayer | null>(null);
let fitDone = false;

onMounted(async () => {
  if (mapContainer.value) {
    await init(mapContainer.value);
  }
  if (ready.value && map.value && AMap.value) {
    setupMarkerLayer();
  }

  await Promise.all([calibrate(), loadVehicleChannels()]);
  focusRequestedVehicle();
});

/**
 * 地图标记跟着「视图发布」走，而不是跟着每条增量走——
 * 与列表共用同一个节流窗口，一次合并里最多同步一次标记。
 */
feed.onPublish(() => {
  const layer = markerLayer.value;
  if (!layer) return;
  // 先定视野再同步标记：反过来的话，刚建好的标记是按旧视野裁的
  if (!fitDone && total.value > 0) {
    fitDone = true;
    layer.fitAll(store);
  }
  layer.sync(store);
});

function setupMarkerLayer() {
  markerLayer.value = createVehicleMarkerLayer({
    map: map.value,
    AMap: AMap.value,
    onSelect: deviceId => {
      selectedId.value = deviceId;
    },
    onCappedChange: capped => {
      markerCapped.value = capped;
    }
  });

  // 拖动或缩放后视野变了，按新视野重算该画哪些标记
  map.value.on('moveend', syncMarkers);
  map.value.on('zoomend', syncMarkers);
  markerLayer.value.sync(store);
}

function syncMarkers() {
  markerLayer.value?.sync(store);
}

watch(
  () => route.query.device,
  () => focusRequestedVehicle()
);

watch(keyword, word => feed.setKeyword(word));

onBeforeUnmount(() => {
  if (map.value) {
    map.value.off('moveend', syncMarkers);
    map.value.off('zoomend', syncMarkers);
  }
  markerLayer.value?.destroy();
  markerLayer.value = null;
});

function focusVehicle(vehicle: DeviceRow) {
  selectedId.value = vehicle.deviceId;
  if (ready.value && map.value && vehicle.gcjLng != null && vehicle.gcjLat != null) {
    map.value.setZoomAndCenter(16, [vehicle.gcjLng, vehicle.gcjLat]);
  }
}

function focusRequestedVehicle() {
  const requestedDevice = typeof route.query.device === 'string' ? route.query.device : '';
  if (!requestedDevice) return;
  const requested = store.get(requestedDevice);
  if (requested) focusVehicle(requested);
}

async function loadVehicleChannels() {
  const { data } = await fetchVehicles();
  vehicleChannelCounts.value = new Map((data ?? []).map(item => [item.deviceId, item.channelCount]));
}

function openProfile(vehicle: DeviceRow) {
  profileDeviceId.value = vehicle.deviceId;
  profileVisible.value = true;
}

function openCommands(vehicle: DeviceRow) {
  commandTarget.value = vehicle;
  commandVisible.value = true;
}

function openVideo(vehicle: DeviceRow) {
  videoTarget.value = {
    deviceId: vehicle.deviceId,
    plateNo: vehicle.label,
    channelCount: vehicleChannelCounts.value.get(vehicle.deviceId) ?? 4
  };
  videoVisible.value = true;
}
</script>

<template>
  <div class="h-full flex gap-12px">
    <DeviceList
      v-model:keyword="keyword"
      :rows="rows"
      :total="total"
      :online-count="onlineCount"
      :selected-id="selectedId"
      :connection-label="connectionLabel"
      :connection-tag-type="connectionTagType"
      :degraded="degraded"
      :calibrating="calibrating"
      :calibration-error="calibrationError"
      :last-calibrated-at="lastCalibratedAt"
      :marker-capped="markerCapped"
      @retry="calibrate"
      @select="focusVehicle"
      @video="openVideo"
      @profile="openProfile"
      @command="openCommands"
    />

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
