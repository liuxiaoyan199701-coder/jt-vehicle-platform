<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  archiveTerminal,
  fetchTerminals,
  type TerminalQuery,
  type TerminalSummary,
  type Vehicle
} from '@/service/api';
import { formatConsoleTime } from '@/utils/fleet-operations';

defineOptions({ name: 'TerminalIndex' });

const message = useMessage();
const loading = ref(false);
const rows = ref<TerminalSummary[]>([]);
const total = ref(0);

const archiveVisible = ref(false);
const archiving = ref(false);
const current = ref<TerminalSummary | null>(null);
const form = reactive<{
  plateNo: string;
  plateColor: string;
  brand: string;
  channelCount: number;
  remark: string;
}>({ plateNo: '', plateColor: '', brand: '', channelCount: 1, remark: '' });

// NSelect 的 value 不接受 boolean，筛选项用字符串承载，到 queryParams 再映射回三态。
type YesNo = 'yes' | 'no';

const filters = reactive<{
  keyword: string;
  archived: YesNo | null;
  online: YesNo | null;
  range: [number, number] | null;
  page: number;
  pageSize: number;
}>({ keyword: '', archived: null, online: null, range: null, page: 1, pageSize: 20 });

const archivedOptions: { label: string; value: YesNo }[] = [
  { label: '已建档', value: 'yes' },
  { label: '未建档', value: 'no' }
];
const onlineOptions: { label: string; value: YesNo }[] = [
  { label: '在线', value: 'yes' },
  { label: '离线', value: 'no' }
];

/** 三态：未选为 undefined（不筛选），选了才变成 true/false。 */
function tristate(value: YesNo | null) {
  return value === null ? undefined : value === 'yes';
}

const pagination = computed(() => ({
  page: filters.page,
  pageSize: filters.pageSize,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: ({ itemCount }: { itemCount?: number }) => `共 ${itemCount ?? 0} 台`,
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

const columns: DataTableColumns<TerminalSummary> = [
  {
    title: '终端手机号',
    key: 'deviceId',
    width: 150,
    fixed: 'left',
    render: row =>
      h('div', [
        h('div', { class: 'font-medium' }, row.deviceId),
        h('div', { class: 'text-12px text-gray-500' }, `终端 ID ${row.terminalId ?? '-'}`)
      ])
  },
  {
    title: '建档状态',
    key: 'archived',
    width: 96,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: row.archived ? 'success' : 'warning' }, () =>
        row.archived ? '已建档' : '未建档'
      )
  },
  {
    title: '档案车牌',
    key: 'plateNo',
    width: 130,
    render: row => row.plateNo ?? '—'
  },
  {
    // 与档案车牌刻意分成两列：自报值是终端自己填的，可以是错的。合并之后
    // 使用者就无从判断眼前这个车牌可不可信，而这正是建档时最需要知道的。
    title: '自报车牌',
    key: 'reportedPlate',
    width: 130,
    render: row =>
      h(
        'span',
        { class: row.plateNo && row.reportedPlate !== row.plateNo ? 'text-warning' : '' },
        row.reportedPlate ?? '—'
      )
  },
  { title: '终端型号', key: 'deviceModel', width: 130, render: row => row.deviceModel ?? '—' },
  { title: '制造商', key: 'makerId', width: 100, render: row => row.makerId ?? '—' },
  {
    title: '在线',
    key: 'online',
    width: 80,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: row.online ? 'success' : 'default' }, () =>
        row.online ? '在线' : '离线'
      )
  },
  {
    // 措辞不能写成「最近在线」：台账只在注册/鉴权时更新，长连不断的终端不会刷新它。
    title: '最近注册/鉴权',
    key: 'lastSeenAt',
    width: 168,
    render: row => formatConsoleTime(row.lastSeenAt)
  },
  { title: '首次发现', key: 'firstSeenAt', width: 168, render: row => formatConsoleTime(row.firstSeenAt) },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    fixed: 'right',
    render: row =>
      row.archived
        ? h('span', { class: 'text-12px text-gray-400' }, '已建档')
        : h(
            NButton,
            { text: true, type: 'primary', size: 'small', onClick: () => openArchive(row) },
            () => '建档'
          )
  }
];

onMounted(() => {
  void load();
});

function queryParams(): TerminalQuery {
  return {
    keyword: filters.keyword.trim() || undefined,
    archived: tristate(filters.archived),
    online: tristate(filters.online),
    start: filters.range ? new Date(filters.range[0]).toISOString() : undefined,
    end: filters.range ? new Date(filters.range[1]).toISOString() : undefined,
    page: filters.page,
    pageSize: filters.pageSize
  };
}

