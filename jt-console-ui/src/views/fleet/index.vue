<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NTag, useDialog, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  createFleet,
  deleteFleet,
  fetchFleet,
  fetchFleets,
  fetchVehicles,
  replaceFleetVehicles,
  updateFleet,
  type FleetDetails,
  type FleetListItem,
  type FleetMutation,
  type FleetMember,
  type Vehicle
} from '@/service/api';
import {
  buildFleetAssignmentMap,
  filterFleets,
  fleetMemberLinks,
  membershipChangeSummary
} from '@/utils/fleet-management';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';

defineOptions({ name: 'FleetIndex' });

const route = useRoute();
const router = useRouter();
const dialog = useDialog();
const message = useMessage();

const loading = ref(false);
const saving = ref(false);
const memberSaving = ref(false);
const keyword = ref('');
const fleets = ref<FleetListItem[]>([]);
const fleetDetails = ref<FleetDetails[]>([]);
const vehicles = ref<Vehicle[]>([]);
const selectedId = ref<number | null>(null);
const detail = ref<FleetDetails | null>(null);
const editorVisible = ref(false);
const memberEditorVisible = ref(false);
const editingId = ref<number | null>(null);
const selectedMemberIds = ref<string[]>([]);
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);

const form = reactive<FleetMutation>({
  code: '',
  name: '',
  manager: null,
  contactPhone: null,
  remark: null
});

const visibleFleets = computed(() =>
  filterFleets(
    fleets.value.map(item => ({
      ...item,
      code: item.fleet.code,
      name: item.fleet.name,
      manager: item.fleet.manager,
      contact: item.fleet.contactPhone
    })),
    keyword.value
  )
);

const assignmentMap = computed(() =>
  buildFleetAssignmentMap(
    fleetDetails.value.map(item => ({
      id: item.fleet.id,
      code: item.fleet.code,
      name: item.fleet.name,
      members: item.members.map(member => ({ deviceId: member.vehicle.deviceId }))
    }))
  )
);

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => {
    const assigned = assignmentMap.value.get(vehicle.deviceId);
    return {
      label: `${vehicle.plateNo} (${vehicle.deviceId})${assigned ? ` · ${assigned.fleetName}` : ' · 未分配'}`,
      value: vehicle.deviceId
    };
  })
);

const memberChange = computed(() =>
  membershipChangeSummary(
    detail.value?.members.map(member => member.vehicle.deviceId) ?? [],
    selectedMemberIds.value,
    assignmentMap.value,
    selectedId.value ?? 0
  )
);

const memberColumns: DataTableColumns<FleetMember> = [
  {
    title: '车辆',
    key: 'plateNo',
    minWidth: 150,
    fixed: 'left',
    render: row => h('div', [
      h('div', { class: 'font-medium' }, row.vehicle.plateNo),
      h('div', { class: 'text-12px text-gray-500' }, row.vehicle.deviceId)
    ])
  },
  {
    title: '状态',
    key: 'online',
    width: 92,
    render: row => h(NTag, {
      size: 'small',
      bordered: false,
      type: row.online ? (Number(row.speedKph ?? 0) > 5 ? 'success' : 'info') : 'default'
    }, () => row.online ? (Number(row.speedKph ?? 0) > 5 ? '行驶' : '静止') : '离线')
  },
  { title: '速度', key: 'speedKph', width: 100, render: row => row.speedKph == null ? '-' : `${row.speedKph.toFixed(1)} km/h` },
  { title: '今日里程', key: 'todayDistanceKm', width: 105, render: row => `${row.todayDistanceKm.toFixed(1)} km` },
  {
    title: '未关闭告警',
    key: 'openAlarmCount',
    width: 105,
    render: row => row.openAlarmCount
      ? h(NTag, { type: 'error', size: 'small', bordered: false }, () => `${row.openAlarmCount} 条`)
      : '0'
  },
  { title: '最后上报', key: 'lastSeenAt', width: 168, render: row => formatTime(row.lastSeenAt) },
  {
    title: '操作',
    key: 'actions',
    width: 245,
    fixed: 'right',
    render: row => {
      const links = fleetMemberLinks(row.vehicle.deviceId);
      return h('div', { class: 'flex items-center gap-8px whitespace-nowrap' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openProfile(row.vehicle.deviceId) }, () => '详情'),
        h(NButton, { text: true, size: 'small', onClick: () => void router.push(links.monitor) }, () => '监控'),
        h(NButton, { text: true, size: 'small', onClick: () => void router.push(links.track) }, () => '轨迹'),
        h(NButton, { text: true, type: row.openAlarmCount ? 'error' : 'default', size: 'small', onClick: () => void router.push(links.alarm) }, () => '告警')
      ]);
    }
  }
];

