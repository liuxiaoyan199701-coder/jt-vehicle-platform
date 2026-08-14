<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type DepartmentNode,
  type TenantView,
  createDepartment,
  deleteDepartment,
  fetchDepartmentTree,
  fetchTenants,
  updateDepartment
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'SystemDepartment' });

const message = useMessage();
const authStore = useAuthStore();
const isPlatform = computed(() => Boolean(authStore.userInfo.platform));

const loading = ref(false);
const submitting = ref(false);
const tree = ref<DepartmentNode[]>([]);
const tenants = ref<TenantView[]>([]);
const tenantFilter = ref<number | null>(null);
const modalVisible = ref(false);
const editingId = ref<number | null>(null);

const form = reactive({ parentId: null as number | null, name: '', sortOrder: 0, enabled: true });

const parentOptions = computed<{ label: string; value: number | null }[]>(() => [
  { label: '（顶级部门）', value: null },
  ...flatten(tree.value, 0).filter(item => item.value !== editingId.value)
]);

function flatten(nodes: DepartmentNode[], depth: number): { label: string; value: number }[] {
  return nodes.flatMap(node => [
    { label: `${'　'.repeat(depth)}${node.name}`, value: node.id },
    ...flatten(node.children ?? [], depth + 1)
  ]);
}

const columns = computed<DataTableColumns<DepartmentNode>>(() => [
  { title: '部门名称', key: 'name' },
  { title: '排序', key: 'sortOrder', width: 80 },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: row =>
      h(
        NTag,
        { size: 'small', type: row.enabled ? 'success' : 'warning' },
        { default: () => (row.enabled ? '启用' : '停用') }
      )
  },
  { title: '账号数', key: 'accountCount', width: 90 },
  { title: '车辆数', key: 'vehicleCount', width: 90 },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    render: row =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => openCreate(row.id) }, { default: () => '新增下级' }),
          h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => remove(row) },
            {
              trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
              default: () => '仅当该部门没有子部门、账号与车辆时才能删除'
            }
          )
        ]
      })
  }
]);

onMounted(load);

async function load() {
  if (isPlatform.value && !tenantFilter.value) {
    const tenantResult = await fetchTenants();
    tenants.value = tenantResult.data ?? [];
    tree.value = [];
    return;
  }
  loading.value = true;
  try {
    if (isPlatform.value && !tenants.value.length) {
      const tenantResult = await fetchTenants();
      tenants.value = tenantResult.data ?? [];
    }
    const { data } = await fetchDepartmentTree({ tenantId: tenantFilter.value });
    tree.value = data ?? [];
  } finally {
    loading.value = false;
  }
}

function openCreate(parentId: number | null = null) {
  editingId.value = null;
  Object.assign(form, { parentId, name: '', sortOrder: 0, enabled: true });
  modalVisible.value = true;
}

function openEdit(row: DepartmentNode) {
  editingId.value = row.id;
  Object.assign(form, {
    parentId: row.parentId ?? null,
    name: row.name,
    sortOrder: row.sortOrder,
    enabled: row.enabled
  });
  modalVisible.value = true;
}

async function submit() {
  if (!form.name.trim()) {
    message.warning('部门名称不能为空');
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      parentId: form.parentId,
      name: form.name.trim(),
      sortOrder: form.sortOrder,
      enabled: form.enabled
    };
    const result = editingId.value
      ? await updateDepartment(editingId.value, payload)
      : await createDepartment({ ...payload, tenantId: tenantFilter.value });
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '部门已更新' : '部门已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

async function remove(row: DepartmentNode) {
  const result = await deleteDepartment(row.id);
  if (result.error) {
    return;
  }
  message.success('部门已删除');
  await load();
}
</script>

<template>
  <NCard title="部门管理" :bordered="false" size="small" class="h-full">
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
          v-permission="'system:dept:manage'"
          type="primary"
          :disabled="isPlatform && !tenantFilter"
          @click="openCreate(null)"
        >
          新增顶级部门
        </NButton>
      </NSpace>
    </template>

    <NAlert v-if="isPlatform && !tenantFilter" type="info" :bordered="false">
      部门是租户内的组织单元，请先选择要管理的租户。
    </NAlert>
    <NDataTable
      v-else
      :columns="columns"
      :data="tree"
      :loading="loading"
      :row-key="row => row.id"
      default-expand-all
      size="small"
    />

    <NModal v-model:show="modalVisible" preset="card" class="w-460px" :title="editingId ? '编辑部门' : '新增部门'">
      <NForm label-placement="left" :label-width="80">
        <NFormItem label="上级部门">
          <NSelect v-model:value="form.parentId" :options="parentOptions as any" />
        </NFormItem>
        <NFormItem label="名称" required>
          <NInput v-model:value="form.name" placeholder="同级下不可重名" />
        </NFormItem>
        <NFormItem label="排序">
          <NInputNumber v-model:value="form.sortOrder" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="状态">
          <NSwitch v-model:value="form.enabled" />
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
