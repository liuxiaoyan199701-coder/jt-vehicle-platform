<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import { NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { type AuditEntry, fetchAuditLog } from '@/service/api';

defineOptions({ name: 'SystemAudit' });

const loading = ref(false);
const rows = ref<AuditEntry[]>([]);
const total = ref(0);
const pagination = reactive({ page: 1, pageSize: 20 });
const filters = reactive({ username: '', action: '', result: null as string | null });

const resultOptions = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' },
  { label: '被拒绝', value: 'DENIED' }
];

const columns: DataTableColumns<AuditEntry> = [
  { title: '时间', key: 'occurredAt', width: 200 },
  { title: '操作人', key: 'username', width: 130 },
  { title: '动作', key: 'action', width: 200 },
  { title: '对象', key: 'resourceId', width: 160, render: row => row.resourceId ?? '-' },
  {
    title: '结果',
    key: 'result',
    width: 100,
    render: row =>
      h(
        NTag,
        {
          size: 'small',
          type: row.result === 'SUCCESS' ? 'success' : row.result === 'DENIED' ? 'warning' : 'error'
        },
        { default: () => resultOptions.find(item => item.value === row.result)?.label ?? row.result }
      )
  },
  { title: '来源 IP', key: 'sourceIp', width: 140 },
  { title: '耗时(ms)', key: 'durationMs', width: 100 },
  { title: '说明', key: 'detail', ellipsis: { tooltip: true } }
];

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchAuditLog({
      username: filters.username || undefined,
      action: filters.action || undefined,
      result: filters.result || undefined,
      page: pagination.page,
      size: pagination.pageSize
    });
    rows.value = data?.records ?? [];
    total.value = data?.total ?? 0;
  } finally {
    loading.value = false;
  }
}

function search() {
  pagination.page = 1;
  load();
}

function changePage(page: number) {
  pagination.page = page;
  load();
}
</script>

<template>
  <NCard title="审计日志" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NInput v-model:value="filters.username" class="w-150px" clearable placeholder="操作人" />
        <NInput v-model:value="filters.action" class="w-170px" clearable placeholder="动作" />
        <NSelect v-model:value="filters.result" class="w-120px" clearable placeholder="结果" :options="resultOptions" />
        <NButton @click="search">查询</NButton>
      </NSpace>
    </template>

    <NAlert type="info" :bordered="false" class="mb-12px">
      审计记录只增不改：界面与接口都不提供修改或删除，唯一的清理路径是按保留期的后台任务。
    </NAlert>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :scroll-x="1200"
      :row-key="row => row.id"
      size="small"
      remote
      :pagination="{
        page: pagination.page,
        pageSize: pagination.pageSize,
        itemCount: total,
        showSizePicker: false,
        onUpdatePage: changePage
      }"
    />
  </NCard>
</template>
