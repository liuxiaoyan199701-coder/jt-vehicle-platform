<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { useRoute, useRouter } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { NButton, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  acknowledgeAlarm,
  closeAlarm,
  fetchAlarm,
  fetchAlarms,
  fetchMediaAround,
  fetchRecordingsAround,
  fetchVehicles,
  type AlarmEvent,
  type AlarmLevel,
  type AlarmQuery,
  type AlarmSource,
  type AlarmStatus,
  type MediaFileItem,
  type RecordingRange,
  type Vehicle
} from '@/service/api';
import {
  alarmLevelLabel,
  alarmLevelTagType,
  alarmStatusLabel,
  alarmStatusTagType,
  formatConsoleTime,
  normalizeAlarmPage
} from '@/utils/fleet-operations';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';

defineOptions({ name: 'AlarmIndex' });

const route = useRoute();
const router = useRouter();
const appStore = useAppStore();
const authStore = useAuthStore();
const message = useMessage();
const loading = ref(false);
const rows = ref<AlarmEvent[]>([]);
const total = ref(0);
const vehicles = ref<Vehicle[]>([]);
const detailVisible = ref(false);
const nearbyPhotos = ref<MediaFileItem[]>([]);
const nearbyRecordings = ref<RecordingRange[]>([]);
const currentAlarm = ref<AlarmEvent | null>(null);
const actionVisible = ref(false);
const actionType = ref<'acknowledge' | 'close'>('acknowledge');
const actionNote = ref('');
const actionSubmitting = ref(false);
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);

const filters = reactive<{
  keyword: string;
  deviceId: string | null;
  type: string;
  status: AlarmStatus | null;
  level: AlarmLevel | null;
  source: AlarmSource | null;
  range: [number, number] | null;
  page: number;
  pageSize: number;
}>({
  keyword: '',
  deviceId: null,
  type: '',
  status: null,
  level: null,
  source: null,
  range: null,
  page: 1,
  pageSize: 20
});

const levelOptions = (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as AlarmLevel[]).map(value => ({
  label: alarmLevelLabel(value),
  value
}));
const statusOptions = (['OPEN', 'ACKNOWLEDGED', 'CLOSED'] as AlarmStatus[]).map(value => ({
  label: alarmStatusLabel(value),
  value
}));
const sourceOptions: { label: string; value: AlarmSource }[] = [
  { label: 'JT/T 808', value: 'PROTOCOL' },
  { label: '电子围栏', value: 'GEOFENCE' }
];
const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

const pagination = computed(() => ({
  page: filters.page,
  pageSize: filters.pageSize,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [10, 20, 50],
  prefix: ({ itemCount }: { itemCount?: number }) => `共 ${itemCount ?? 0} 条`,
  onUpdatePage: (page: number) => {
    filters.page = page;
    void load();
  },
  onUpdatePageSize: (pageSize: number) => {
    filters.pageSize = pageSize;
    filters.page = 1;
    void load();
  }
}));

const columns: DataTableColumns<AlarmEvent> = [
  {
    title: '车辆',
    key: 'deviceId',
    width: 170,
    fixed: 'left',
    render: row => h('div', [
      h('div', { class: 'font-medium' }, row.plateNo || '未建档'),
      h('div', { class: 'text-12px text-gray-500' }, row.deviceId)
    ])
  },
  { title: '告警标题', key: 'title', minWidth: 180, ellipsis: { tooltip: true } },
  {
    title: '级别', key: 'level', width: 80,
    render: row => h(NTag, { size: 'small', type: alarmLevelTagType(row.level) }, () => alarmLevelLabel(row.level))
  },
  {
    title: '状态', key: 'status', width: 92,
    render: row => h(NTag, { size: 'small', bordered: false, type: alarmStatusTagType(row.status) }, () => alarmStatusLabel(row.status))
  },
  { title: '来源', key: 'source', width: 110, render: row => row.source === 'GEOFENCE' ? '电子围栏' : 'JT/T 808' },
  { title: '类型', key: 'type', minWidth: 130, ellipsis: { tooltip: true } },
  { title: '发生时间', key: 'occurredAt', width: 168, render: row => formatConsoleTime(row.occurredAt) },
  { title: '最后出现', key: 'lastOccurredAt', width: 168, render: row => formatConsoleTime(row.lastOccurredAt) },
  {
    title: '操作', key: 'actions', width: 210, fixed: 'right',
    render: row => h(NSpace, { size: 4, wrap: false }, () => [
      h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) }, () => '详情'),
      row.status === 'OPEN'
        ? h(NButton, { text: true, type: 'warning', size: 'small', onClick: () => openAction(row, 'acknowledge') }, () => '确认')
        : null,
      row.status !== 'CLOSED'
        ? h(NButton, { text: true, type: 'error', size: 'small', onClick: () => openAction(row, 'close') }, () => '关闭')
        : null,
      h(NButton, { text: true, size: 'small', onClick: () => openVehicle(row.deviceId) }, () => '车辆')
    ])
  }
];

