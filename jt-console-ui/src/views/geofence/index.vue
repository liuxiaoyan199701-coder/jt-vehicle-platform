<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import {
  createGeofence,
  deleteGeofence,
  fetchGeofences,
  fetchVehicles,
  setGeofenceEnabled,
  updateGeofence,
  type Geofence,
  type GeofenceMutation,
  type Vehicle
} from '@/service/api';
import { useAMap } from '@/hooks/use-amap';
import { normalizeGeofences } from '@/utils/fleet-operations';

defineOptions({ name: 'GeofenceIndex' });

const message = useMessage();
const appStore = useAppStore();
const mapContainer = ref<HTMLElement | null>(null);
const { map, AMap, ready, error, init } = useAMap({ zoom: 13 });
const geofences = ref<Geofence[]>([]);
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);
const selectedId = ref<number | null>(null);
const editorVisible = ref(false);
const isEdit = ref(false);
const submitting = ref(false);
const keyword = ref('');
const circle = shallowRef<any>(null);
const centerMarker = shallowRef<any>(null);

const form = reactive<GeofenceMutation>({
  name: '',
  centerGcjLat: 39.90923,
  centerGcjLng: 116.397428,
  radiusMeters: 500,
  color: '#18a058',
  enabled: true,
  alertOnEnter: true,
  alertOnExit: true,
  speedLimitKph: null,
  vehicleIds: []
});

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  if (!word) return geofences.value;
  return geofences.value.filter(item => item.name.toLowerCase().includes(word));
});

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

onMounted(async () => {
  const dataPromise = load();
  if (mapContainer.value) {
    await init(mapContainer.value);
    map.value?.on('click', handleMapClick);
  }
  await dataPromise;
  renderAllGeofences();
});

watch(
  () => [form.centerGcjLng, form.centerGcjLat, form.radiusMeters, form.color] as const,
  () => {
    if (editorVisible.value) renderEditorPreview();
  }
);

async function load() {
  loading.value = true;
  const [geofenceResult, vehicleResult] = await Promise.all([fetchGeofences(), fetchVehicles()]);
  loading.value = false;
  if (geofenceResult.error) {
    message.error(geofenceResult.error.message || '围栏列表加载失败');
  } else {
    geofences.value = normalizeGeofences(geofenceResult.data);
  }
  vehicles.value = vehicleResult.data ?? [];
}

function renderAllGeofences() {
  if (!ready.value || !map.value) return;
  clearPreview();
  const overlays = geofences.value.map(item => new AMap.value.Circle({
    center: [item.centerGcjLng, item.centerGcjLat],
    radius: item.radiusMeters,
    strokeColor: item.color,
    strokeWeight: selectedId.value === item.id ? 4 : 2,
    strokeOpacity: item.enabled ? 0.9 : 0.45,
    fillColor: item.color,
    fillOpacity: item.enabled ? 0.14 : 0.05,
    bubble: true,
    extData: item.id
  }));
  overlays.forEach((overlay, index) => {
    overlay.on('click', () => selectGeofence(geofences.value[index]));
    map.value.add(overlay);
  });
  circle.value = overlays;
  if (overlays.length > 0) map.value.setFitView(overlays, false, [50, 50, 50, 50]);
}

function clearPreview() {
  if (!map.value) return;
  const overlays = Array.isArray(circle.value) ? circle.value : circle.value ? [circle.value] : [];
  if (overlays.length) map.value.remove(overlays);
  if (centerMarker.value) map.value.remove(centerMarker.value);
  circle.value = null;
  centerMarker.value = null;
}

function renderEditorPreview() {
  if (!ready.value || !map.value) return;
  clearPreview();
  const center: [number, number] = [Number(form.centerGcjLng), Number(form.centerGcjLat)];
  if (!Number.isFinite(center[0]) || !Number.isFinite(center[1])) return;
  circle.value = new AMap.value.Circle({
    center,
    radius: Math.max(1, Number(form.radiusMeters) || 1),
    strokeColor: form.color,
    strokeWeight: 3,
    fillColor: form.color,
    fillOpacity: 0.16,
    bubble: true
  });
  centerMarker.value = new AMap.value.Marker({ position: center, title: form.name || '围栏圆心' });
  map.value.add([circle.value, centerMarker.value]);
  map.value.setFitView([circle.value], false, [80, 80, 80, 80]);
}

function handleMapClick(event: any) {
  if (!editorVisible.value || !event?.lnglat) return;
  form.centerGcjLng = Number(event.lnglat.getLng().toFixed(6));
  form.centerGcjLat = Number(event.lnglat.getLat().toFixed(6));
}

