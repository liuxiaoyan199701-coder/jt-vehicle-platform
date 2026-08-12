<script setup lang="ts">
import { computed, h } from 'vue';
import { useRouter } from 'vue-router';
import { NButton, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import type { LiveStatus } from '@/service/api';

defineOptions({ name: 'ActiveVehicles' });

const props = defineProps<{ vehicles: LiveStatus[] }>();

const router = useRouter();

/** 最近上报的排在前面，没上报过的排最后 */
const rows = computed(() =>
  [...props.vehicles]
    .sort((a, b) => (b.lastSeenAt ?? '').localeCompare(a.lastSeenAt ?? ''))
    .slice(0, 8)
);

function formatTime(value: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

const columns: DataTableColumns<LiveStatus> = [
  {
    title: '车牌 / 终端号',
    key: 'plateNo',
    render: row =>
      h('div', [
        h('div', { class: 'font-medium' }, row.plateNo ?? '未建档'),
        h('div', { class: 'text-12px text-#999' }, row.deviceId)
      ])
  },
  {
    title: '状态',
    key: 'online',
    width: 90,
    render: row =>
      h(
        NTag,
        { type: row.online ? 'success' : 'default', size: 'small', round: true },
        () => (row.online ? '在线' : '离线')
      )
  },
  {
    title: '速度',
    key: 'speedKph',
    width: 100,
    render: row => (row.speedKph == null ? '-' : `${row.speedKph.toFixed(1)} km/h`)
  },
  {
    title: '最后上报',
    key: 'lastSeenAt',
    width: 150,
    render: row => formatTime(row.lastSeenAt)
  },
  {
    title: '',
    key: 'actions',
    width: 80,
    render: row =>
      h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          type: 'primary',
          onClick: () => router.push({ name: 'monitor', query: { device: row.deviceId } })
        },
        () => '定位'
      )
  }
];
</script>

<template>
  <NCard title="最近活跃车辆" :bordered="false" size="small" class="card-wrapper h-full">
    <template #header-extra>
      <NButton size="tiny" quaternary type="primary" @click="router.push({ name: 'monitor' })">
        进入监控
      </NButton>
    </template>
    <NDataTable
      :columns="columns"
      :data="rows"
      :row-key="row => row.deviceId"
      size="small"
      :bordered="false"
    />
    <NEmpty v-if="rows.length === 0" description="还没有设备上报数据" class="py-40px" />
  </NCard>
</template>
