<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type Position,
  type TenantView,
  createPosition,
  deletePosition,
  fetchPositions,
  fetchTenants,
  updatePosition
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'SystemPosition' });

const message = useMessage();
const authStore = useAuthStore();
const isPlatform = computed(() => Boolean(authStore.userInfo.platform));

const loading = ref(false);
const submitting = ref(false);
const rows = ref<Position[]>([]);
const tenants = ref<TenantView[]>([]);
const tenantFilter = ref<number | null>(null);
const modalVisible = ref(false);
const editingId = ref<number | null>(null);
const form = reactive({ name: '', sortOrder: 0, remark: '' });

const columns = computed<DataTableColumns<Position>>(() => [
  { title: '岗位名称', key: 'name' },
  { title: '排序', key: 'sortOrder', width: 90 },
  { title: '备注', key: 'remark' },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: row =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => remove(row) },
            {
              trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
              default: () => '仍被账号引用的岗位不能删除'
            }
          )
        ]
      })
  }
]);

onMounted(load);

async function load() {
  if (isPlatform.value && !tenants.value.length) {
    const tenantResult = await fetchTenants();
    tenants.value = tenantResult.data ?? [];
  }
  if (isPlatform.value && !tenantFilter.value) {
    rows.value = [];
    return;
  }
  loading.value = true;
  try {
    const { data } = await fetchPositions({ tenantId: tenantFilter.value });
    rows.value = data ?? [];
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, { name: '', sortOrder: 0, remark: '' });
  modalVisible.value = true;
}

function openEdit(row: Position) {
  editingId.value = row.id;
  Object.assign(form, { name: row.name, sortOrder: row.sortOrder, remark: row.remark ?? '' });
  modalVisible.value = true;
}

async function submit() {
  if (!form.name.trim()) {
    message.warning('岗位名称不能为空');
    return;
  }
  submitting.value = true;
  try {
    const payload = { name: form.name.trim(), sortOrder: form.sortOrder, remark: form.remark };
    const result = editingId.value
      ? await updatePosition(editingId.value, payload)
      : await createPosition({ ...payload, tenantId: tenantFilter.value });
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '岗位已更新' : '岗位已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

async function remove(row: Position) {
  const result = await deletePosition(row.id);
  if (result.error) {
    return;
  }
  message.success('岗位已删除');
  await load();
}
</script>

<template>
  <NCard title="岗位管理" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NSelect
          v-if="isPlatform"
          v-model:value="tenantFilter"
          class="w-180px"
          placeholder="请选择租户"
          :options="tenants.map(item => ({ label: item.tenant.name, value: item.tenant.id }))"
          @update:value="load"
        />
        <NButton
          v-permission="'system:position:manage'"
          type="primary"
          :disabled="isPlatform && !tenantFilter"
          @click="openCreate"
        >
          新增岗位
        </NButton>
      </NSpace>
    </template>

    <NAlert type="info" :bordered="false" class="mb-12px">
      岗位只是人事标签，不参与任何权限判定；谁能看到哪些数据由「角色 + 部门」决定。
    </NAlert>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="row => row.id" size="small" />

    <NModal v-model:show="modalVisible" preset="card" class="w-440px" :title="editingId ? '编辑岗位' : '新增岗位'">
      <NForm label-placement="left" :label-width="80">
        <NFormItem label="名称" required>
          <NInput v-model:value="form.name" placeholder="租户内唯一" />
        </NFormItem>
        <NFormItem label="排序">
          <NInputNumber v-model:value="form.sortOrder" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="form.remark" type="textarea" :rows="2" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </NCard>
</template>