onMounted(load);

watch(
  () => route.query.fleet,
  value => {
    const id = parseFleetId(value);
    if (id && fleets.value.some(item => item.fleet.id === id) && id !== selectedId.value) selectFleet(id, false);
  }
);

async function load() {
  loading.value = true;
  const [fleetResult, vehicleResult] = await Promise.all([fetchFleets(), fetchVehicles()]);
  if (fleetResult.error) message.error(fleetResult.error.message || '车队列表加载失败');
  if (vehicleResult.error) message.error(vehicleResult.error.message || '车辆列表加载失败');
  fleets.value = fleetResult.data ?? [];
  vehicles.value = vehicleResult.data ?? [];

  const loaded = await Promise.all(fleets.value.map(item => fetchFleet(item.fleet.id)));
  fleetDetails.value = loaded.flatMap(result => result.data ? [result.data] : []);

  const requestedId = parseFleetId(route.query.fleet);
  const nextId = requestedId && fleets.value.some(item => item.fleet.id === requestedId)
    ? requestedId
    : selectedId.value && fleets.value.some(item => item.fleet.id === selectedId.value)
      ? selectedId.value
      : fleets.value[0]?.fleet.id ?? null;
  if (nextId) await selectFleet(nextId, requestedId !== nextId);
  else {
    selectedId.value = null;
    detail.value = null;
    const query = { ...route.query };
    delete query.fleet;
    await router.replace({ query });
  }
  loading.value = false;
}

async function selectFleet(id: number, syncRoute = true) {
  selectedId.value = id;
  detail.value = fleetDetails.value.find(item => item.fleet.id === id) ?? null;
  if (!detail.value) {
    const result = await fetchFleet(id);
    if (result.error || !result.data) {
      message.error(result.error?.message || '车队详情加载失败');
      return;
    }
    detail.value = result.data;
  }
  if (syncRoute && String(route.query.fleet ?? '') !== String(id)) {
    await router.replace({ query: { ...route.query, fleet: String(id) } });
  }
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, { code: '', name: '', manager: null, contactPhone: null, remark: null });
  editorVisible.value = true;
}

function openEdit() {
  if (!detail.value) return;
  editingId.value = detail.value.fleet.id;
  Object.assign(form, {
    code: detail.value.fleet.code,
    name: detail.value.fleet.name,
    manager: detail.value.fleet.manager,
    contactPhone: detail.value.fleet.contactPhone,
    remark: detail.value.fleet.remark
  });
  editorVisible.value = true;
}

async function saveFleet() {
  if (!form.code.trim() || !form.name.trim()) {
    message.warning('请填写车队编码和名称');
    return;
  }
  saving.value = true;
  const payload: FleetMutation = {
    code: form.code.trim(),
    name: form.name.trim(),
    manager: form.manager?.trim() || null,
    contactPhone: form.contactPhone?.trim() || null,
    remark: form.remark?.trim() || null
  };
  const result = editingId.value
    ? await updateFleet(editingId.value, payload)
    : await createFleet(payload);
  saving.value = false;
  if (result.error || !result.data) {
    message.error(result.error?.message || '车队保存失败');
    return;
  }
  editorVisible.value = false;
  selectedId.value = result.data.fleet.id;
  message.success(editingId.value ? '车队档案已更新' : '车队已创建');
  await load();
}

