<script setup lang="ts">
import { ref } from 'vue';
import { useMessage } from 'naive-ui';
import dayjs from 'dayjs';
import { fetchVehicleReport, type VehicleReportRow } from '@/service/api';

defineOptions({ name: 'ReportIndex' });

const message = useMessage();
const range = ref<[number, number] | null>([
  dayjs().subtract(30, 'day').valueOf(),
  dayjs().valueOf()
]);
const rows = ref<VehicleReportRow[]>([]);
const loading = ref(false);

const start = () => (range.value ? dayjs(range.value[0]).format('YYYY-MM-DD') : '');
const end = () => (range.value ? dayjs(range.value[1]).format('YYYY-MM-DD') : '');

async function load() {
  if (!range.value) {
    message.warning('请选择日期范围');
    return;
  }
  loading.value = true;
  const result = await fetchVehicleReport(start(), end());
  loading.value = false;
  if (result.error) {
    message.error(result.error.message || '报表加载失败');
    return;
  }
  rows.value = result.data ?? [];
}

function exportCsv() {
  if (!rows.value.length) {
    message.warning('没有可导出的数据');
    return;
  }
  const header = ['设备号', '车牌号', '总里程(km)', '活跃天数', '告警总数', '最高速(km/h)'];
  const lines = rows.value.map(r =>
    [r.deviceId, r.plateNo, r.totalDistanceKm, r.activeDays, r.totalAlarms, r.maxSpeedKph].join(',')
  );
  const csv = '\uFEFF' + [header.join(','), ...lines].join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `vehicle-report-${start()}-${end()}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <div class="p-16px">
    <NCard :bordered="false" size="small">
      <template #header>
        <div class="flex items-center justify-between gap-8px">
          <span>车辆运营报表</span>
          <NSpace>
            <NDatePicker v-model:value="range" type="daterange" clearable class="w-240px" />
            <NButton type="primary" size="small" @click="load">查询</NButton>
            <NButton size="small" @click="exportCsv">
              <template #icon><SvgIcon icon="lucide:download" /></template>
              导出 CSV
            </NButton>
          </NSpace>
        </div>
      </template>
      <NSpin :show="loading">
        <NEmpty v-if="!loading && rows.length === 0" description="该范围内暂无运营数据" class="py-40px" />
        <NDataTable
          :columns="[
            { title: '设备号', key: 'deviceId' },
            { title: '车牌号', key: 'plateNo' },
            { title: '总里程 (km)', key: 'totalDistanceKm', render: row => row.totalDistanceKm.toFixed(2) },
            { title: '活跃天数', key: 'activeDays' },
            { title: '告警总数', key: 'totalAlarms' },
            { title: '最高速 (km/h)', key: 'maxSpeedKph', render: row => row.maxSpeedKph.toFixed(1) }
          ]"
          :data="rows"
          :bordered="false"
          size="small"
        />
      </NSpin>
    </NCard>
  </div>
</template>