function selectGeofence(item: Geofence) {
  selectedId.value = item.id;
  if (ready.value && map.value) {
    map.value.setZoomAndCenter(15, [item.centerGcjLng, item.centerGcjLat]);
  }
}

function openCreate() {
  isEdit.value = false;
  selectedId.value = null;
  const center = map.value?.getCenter?.();
  Object.assign(form, {
    name: '',
    centerGcjLat: center?.getLat?.() ?? 39.90923,
    centerGcjLng: center?.getLng?.() ?? 116.397428,
    radiusMeters: 500,
    color: '#18a058',
    enabled: true,
    alertOnEnter: true,
    alertOnExit: true,
    speedLimitKph: null,
    vehicleIds: []
  });
  editorVisible.value = true;
  renderEditorPreview();
}

function openEdit(item: Geofence) {
  isEdit.value = true;
  selectedId.value = item.id;
  Object.assign(form, {
    name: item.name,
    centerGcjLat: item.centerGcjLat,
    centerGcjLng: item.centerGcjLng,
    radiusMeters: item.radiusMeters,
    color: item.color,
    enabled: item.enabled,
    alertOnEnter: item.alertOnEnter,
    alertOnExit: item.alertOnExit,
    speedLimitKph: item.speedLimitKph,
    vehicleIds: [...item.vehicleIds]
  });
  editorVisible.value = true;
  renderEditorPreview();
}

function closeEditor() {
  editorVisible.value = false;
  renderAllGeofences();
}

function validate() {
  if (!form.name.trim()) return '请填写围栏名称';
  if (!Number.isFinite(form.centerGcjLat) || form.centerGcjLat < -90 || form.centerGcjLat > 90) return '纬度必须在 -90 到 90 之间';
  if (!Number.isFinite(form.centerGcjLng) || form.centerGcjLng < -180 || form.centerGcjLng > 180) return '经度必须在 -180 到 180 之间';
  if (!Number.isFinite(form.radiusMeters) || form.radiusMeters <= 0) return '半径必须大于 0';
  if (form.speedLimitKph != null && (!Number.isFinite(form.speedLimitKph) || form.speedLimitKph <= 0)) return '限速必须大于 0';
  return '';
}

async function submit() {
  const validationError = validate();
  if (validationError) {
    message.warning(validationError);
    return;
  }
  const payload: GeofenceMutation = {
    ...form,
    name: form.name.trim(),
    vehicleIds: [...new Set(form.vehicleIds)]
  };
  submitting.value = true;
  const result = isEdit.value && selectedId.value != null
    ? await updateGeofence(selectedId.value, payload)
    : await createGeofence(payload);
  if (result.error || !result.data) {
    submitting.value = false;
    message.error(result.error?.message || '围栏保存失败');
    return;
  }
  const savedId = result.data.id;
  submitting.value = false;
  message.success(isEdit.value ? '围栏已更新' : '围栏已创建');
  editorVisible.value = false;
  selectedId.value = savedId;
  await load();
  renderAllGeofences();
}

async function toggleEnabled(item: Geofence, enabled: boolean) {
  const { error: requestError } = await setGeofenceEnabled(item.id, enabled);
  if (requestError) {
    message.error(requestError.message || '围栏状态更新失败');
    return;
  }
  item.enabled = enabled;
  message.success(enabled ? '围栏已启用' : '围栏已停用');
  renderAllGeofences();
}

async function remove(item: Geofence) {
  const { error: requestError } = await deleteGeofence(item.id);
  if (requestError) {
    message.error(requestError.message || '围栏删除失败');
    return;
  }
  if (selectedId.value === item.id) selectedId.value = null;
  message.success('围栏已删除');
  await load();
  renderAllGeofences();
}
</script>