onMounted(async () => {
  const vehicleResult = await fetchVehicles();
  vehicles.value = vehicleResult.data ?? [];
  const requestedDevice = typeof route.query.device === 'string' ? route.query.device : '';
  if (requestedDevice) filters.deviceId = requestedDevice;
  await load();
  const alarmId = Number(route.query.alarm);
  if (Number.isSafeInteger(alarmId) && alarmId > 0) await openAlarmById(alarmId);
});

watch(
  () => route.query.device,
  value => {
    const requestedDevice = typeof value === 'string' ? value : null;
    if (requestedDevice === filters.deviceId) return;
    filters.deviceId = requestedDevice;
    filters.page = 1;
    void load();
  }
);

function queryParams(): AlarmQuery {
  return {
    keyword: filters.keyword.trim() || undefined,
    deviceId: filters.deviceId || undefined,
    type: filters.type.trim() || undefined,
    status: filters.status || undefined,
    level: filters.level || undefined,
    source: filters.source || undefined,
    start: filters.range ? new Date(filters.range[0]).toISOString() : undefined,
    end: filters.range ? new Date(filters.range[1]).toISOString() : undefined,
    page: filters.page,
    pageSize: filters.pageSize
  };
}

async function load() {
  loading.value = true;
  const result = await fetchAlarms(queryParams());
  loading.value = false;
  if (result.error) {
    message.error(result.error.message || '告警列表加载失败');
    return;
  }
  const page = normalizeAlarmPage(result.data);
  rows.value = page.items;
  total.value = page.total;
  filters.page = page.page;
  filters.pageSize = page.pageSize;
}

function search() {
  filters.page = 1;
  void load();
}

function reset() {
  Object.assign(filters, {
    keyword: '', deviceId: null, type: '', status: null, level: null, source: null, range: null, page: 1
  });
  void load();
}

function openDetail(alarm: AlarmEvent) {
  currentAlarm.value = alarm;
  detailVisible.value = true;
  void loadNearbyPhotos(alarm);
  void loadNearbyRecordings(alarm);
  void router.replace({ query: { ...route.query, alarm: String(alarm.id) } });
}

/**
 * 该时段抓拍。
 *
 * 失败静默：抓拍是锦上添花，加载不出来就当没有。为它弹一个错误提示会让人以为告警详情坏了。
 */
async function loadNearbyPhotos(alarm: AlarmEvent) {
  nearbyPhotos.value = [];
  try {
    const { data } = await fetchMediaAround(alarm.deviceId, alarm.occurredAt, 6);
    nearbyPhotos.value = data ?? [];
  } catch {
    nearbyPhotos.value = [];
  }
}

async function loadNearbyRecordings(alarm: AlarmEvent) {
  nearbyRecordings.value = [];
  if (!authStore.hasPermission('recording:search') || !authStore.hasPermission('recording:playback')) return;
  try {
    const { data } = await fetchRecordingsAround(alarm.deviceId, alarm.occurredAt);
    nearbyRecordings.value = data ?? [];
  } catch {
    nearbyRecordings.value = [];
  }
}

function openNearbyRecording(alarm: AlarmEvent) {
  const at = dayjs(alarm.occurredAt);
  void router.push({
    name: 'recording',
    query: {
      deviceId: alarm.deviceId,
      startTime: at.subtract(5, 'minute').toISOString(),
      endTime: at.add(5, 'minute').toISOString(),
      autoplay: '1'
    }
  });
}

async function openAlarmById(id: number) {
  const { data, error } = await fetchAlarm(id);
  if (error || !data) {
    message.error(error?.message || '告警详情加载失败');
    return;
  }
  openDetail(data);
}

function closeDetail() {
  detailVisible.value = false;
  const query = { ...route.query };
  delete query.alarm;
  void router.replace({ query });
}