async function load() {
  loading.value = true;
  const result = await fetchTerminals(queryParams());
  loading.value = false;
  if (result.error) {
    message.error(result.error.message || '终端清单加载失败');
    return;
  }
  rows.value = result.data?.items ?? [];
  total.value = result.data?.total ?? 0;
  if (result.data?.page) filters.page = result.data.page;
  if (result.data?.pageSize) filters.pageSize = result.data.pageSize;
}

function search() {
  filters.page = 1;
  void load();
}

function reset() {
  Object.assign(filters, { keyword: '', archived: null, online: null, range: null, page: 1 });
  void load();
}

/** 用自报值预填，但只是预填——自报车牌可以是错的，必须经人确认才写进档案。 */
function openArchive(row: TerminalSummary) {
  current.value = row;
  Object.assign(form, {
    plateNo: row.reportedPlate ?? '',
    plateColor: '',
    brand: row.deviceModel ?? '',
    channelCount: 1,
    remark: ''
  });
  archiveVisible.value = true;
}

async function submitArchive() {
  if (!current.value) return;
  if (!form.plateNo.trim()) {
    message.warning('请填写车牌号');
    return;
  }
  archiving.value = true;
  const payload: Partial<Vehicle> = {
    plateNo: form.plateNo.trim(),
    plateColor: form.plateColor.trim() || undefined,
    brand: form.brand.trim() || undefined,
    channelCount: form.channelCount,
    remark: form.remark.trim() || undefined
  };
  const result = await archiveTerminal(current.value.deviceId, payload);
  archiving.value = false;
  if (result.error) {
    message.error(result.error.message || '建档失败');
    return;
  }
  message.success('建档成功');
  archiveVisible.value = false;
  void load();
}
</script>

<template>
  <div class="flex flex-col gap-12px">
    <NCard :bordered="false" size="small">
      <div class="filter-grid">
        <NInput
          v-model:value="filters.keyword"
          clearable
          placeholder="终端手机号、终端 ID、车牌或型号"
          @keyup.enter="search"
        />
        <NSelect v-model:value="filters.archived" :options="archivedOptions" clearable placeholder="建档状态" />
        <NSelect v-model:value="filters.online" :options="onlineOptions" clearable placeholder="在线状态" />
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

    <NCard title="终端管理" :bordered="false" size="small">
      <template #header-extra>
        <NButton quaternary circle :loading="loading" title="刷新" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
      </template>
      <NAlert type="info" :bordered="false" class="mb-12px">
        这里列出<strong>连接过网关</strong>的终端及其自报信息。终端在下一次注册或鉴权时入册，
        因此长期保持长连接、尚未重连过的终端可能还不在列表里。
      </NAlert>
      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="row => row.deviceId"
        :scroll-x="1300"
        remote
        size="small"
      />
      <NEmpty v-if="!loading && rows.length === 0" description="还没有终端连接过网关" class="py-40px" />
    </NCard>
  </div>

  <NModal
    v-model:show="archiveVisible"
    preset="card"
    title="建立车辆档案"
    class="w-520px max-w-90vw"
  >
    <NAlert v-if="current" type="warning" :bordered="false" class="mb-12px">
      车牌与型号来自<strong>终端自报</strong>，未经核实，请确认后再提交。
    </NAlert>
    <NForm label-placement="left" :label-width="80">
      <NFormItem label="终端手机号">
        <NInput :value="current?.deviceId ?? ''" disabled />
      </NFormItem>
      <NFormItem label="车牌号" required>
        <NInput v-model:value="form.plateNo" placeholder="请确认车牌号" />
      </NFormItem>
      <NFormItem label="车牌颜色">
        <NInput v-model:value="form.plateColor" placeholder="如 蓝色" />
      </NFormItem>
      <NFormItem label="品牌型号">
        <NInput v-model:value="form.brand" />
      </NFormItem>
      <NFormItem label="通道数">
        <NInputNumber v-model:value="form.channelCount" :min="1" :max="32" class="w-full" />
      </NFormItem>
      <NFormItem label="备注">
        <NInput v-model:value="form.remark" type="textarea" :rows="2" />
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="archiveVisible = false">取消</NButton>
        <NButton type="primary" :loading="archiving" @click="submitArchive">建档</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.filter-grid {
  display: grid;
  grid-template-columns: minmax(220px, 2fr) minmax(120px, 1fr) minmax(120px, 1fr) minmax(280px, 1.6fr) auto;
  gap: 10px;
  align-items: center;
}

.text-warning {
  color: #d97706;
}

@media (max-width: 1280px) {
  .filter-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .date-filter { grid-column: span 2; width: 100%; }
}

@media (max-width: 640px) {
  .filter-grid { grid-template-columns: 1fr; }
  .date-filter { grid-column: auto; }
}
</style>