function confirmDelete() {
  if (!detail.value) return;
  const current = detail.value;
  dialog.warning({
    title: '删除车队',
    content: current.members.length
      ? `${current.fleet.name} 仍有 ${current.members.length} 辆车辆，请先移出或调拨。`
      : `确认删除 ${current.fleet.name}？`,
    positiveText: current.members.length ? '查看成员' : '删除',
    negativeText: '取消',
    onPositiveClick: current.members.length ? undefined : async () => {
      const result = await deleteFleet(current.fleet.id);
      if (result.error) {
        message.error(result.error.message || '车队删除失败');
        await load();
        return;
      }
      message.success('车队已删除');
      selectedId.value = null;
      await load();
    }
  });
}

function openMemberEditor() {
  if (!detail.value) return;
  selectedMemberIds.value = detail.value.members.map(member => member.vehicle.deviceId);
  memberEditorVisible.value = true;
}

function requestSaveMembers() {
  if (!detail.value) return;
  const summary = memberChange.value;
  const parts = [
    summary.added.length ? `加入 ${summary.added.length} 辆` : '',
    summary.removed.length ? `移出 ${summary.removed.length} 辆` : '',
    summary.transferred.length ? `其中跨车队调拨 ${summary.transferred.length} 辆` : ''
  ].filter(Boolean);
  const content = selectedMemberIds.value.length === 0
    ? `将移出 ${detail.value.fleet.name} 的全部车辆，车辆档案和历史数据会保留。`
    : parts.length ? `${parts.join('，')}。确认保存成员全集？` : '成员没有变化，确认保存？';
  dialog.warning({
    title: selectedMemberIds.value.length === 0 ? '清空车队成员' : '确认成员调整',
    content,
    positiveText: '确认保存',
    negativeText: '取消',
    onPositiveClick: saveMembers
  });
}

async function saveMembers() {
  if (!detail.value) return;
  memberSaving.value = true;
  const result = await replaceFleetVehicles(detail.value.fleet.id, selectedMemberIds.value);
  memberSaving.value = false;
  if (result.error || !result.data) {
    message.error(result.error?.message || '成员调整失败');
    await load();
    return;
  }
  memberEditorVisible.value = false;
  message.success('车队成员已更新');
  await load();
}

function openProfile(deviceId: string) {
  profileDeviceId.value = deviceId;
  profileVisible.value = true;
}

