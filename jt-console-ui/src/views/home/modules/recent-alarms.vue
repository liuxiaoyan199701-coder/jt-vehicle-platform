<script setup lang="ts">
import { h } from 'vue';
import { NButton, NSpace, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import type { AlarmEvent } from '@/service/api';
import {
  alarmLevelLabel,
  alarmLevelTagType,
  alarmStatusLabel,
  alarmStatusTagType,
  formatConsoleTime
} from '@/utils/fleet-operations';

defineOptions({ name: 'RecentAlarms' });
defineProps<{ alarms: AlarmEvent[] }>();
const emit = defineEmits<{
  'open-alarm': [alarm: AlarmEvent];
  'open-vehicle': [deviceId: string];
}>();

const columns: DataTableColumns<AlarmEvent> = [
  {
    title: '车辆',
    key: 'deviceId',
    minWidth: 150,
    render: row => h('div', [
      h('div', { class: 'font-medium' }, row.plateNo || '未建档'),
      h('div', { class: 'text-12px text-gray-500' }, row.deviceId)
    ])
  },
  { title: '告警', key: 'title', minWidth: 160, ellipsis: { tooltip: true } },
  {
    title: '级别', key: 'level', width: 80,
    render: row => h(NTag, { size: 'small', type: alarmLevelTagType(row.level) }, () => alarmLevelLabel(row.level))
  },
  {
    title: '状态', key: 'status', width: 90,
    render: row => h(NTag, { size: 'small', bordered: false, type: alarmStatusTagType(row.status) }, () => alarmStatusLabel(row.status))
  },
  { title: '发生时间', key: 'occurredAt', width: 170, render: row => formatConsoleTime(row.occurredAt) },
  {
    title: '操作', key: 'actions', width: 132,
    render: row => h(NSpace, { size: 4, wrap: false }, () => [
      h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => emit('open-alarm', row) }, () => '处置'),
      h(NButton, { text: true, size: 'small', onClick: () => emit('open-vehicle', row.deviceId) }, () => '车辆')
    ])
  }
];
</script>

<template>
  <NCard title="最近告警" :bordered="false" size="small" class="h-full">
    <NDataTable
      :columns="columns"
      :data="alarms"
      :row-key="row => row.id"
      :scroll-x="782"
      size="small"
      :bordered="false"
    />
    <NEmpty v-if="alarms.length === 0" description="暂无告警动态" class="py-36px" />
  </NCard>
</template>
