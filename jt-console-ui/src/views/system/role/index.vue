<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type DepartmentNode,
  type PermissionDefinition,
  type RoleDetails,
  type RolePayload,
  type TenantView,
  createRole,
  deleteRole,
  fetchDepartmentTree,
  fetchPermissionCatalog,
  fetchRoles,
  fetchTenants,
  updateRole
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'SystemRole' });

const message = useMessage();
const authStore = useAuthStore();
const isPlatform = computed(() => Boolean(authStore.userInfo.platform));
const canManage = computed(() => authStore.hasPermission('system:role:manage'));

const loading = ref(false);
const submitting = ref(false);
const rows = ref<RoleDetails[]>([]);
const catalog = ref<PermissionDefinition[]>([]);
const tenants = ref<TenantView[]>([]);
const departments = ref<DepartmentNode[]>([]);
const tenantFilter = ref<number | null>(null);
const modalVisible = ref(false);
const editingId = ref<number | null>(null);

const dataScopes = [
  { label: '本租户全部', value: 'TENANT' },
  { label: '本部门及以下', value: 'DEPT_AND_CHILDREN' },
  { label: '仅本部门', value: 'DEPT' },
  { label: '自定义部门', value: 'CUSTOM' }
];

const form = reactive<RolePayload & { code: string }>({
  tenantId: null,
  code: '',
  name: '',
  dataScope: 'TENANT',
  remark: '',
  permissions: [],
  departmentIds: []
});

/** 按模块分组，界面上一组一行复选框，比一长串权限码可读得多。 */
const grouped = computed(() => {
  const byModule = new Map<string, PermissionDefinition[]>();
  catalog.value.forEach(item => {
    const list = byModule.get(item.module) ?? [];
    list.push(item);
    byModule.set(item.module, list);
  });
  return Array.from(byModule.entries()).map(([module, items]) => ({ module, items }));
});

const departmentOptions = computed(() => flatten(departments.value, 0));

function flatten(nodes: DepartmentNode[], depth: number): { label: string; value: number }[] {
  return nodes.flatMap(node => [
    { label: `${'　'.repeat(depth)}${node.name}`, value: node.id },
    ...flatten(node.children ?? [], depth + 1)
  ]);
}

const columns = computed<DataTableColumns<RoleDetails>>(() => [
  { title: '角色名称', key: 'role.name', width: 160, render: row => row.role.name },
  { title: '编码', key: 'role.code', width: 160, render: row => row.role.code },
  {
    title: '类型',
    key: 'builtin',
    width: 100,
    render: row =>
      h(
        NTag,
        { size: 'small', type: row.role.builtin ? 'info' : 'default' },
        { default: () => (row.role.builtin ? '内置' : '自定义') }
      )
  },
  {
    title: '数据范围',
    key: 'dataScope',
    width: 130,
    render: row => dataScopes.find(item => item.value === row.role.dataScope)?.label ?? row.role.dataScope
  },
  { title: '权限数', key: 'permissions', width: 90, render: row => row.permissions.length },
  { title: '绑定账号', key: 'accountCount', width: 100 },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: row => {
      if (!canManage.value || row.role.builtin) {
        // 内置角色的权限由代码同步维护，界面上不提供任何入口比给一个必然失败的按钮更诚实。
        return row.role.builtin ? h(NTag, { size: 'small', bordered: false }, { default: () => '由系统维护' }) : null;
      }
      return h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => remove(row) },
            {
              trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
              default: () => `确认删除角色 ${row.role.name}？`
            }
          )
        ]
      });
    }
  }
]);

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [roleResult, catalogResult] = await Promise.all([
      fetchRoles({ tenantId: tenantFilter.value }),
      fetchPermissionCatalog()
    ]);
    rows.value = roleResult.data ?? [];
    catalog.value = catalogResult.data ?? [];
    if (isPlatform.value) {
      const tenantResult = await fetchTenants();
      tenants.value = tenantResult.data ?? [];
    }
    if (!isPlatform.value || tenantFilter.value) {
      const deptResult = await fetchDepartmentTree({ tenantId: tenantFilter.value });
      departments.value = deptResult.data ?? [];
    }
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, {
    tenantId: isPlatform.value ? tenantFilter.value : null,
    code: '',
    name: '',
    dataScope: 'TENANT',
    remark: '',
    permissions: [],
    departmentIds: []
  });
  modalVisible.value = true;
}

