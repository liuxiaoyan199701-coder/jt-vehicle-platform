<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { NButton, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  fetchDeviceLogs,
  fetchVehicles,
  type DeviceLog,
  type DeviceLogDirection,
  type DeviceLogQuery,
  type Vehicle
} from '@/service/api';
import { formatConsoleTime } from '@/utils/fleet-operations';

defineOptions({ name: 'DeviceLogIndex' });

const route = useRoute();
const message = useMessage();
const loading = ref(false);
const rows = ref<DeviceLog[]>([]);
const total = ref(0);
const vehicles = ref<Vehicle[]>([]);
const expandedKeys = ref<number[]>([]);

const filters = reactive<{
  deviceId: string | null;
  direction: DeviceLogDirection | null;
  msgId: string;
  keyword: string;
  range: [number, number] | null;
  page: number;
  pageSize: number;
}>({
  deviceId: null,
  direction: null,
  msgId: '',
  keyword: '',
  range: null,
  page: 1,
  pageSize: 20
});

const directionOptions: { label: string; value: DeviceLogDirection }[] = [
  { label: '上行报文', value: 'UP' },
  { label: '下行指令', value: 'DOWN' },
  { label: '连接事件', value: 'CONNECTION' }
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
  pageSizes: [20, 50, 100],
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

const columns: DataTableColumns<DeviceLog> = [
  { type: 'expand', renderExpand: renderDetail },
  { title: '时间', key: 'logTime', width: 176, render: row => formatConsoleTime(row.logTime) },
  {
    title: '方向',
    key: 'direction',
    width: 96,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: directionTagType(row.direction) }, () =>
        directionLabel(row.direction)
      )
  },
  { title: '消息 ID', key: 'msgIdHex', width: 100, render: row => row.msgIdHex ?? '-' },
  { title: '流水号', key: 'serialNo', width: 88, render: row => row.serialNo ?? '-' },
  { title: '概要', key: 'summary', minWidth: 200, ellipsis: { tooltip: true } },
  {
    title: '标记',
    key: 'flags',
    width: 120,
    render: row => [
      row.decodeError ? h(NTag, { size: 'small', type: 'error', bordered: false }, () => '解码失败') : null,
      row.truncated ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => '已截断') : null
    ]
  },
  { title: '采集节点', key: 'instanceId', width: 120, render: row => row.instanceId ?? '-' }
];

onMounted(async () => {
  const vehicleResult = await fetchVehicles();
  vehicles.value = vehicleResult.data ?? [];
  const requestedDevice = typeof route.query.device === 'string' ? route.query.device : '';
  if (requestedDevice) filters.deviceId = requestedDevice;
  if (filters.deviceId) await load();
});

watch(
  () => route.query.device,
  value => {
    const requestedDevice = typeof value === 'string' ? value : null;
    if (requestedDevice === filters.deviceId) return;
    filters.deviceId = requestedDevice;
    filters.page = 1;
    if (filters.deviceId) void load();
  }
);

function queryParams(): DeviceLogQuery {
  return {
    deviceId: filters.deviceId ?? '',
    direction: filters.direction ?? undefined,
    msgId: filters.msgId.trim() || undefined,
    keyword: filters.keyword.trim() || undefined,
    start: filters.range ? new Date(filters.range[0]).toISOString() : undefined,
    end: filters.range ? new Date(filters.range[1]).toISOString() : undefined,
    page: filters.page,
    pageSize: filters.pageSize
  };
}

/**
 * 设备号必选。
 *
 * 不是为了少写一个筛选框：日志表按「设备 + 时间」建索引，不带设备号的查询会退化成全表扫，
 * 而跨设备翻报文本来也不是排障的做法。所以这里拦在前端，给出的是提示而不是一次空查询。
 */
async function load() {
  if (!filters.deviceId) {
    message.warning('请先选择要查看日志的车辆');
    return;
  }
  loading.value = true;
  const result = await fetchDeviceLogs(queryParams());
  loading.value = false;
  if (result.error) {
    message.error(result.error.message || '设备日志加载失败');
    return;
  }
  const page = result.data;
  rows.value = page?.items ?? [];
  total.value = page?.total ?? 0;
  expandedKeys.value = [];
  if (page?.page) filters.page = page.page;
  if (page?.pageSize) filters.pageSize = page.pageSize;
}

function search() {
  filters.page = 1;
  void load();
}

function reset() {
  Object.assign(filters, { direction: null, msgId: '', keyword: '', range: null, page: 1 });
  void load();
}

function directionLabel(direction: DeviceLogDirection) {
  return directionOptions.find(option => option.value === direction)?.label ?? direction;
}

function directionTagType(direction: DeviceLogDirection) {
  if (direction === 'DOWN') return 'warning' as const;
  if (direction === 'CONNECTION') return 'info' as const;
  return 'success' as const;
}

/** 解析 JSON 原样存的是一行紧凑文本，展开时缩进一下才看得清结构；解析不了就照原样显示。 */
function prettyJson(value: string | null) {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function renderDetail(row: DeviceLog) {
  return h('div', { class: 'log-detail' }, [
    h('div', { class: 'log-detail-block' }, [
      h('div', { class: 'log-detail-title' }, row.truncated ? '原始报文（已截断）' : '原始报文'),
      h('pre', { class: 'log-detail-body' }, row.rawHex || '（无原始报文：连接事件与含一次性口令的指令不留 hex）')
    ]),
    h('div', { class: 'log-detail-block' }, [
      h('div', { class: 'log-detail-title' }, '解析结果'),
      h(
        'pre',
        { class: 'log-detail-body' },
        prettyJson(row.parsedJson) || (row.decodeError ? '（该帧解码失败，只有原始字节可信）' : '（无解析结果）')
      )
    ])
  ]);
}
</script>

<template>
  <div class="flex flex-col gap-12px">
    <NCard :bordered="false" size="small">
      <div class="filter-grid">
        <NSelect v-model:value="filters.deviceId" :options="vehicleOptions" filterable clearable placeholder="车辆（必选）" />
        <NSelect v-model:value="filters.direction" :options="directionOptions" clearable placeholder="方向" />
        <NInput v-model:value="filters.msgId" clearable placeholder="消息 ID，如 0x0200" @keyup.enter="search" />
        <NInput v-model:value="filters.keyword" clearable placeholder="概要或解析内容关键字" @keyup.enter="search" />
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

    <NCard title="设备日志" :bordered="false" size="small">
      <template #header-extra>
        <NButton quaternary circle :loading="loading" title="刷新" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
      </template>
      <NDataTable
        v-model:expanded-row-keys="expandedKeys"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="row => row.id"
        :scroll-x="1000"
        remote
        size="small"
      />
      <NEmpty
        v-if="!loading && rows.length === 0"
        :description="filters.deviceId ? '该设备在此条件下没有日志' : '请选择车辆后查询'"
        class="py-40px"
      />
    </NCard>
  </div>
</template>

<style scoped>
.filter-grid {
  display: grid;
  grid-template-columns: minmax(190px, 1.5fr) minmax(120px, 1fr) minmax(140px, 1fr) minmax(180px, 1.4fr) minmax(280px, 1.6fr) auto;
  gap: 10px;
  align-items: center;
}

.log-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.log-detail-title {
  margin-bottom: 4px;
  font-size: 12px;
  color: #888;
}

.log-detail-body {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

@media (max-width: 1280px) {
  .filter-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .date-filter { grid-column: span 2; width: 100%; }
}

@media (max-width: 900px) {
  .log-detail { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .filter-grid { grid-template-columns: 1fr; }
  .date-filter { grid-column: auto; }
}
</style>