function openAction(alarm: AlarmEvent, type: 'acknowledge' | 'close') {
  currentAlarm.value = alarm;
  actionType.value = type;
  actionNote.value = '';
  actionVisible.value = true;
}

async function submitAction() {
  if (!currentAlarm.value) return;
  const note = actionNote.value.trim();
  if (!note) {
    message.warning('请填写处置备注');
    return;
  }
  actionSubmitting.value = true;
  const result = actionType.value === 'acknowledge'
    ? await acknowledgeAlarm(currentAlarm.value.id, note)
    : await closeAlarm(currentAlarm.value.id, note);
  actionSubmitting.value = false;
  if (result.error || !result.data) {
    message.error(result.error?.message || '告警处置失败');
    return;
  }
  currentAlarm.value = result.data;
  actionVisible.value = false;
  message.success(actionType.value === 'acknowledge' ? '告警已确认' : '告警已关闭');
  await load();
}

function openVehicle(deviceId: string) {
  if (detailVisible.value) closeDetail();
  profileDeviceId.value = deviceId;
  profileVisible.value = true;
}

function locateVehicle() {
  if (!currentAlarm.value) return;
  detailVisible.value = false;
  void router.push({ name: 'monitor', query: { device: currentAlarm.value.deviceId } });
}
</script>