function openEdit(row: RoleDetails) {
  editingId.value = row.role.id;
  Object.assign(form, {
    tenantId: row.role.tenantId ?? null,
    code: row.role.code,
    name: row.role.name,
    dataScope: row.role.dataScope,
    remark: row.role.remark ?? '',
    permissions: [...row.permissions],
    departmentIds: [...row.departmentIds]
  });
  modalVisible.value = true;
}

async function submit() {
  if (!form.name.trim()) {
    message.warning('角色名称不能为空');
    return;
  }
  if (!editingId.value && !form.code.trim()) {
    message.warning('角色编码不能为空');
    return;
  }
  if (!form.permissions.length) {
    message.warning('至少需要勾选一个权限');
    return;
  }
  if (form.dataScope === 'CUSTOM' && !form.departmentIds?.length) {
    message.warning('自定义数据范围至少需要选择一个部门');
    return;
  }
  submitting.value = true;
  try {
    const payload: RolePayload = {
      tenantId: form.tenantId,
      code: form.code.trim(),
      name: form.name.trim(),
      dataScope: form.dataScope,
      remark: form.remark || null,
      permissions: form.permissions,
      departmentIds: form.dataScope === 'CUSTOM' ? form.departmentIds : []
    };
    const result = editingId.value ? await updateRole(editingId.value, payload) : await createRole(payload);
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '角色已更新，最迟 30 秒内对相关账号生效' : '角色已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

async function remove(row: RoleDetails) {
  const result = await deleteRole(row.role.id);
  if (result.error) {
    return;
  }
  message.success('角色已删除');
  await load();
}
</script>

<template>
  <NCard title="角色管理" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NSelect
          v-if="isPlatform"
          v-model:value="tenantFilter"
          class="w-180px"
          clearable
          placeholder="全部租户"
          :options="tenants.map(item => ({ label: item.tenant.name, value: item.tenant.id }))"
          @update:value="load"
        />
        <NButton v-permission="'system:role:manage'" type="primary" @click="openCreate">新增角色</NButton>
      </NSpace>
    </template>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :scroll-x="900"
      :row-key="row => row.role.id"
      size="small"
    />

    <NModal v-model:show="modalVisible" preset="card" class="w-720px" :title="editingId ? '编辑角色' : '新增角色'">
      <NForm label-placement="left" :label-width="90">
        <NFormItem v-if="isPlatform && !editingId" label="所属租户" required>
          <NSelect
            v-model:value="form.tenantId"
            placeholder="选择角色归属的租户"
            :options="tenants.map(item => ({ label: item.tenant.name, value: item.tenant.id }))"
          />
        </NFormItem>
        <NFormItem label="角色编码" required>
          <NInput v-model:value="form.code" :disabled="Boolean(editingId)" placeholder="租户内唯一，如 DISPATCH" />
        </NFormItem>
        <NFormItem label="角色名称" required>
          <NInput v-model:value="form.name" placeholder="如 调度员" />
        </NFormItem>
        <NFormItem label="数据范围">
          <NSelect v-model:value="form.dataScope" :options="dataScopes" />
        </NFormItem>
        <NFormItem v-if="form.dataScope === 'CUSTOM'" label="可见部门" required>
          <NSelect v-model:value="form.departmentIds" multiple :options="departmentOptions" />
        </NFormItem>
        <NFormItem label="权限" required>
          <div class="w-full">
            <NCheckboxGroup v-model:value="form.permissions" class="w-full">
              <div v-for="group in grouped" :key="group.module" class="mb-10px">
                <div class="mb-4px text-13px text-#909399">{{ group.module }}</div>
                <NSpace :size="[12, 6]">
                  <NCheckbox v-for="item in group.items" :key="item.code" :value="item.code" :label="item.name" />
                </NSpace>
              </div>
            </NCheckboxGroup>
          </div>
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