<template>
  <div class="geofence-layout">
    <NCard :bordered="false" size="small" class="geofence-list" content-class="p-0! flex flex-col h-full">
      <template #header>
        <div class="flex items-center justify-between gap-8px">
          <span>电子围栏</span>
          <NButton type="primary" size="small" @click="openCreate">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            新增
          </NButton>
        </div>
      </template>
      <div class="px-12px pb-8px">
        <NInput v-model:value="keyword" clearable placeholder="搜索围栏" />
        <div class="mt-6px text-12px text-gray-500">共 {{ geofences.length }} 个，启用 {{ geofences.filter(item => item.enabled).length }} 个</div>
      </div>
      <NScrollbar class="min-h-0 flex-1">
        <NSpin v-if="loading && geofences.length === 0" class="mt-40px" />
        <NEmpty v-else-if="filtered.length === 0" description="暂无围栏" class="mt-40px" />
        <div
          v-for="item in filtered"
          :key="item.id"
          class="geofence-row"
          :class="{ selected: selectedId === item.id }"
          @click="selectGeofence(item)"
        >
          <div class="flex items-start gap-8px">
            <span class="mt-4px size-10px flex-none rounded-full" :style="{ backgroundColor: item.color }"></span>
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-8px">
                <span class="truncate font-medium">{{ item.name }}</span>
                <NSwitch :value="item.enabled" size="small" @click.stop @update:value="value => toggleEnabled(item, value)" />
              </div>
              <div class="mt-3px text-12px text-gray-500">
                半径 {{ item.radiusMeters }} m · {{ item.assignedVehicleCount }} 台车
              </div>
              <div class="mt-3px flex flex-wrap gap-4px">
                <NTag v-if="item.alertOnEnter" size="tiny">进入</NTag>
                <NTag v-if="item.alertOnExit" size="tiny">离开</NTag>
                <NTag v-if="item.speedLimitKph" size="tiny" type="warning">限速 {{ item.speedLimitKph }} km/h</NTag>
              </div>
              <NSpace class="mt-6px" size="small">
                <NButton text type="primary" size="tiny" @click.stop="openEdit(item)">编辑</NButton>
                <NPopconfirm @positive-click="remove(item)">
                  <template #trigger><NButton text type="error" size="tiny" @click.stop>删除</NButton></template>
                  删除后不再评估该围栏，历史告警仍保留。
                </NPopconfirm>
              </NSpace>
            </div>
          </div>
        </div>
      </NScrollbar>
    </NCard>

    <NCard :bordered="false" class="map-card" content-class="p-0! h-full">
      <div v-if="error" class="h-full flex-center p-24px">
        <NAlert type="warning" title="地图不可用" class="max-w-480px">{{ error }}</NAlert>
      </div>
      <div v-else ref="mapContainer" class="h-full w-full"></div>
    </NCard>

    <NDrawer :show="editorVisible" :width="appStore.isMobile ? '100%' : 480" placement="right" @update:show="closeEditor">
      <NDrawerContent :title="isEdit ? '编辑围栏' : '新增围栏'" closable>
        <NAlert type="info" :bordered="false" class="mb-12px">点击地图可设置圆心，地图坐标按 GCJ-02 保存。</NAlert>
        <NForm label-placement="top">
          <NFormItem label="围栏名称" required><NInput v-model:value="form.name" maxlength="80" show-count /></NFormItem>
          <NGrid :cols="2" :x-gap="10">
            <NGi><NFormItem label="纬度" required><NInputNumber v-model:value="form.centerGcjLat" :min="-90" :max="90" :precision="6" class="w-full" /></NFormItem></NGi>
            <NGi><NFormItem label="经度" required><NInputNumber v-model:value="form.centerGcjLng" :min="-180" :max="180" :precision="6" class="w-full" /></NFormItem></NGi>
          </NGrid>
          <NGrid :cols="2" :x-gap="10">
            <NGi><NFormItem label="半径 (m)" required><NInputNumber v-model:value="form.radiusMeters" :min="1" :max="100000" class="w-full" /></NFormItem></NGi>
            <NGi><NFormItem label="显示颜色"><NColorPicker v-model:value="form.color" :modes="['hex']" /></NFormItem></NGi>
          </NGrid>
          <NFormItem label="规则">
            <NSpace vertical>
              <NSwitch v-model:value="form.enabled"><template #checked>已启用</template><template #unchecked>已停用</template></NSwitch>
              <NCheckbox v-model:checked="form.alertOnEnter">进入围栏时告警</NCheckbox>
              <NCheckbox v-model:checked="form.alertOnExit">离开围栏时告警</NCheckbox>
            </NSpace>
          </NFormItem>
          <NFormItem label="围栏内限速 (km/h)">
            <NInputNumber v-model:value="form.speedLimitKph" :min="1" :max="300" clearable placeholder="不设限速" class="w-full" />
          </NFormItem>
          <NFormItem label="分配车辆">
            <NSelect v-model:value="form.vehicleIds" :options="vehicleOptions" multiple filterable clearable placeholder="选择已建档车辆" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="closeEditor">取消</NButton>
            <NButton type="primary" :loading="submitting" @click="submit">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.geofence-layout { display: grid; min-height: 600px; height: 100%; grid-template-columns: 340px minmax(0, 1fr); gap: 12px; }
.geofence-list, .map-card { min-height: 0; }
.geofence-row { padding: 10px 12px; border-top: 1px solid rgb(239 239 245); cursor: pointer; transition: background-color 0.15s; }
.geofence-row:hover, .geofence-row.selected { background: rgb(24 160 88 / 7%); }
@media (max-width: 760px) {
  .geofence-layout { height: auto; min-height: 0; grid-template-columns: 1fr; }
  .geofence-list { height: 360px; }
  .map-card { height: 480px; }
}
</style>