<template>
  <div class="flex flex-col gap-12px">
    <NCard :bordered="false" size="small">
      <div class="filter-grid">
        <NInput v-model:value="filters.keyword" clearable placeholder="车牌、终端号或告警标题" @keyup.enter="search" />
        <NSelect v-model:value="filters.status" :options="statusOptions" clearable placeholder="状态" />
        <NSelect v-model:value="filters.level" :options="levelOptions" clearable placeholder="级别" />
        <NSelect v-model:value="filters.source" :options="sourceOptions" clearable placeholder="来源" />
        <NSelect v-model:value="filters.deviceId" :options="vehicleOptions" filterable clearable placeholder="车辆" />
        <NInput v-model:value="filters.type" clearable placeholder="告警类型" @keyup.enter="search" />
        <NDatePicker v-model:value="filters.range" type="datetimerange" clearable class="date-filter" />
        <NSpace justify="end" wrap>
          <NButton @click="reset">
            <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
            重置
          </NButton>
          <NButton type="primary" :loading="loading" @click="search">
            <template #icon><SvgIcon icon="lucide:search" /></template>
            查询
          </NButton>
        </NSpace>
      </div>
    </NCard>

    <NCard title="告警中心" :bordered="false" size="small">
      <template #header-extra>
        <NButton quaternary circle :loading="loading" title="刷新" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
      </template>
      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="row => row.id"
        :scroll-x="1298"
        remote
        size="small"
      />
      <NEmpty v-if="!loading && rows.length === 0" description="没有匹配的告警" class="py-40px" />
    </NCard>
  </div>

  <NDrawer :show="detailVisible" :width="appStore.isMobile ? '100%' : 500" placement="right" @update:show="closeDetail">
    <NDrawerContent :title="currentAlarm?.title || '告警详情'" closable>
      <template v-if="currentAlarm">
        <NSpace class="mb-12px" wrap>
          <NTag :type="alarmLevelTagType(currentAlarm.level)">{{ alarmLevelLabel(currentAlarm.level) }}</NTag>
          <NTag :type="alarmStatusTagType(currentAlarm.status)" :bordered="false">
            {{ alarmStatusLabel(currentAlarm.status) }}
          </NTag>
          <NTag>{{ currentAlarm.source === 'GEOFENCE' ? '电子围栏' : 'JT/T 808' }}</NTag>
        </NSpace>
        <NDescriptions bordered label-placement="left" :column="1" size="small">
          <NDescriptionsItem label="车辆">{{ currentAlarm.plateNo || '未建档' }} ({{ currentAlarm.deviceId }})</NDescriptionsItem>
          <NDescriptionsItem label="类型">{{ currentAlarm.type }}</NDescriptionsItem>
          <NDescriptionsItem label="发生时间">{{ formatConsoleTime(currentAlarm.occurredAt) }}</NDescriptionsItem>
          <NDescriptionsItem label="最后出现">{{ formatConsoleTime(currentAlarm.lastOccurredAt) }}</NDescriptionsItem>
          <NDescriptionsItem label="围栏">{{ currentAlarm.geofenceName || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="位置">
            {{ currentAlarm.gcjLat == null ? '-' : `${currentAlarm.gcjLat.toFixed(6)}, ${currentAlarm.gcjLng?.toFixed(6)}` }}
          </NDescriptionsItem>
          <NDescriptionsItem label="确认记录">
            {{ currentAlarm.acknowledgeNote || '-' }}
            <div v-if="currentAlarm.acknowledgedAt" class="text-12px text-gray-500">
              {{ currentAlarm.acknowledgedBy }} · {{ formatConsoleTime(currentAlarm.acknowledgedAt) }}
            </div>
          </NDescriptionsItem>
          <NDescriptionsItem label="关闭记录">
            {{ currentAlarm.closeNote || '-' }}
            <div v-if="currentAlarm.closedAt" class="text-12px text-gray-500">
              {{ currentAlarm.closedBy }} · {{ formatConsoleTime(currentAlarm.closedAt) }}
            </div>
          </NDescriptionsItem>
        </NDescriptions>

        <div v-if="nearbyRecordings.length" class="mt-16px">
          <NButton type="primary" secondary @click="openNearbyRecording(currentAlarm)">
            <template #icon><SvgIcon icon="lucide:video" /></template>
            该时段录像
          </NButton>
        </div>

        <!--
          该时段的抓拍。
          刻意不写「该告警的抓拍」：0x0801 只带事件项编码（平台指令/定时/抢劫/碰撞侧翻），
          没有任何字段能定位到具体某条告警，所以这里给的是时间邻近的照片，不是因果关系。
          没有照片时整块不出现，而不是显示一个空区域。
        -->
        <div v-if="nearbyPhotos.length" class="mt-16px">
          <p class="mb-8px text-13px text-gray-600 dark:text-gray-300">
            该时段（前后 90 秒）该车的抓拍
          </p>
          <div class="grid grid-cols-3 gap-8px">
            <a
              v-for="photo in nearbyPhotos"
              :key="photo.id"
              :href="photo.accessAddress ?? undefined"
              target="_blank"
              rel="noopener noreferrer"
              class="block overflow-hidden rd-4px"
            >
              <img
                v-if="photo.accessAddress"
                :src="photo.accessAddress"
                :alt="`${photo.deviceId} 于 ${photo.capturedAt} 的抓拍`"
                class="aspect-video w-full bg-gray-100 object-cover dark:bg-gray-800"
                loading="lazy"
              />
              <div v-else class="aspect-video w-full flex-center bg-gray-100 text-11px text-gray-500 dark:bg-gray-800">
                无访问地址
              </div>
            </a>
          </div>
        </div>
      </template>
      <template #footer>
        <NSpace v-if="currentAlarm" justify="end" wrap>
          <NButton @click="openVehicle(currentAlarm.deviceId)">
            <template #icon><SvgIcon icon="lucide:car-front" /></template>
            车辆详情
          </NButton>
          <NButton @click="locateVehicle">
            <template #icon><SvgIcon icon="lucide:map-pin" /></template>
            定位
          </NButton>
          <NButton v-if="currentAlarm.status === 'OPEN'" type="warning" @click="openAction(currentAlarm, 'acknowledge')">确认</NButton>
          <NButton v-if="currentAlarm.status !== 'CLOSED'" type="error" @click="openAction(currentAlarm, 'close')">关闭</NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>

  <NModal v-model:show="actionVisible" preset="card" class="w-480px max-w-[calc(100vw-24px)]" :title="actionType === 'acknowledge' ? '确认告警' : '关闭告警'">
    <NFormItem label="处置备注" required>
      <NInput v-model:value="actionNote" type="textarea" :rows="4" maxlength="500" show-count placeholder="记录核实情况和处置结果" />
    </NFormItem>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="actionVisible = false">取消</NButton>
        <NButton :type="actionType === 'acknowledge' ? 'warning' : 'error'" :loading="actionSubmitting" @click="submitAction">
          确认提交
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <VehicleProfileDrawer v-model:visible="profileVisible" :device-id="profileDeviceId" />
</template>

<style scoped>
.filter-grid {
  display: grid;
  grid-template-columns: minmax(190px, 1.5fr) repeat(5, minmax(120px, 1fr)) minmax(280px, 1.6fr) auto;
  gap: 10px;
  align-items: center;
}

@media (max-width: 1280px) {
  .filter-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .date-filter { grid-column: span 2; width: 100%; }
}

@media (max-width: 640px) {
  .filter-grid { grid-template-columns: 1fr; }
  .date-filter { grid-column: auto; }
}
</style>