function parseFleetId(value: unknown) {
  const number = typeof value === 'string' ? Number(value) : Number.NaN;
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function formatTime(value: string | null) {
  if (!value) return '-';
  return value.replace('T', ' ').replace('Z', '').slice(0, 19);
}
</script>

<template>
  <div class="fleet-page">
    <section class="fleet-toolbar">
      <div>
        <h2>车队管理</h2>
        <p>维护运营分组、车辆归属和车队运行状态</p>
      </div>
      <div class="toolbar-actions">
        <NInput v-model:value="keyword" clearable placeholder="编码、名称、负责人或电话" class="search-input">
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
        <NButton quaternary circle :loading="loading" title="刷新" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新增车队
        </NButton>
      </div>
    </section>

    <div class="fleet-layout">
      <NCard title="车队列表" :bordered="false" size="small" class="fleet-list-panel">
        <template #header-extra><span class="text-12px text-gray-500">{{ visibleFleets.length }} 个</span></template>
        <NSpin :show="loading">
          <div v-if="visibleFleets.length" class="fleet-list">
            <button
              v-for="item in visibleFleets"
              :key="item.fleet.id"
              type="button"
              class="fleet-list-item"
              :class="{ active: item.fleet.id === selectedId }"
              @click="selectFleet(item.fleet.id)"
            >
              <span class="fleet-list-title">
                <span class="truncate font-medium">{{ item.fleet.name }}</span>
                <NTag size="tiny" :bordered="false">{{ item.fleet.code }}</NTag>
              </span>
              <span class="fleet-list-meta">
                <span>{{ item.totalVehicles }} 辆</span>
                <span class="text-success">在线 {{ item.online }}</span>
                <span :class="item.openAlarms ? 'text-error' : ''">告警 {{ item.openAlarms }}</span>
              </span>
            </button>
          </div>
          <NEmpty v-else :description="keyword ? '没有匹配的车队' : '暂无车队'" class="py-48px" />
        </NSpin>
      </NCard>

      <main v-if="detail" class="fleet-detail">
        <section class="detail-heading">
          <div class="min-w-0">
            <div class="flex items-center gap-8px">
              <h3 class="truncate">{{ detail.fleet.name }}</h3>
              <NTag size="small" :bordered="false">{{ detail.fleet.code }}</NTag>
            </div>
            <p>
              {{ detail.fleet.manager || '未设置负责人' }}
              <span v-if="detail.fleet.contactPhone"> · {{ detail.fleet.contactPhone }}</span>
              <span v-if="detail.fleet.remark"> · {{ detail.fleet.remark }}</span>
            </p>
          </div>
          <NSpace wrap>
            <NButton size="small" @click="openEdit">
              <template #icon><SvgIcon icon="lucide:pencil" /></template>
              编辑
            </NButton>
            <NButton size="small" type="error" secondary @click="confirmDelete">
              <template #icon><SvgIcon icon="lucide:trash-2" /></template>
              删除
            </NButton>
          </NSpace>
        </section>

        <section class="metric-band">
          <div class="metric"><span>车辆总数</span><b>{{ detail.summary.totalVehicles }}</b></div>
          <div class="metric success"><span>在线</span><b>{{ detail.summary.online }}</b></div>
          <div class="metric moving"><span>行驶</span><b>{{ detail.summary.moving }}</b></div>
          <div class="metric idle"><span>静止</span><b>{{ detail.summary.idle }}</b></div>
          <div class="metric"><span>离线</span><b>{{ detail.summary.offline }}</b></div>
          <div class="metric danger"><span>未关闭告警</span><b>{{ detail.summary.openAlarms }}</b></div>
          <div class="metric distance"><span>今日里程</span><b>{{ detail.summary.todayDistanceKm.toFixed(1) }} <small>km</small></b></div>
        </section>

        <NCard title="成员车辆" :bordered="false" size="small" class="member-panel">
          <template #header-extra>
            <NButton size="small" type="primary" @click="openMemberEditor">
              <template #icon><SvgIcon icon="lucide:arrow-right-left" /></template>
              调整成员
            </NButton>
          </template>
          <NDataTable
            :columns="memberColumns"
            :data="detail.members"
            :row-key="row => row.vehicle.deviceId"
            :pagination="detail.members.length > 15 ? { pageSize: 15 } : false"
            :scroll-x="1180"
            size="small"
          />
          <NEmpty v-if="detail.members.length === 0" description="该车队暂无车辆" class="py-44px" />
        </NCard>
      </main>

      <NCard v-else :bordered="false" class="empty-detail">
        <NEmpty description="选择或创建车队后查看详情" />
      </NCard>
    </div>

    <NModal
      v-model:show="editorVisible"
      preset="card"
      :title="editingId ? '编辑车队' : '新增车队'"
      class="w-560px max-w-[calc(100vw-24px)]"
    >
      <NForm label-placement="left" :label-width="90">
        <NFormItem label="车队编码" required>
          <NInput v-model:value="form.code" maxlength="32" show-count placeholder="如 NORTH-01" />
        </NFormItem>
        <NFormItem label="车队名称" required>
          <NInput v-model:value="form.name" maxlength="100" show-count placeholder="如 北区配送车队" />
        </NFormItem>
        <NFormItem label="负责人">
          <NInput v-model:value="form.manager" maxlength="50" show-count placeholder="选填" />
        </NFormItem>
        <NFormItem label="联系电话">
          <NInput v-model:value="form.contactPhone" maxlength="50" show-count placeholder="选填" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="form.remark" type="textarea" :rows="3" maxlength="500" show-count placeholder="选填" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="saveFleet">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="memberEditorVisible"
      preset="card"
      title="调整车队成员"
      class="w-680px max-w-[calc(100vw-24px)]"
    >
      <NAlert type="info" :bordered="false" class="mb-16px">
        这里保存的是车队完整成员集合。选择其他车队的车辆会直接调拨，移出的车辆仍保留档案和全部历史数据。
      </NAlert>
      <NSelect
        v-model:value="selectedMemberIds"
        :options="vehicleOptions"
        multiple
        filterable
        clearable
        :max-tag-count="'responsive'"
        placeholder="选择已建档车辆"
      />
      <div class="mt-12px flex flex-wrap gap-12px text-13px text-gray-500">
        <span>保存后 {{ selectedMemberIds.length }} 辆</span>
        <span v-if="memberChange.added.length" class="text-success">加入 {{ memberChange.added.length }}</span>
        <span v-if="memberChange.removed.length" class="text-warning">移出 {{ memberChange.removed.length }}</span>
        <span v-if="memberChange.transferred.length" class="text-error">跨队调拨 {{ memberChange.transferred.length }}</span>
      </div>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="memberEditorVisible = false">取消</NButton>
          <NButton type="primary" :loading="memberSaving" @click="requestSaveMembers">保存成员</NButton>
        </NSpace>
      </template>
    </NModal>

    <VehicleProfileDrawer v-model:visible="profileVisible" :device-id="profileDeviceId" />
  </div>
</template>

<style scoped>
.fleet-page { display: flex; min-height: 100%; flex-direction: column; gap: 12px; }
.fleet-toolbar, .detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.fleet-toolbar { padding: 2px 4px; }
.fleet-toolbar h2, .detail-heading h3 { margin: 0; font-size: 20px; line-height: 28px; letter-spacing: 0; }
.fleet-toolbar p, .detail-heading p { margin: 3px 0 0; color: #737373; font-size: 13px; }
.toolbar-actions { display: flex; align-items: center; gap: 8px; }
.search-input { width: 280px; }
.fleet-layout { display: grid; min-height: 0; flex: 1; grid-template-columns: minmax(280px, 340px) minmax(0, 1fr); gap: 12px; }
.fleet-list-panel, .member-panel, .empty-detail { border-radius: 6px; }
.fleet-list { display: flex; flex-direction: column; gap: 6px; }
.fleet-list-item { width: 100%; min-height: 72px; border: 1px solid transparent; border-radius: 5px; background: #f7f7f8; padding: 11px 12px; color: inherit; text-align: left; cursor: pointer; transition: border-color .15s, background-color .15s; }
.fleet-list-item:hover { border-color: rgba(24, 160, 88, .35); }
.fleet-list-item.active { border-color: #18a058; background: rgba(24, 160, 88, .08); }
.fleet-list-title, .fleet-list-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.fleet-list-meta { margin-top: 8px; justify-content: flex-start; font-size: 12px; color: #737373; }
.fleet-detail { display: flex; min-width: 0; flex-direction: column; gap: 12px; }
.detail-heading { min-height: 70px; padding: 10px 4px; }
.metric-band { display: grid; overflow: hidden; border: 1px solid rgba(128, 128, 128, .18); border-radius: 6px; background: var(--n-color, #fff); grid-template-columns: repeat(7, minmax(90px, 1fr)); }
.metric { min-width: 0; padding: 14px 16px; border-right: 1px solid rgba(128, 128, 128, .16); }
.metric:last-child { border-right: 0; }
.metric span { display: block; overflow: hidden; color: #737373; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.metric b { display: block; margin-top: 5px; font-size: 22px; line-height: 28px; letter-spacing: 0; }
.metric small { font-size: 12px; font-weight: 500; }
.metric.success b, .metric.moving b { color: #18a058; }
.metric.idle b { color: #2080f0; }
.metric.danger b { color: #d03050; }
.metric.distance b { color: #8a5a00; }
.member-panel { min-width: 0; flex: 1; }
.empty-detail { display: flex; min-height: 360px; align-items: center; justify-content: center; }

@media (max-width: 1050px) {
  .metric-band { grid-template-columns: repeat(4, minmax(100px, 1fr)); }
  .metric { border-bottom: 1px solid rgba(128, 128, 128, .16); }
}

@media (max-width: 760px) {
  .fleet-toolbar, .detail-heading { align-items: stretch; flex-direction: column; }
  .toolbar-actions { flex-wrap: wrap; }
  .search-input { min-width: 0; flex: 1 1 220px; width: auto; }
  .fleet-layout { grid-template-columns: minmax(0, 1fr); }
  .fleet-list-panel { max-height: 300px; overflow: auto; }
  .metric-band { grid-template-columns: repeat(2, minmax(110px, 1fr)); }
  .metric:nth-child(2n) { border-right: 0; }
}
</style>
