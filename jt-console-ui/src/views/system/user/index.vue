<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type AccountPayload,
  type AccountView,
  type DepartmentNode,
  type Position,
  type RoleDetails,
  type TenantView,
  changeAccountStatus,
  createAccount,
  deleteAccount,
  fetchAccounts,
  fetchDepartmentTree,
  fetchPositions,
  fetchRoles,
  fetchTenants,
  resetAccountPassword,
  updateAccount
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'SystemUser' });

const message = useMessage();
const authStore = useAuthStore();
const isPlatform = computed(() => Boolean(authStore.userInfo.platform));
const canManage = computed(() => authStore.hasPermission('system:account:manage'));

const loading = ref(false);
const submitting = ref(false);
const rows = ref<AccountView[]>([]);
const roles = ref<RoleDetails[]>([]);
const tenants = ref<TenantView[]>([]);
const departments = ref<DepartmentNode[]>([]);
const positions = ref<Position[]>([]);
const keyword = ref('');
const tenantFilter = ref<number | null>(null);

const modalVisible = ref(false);
const editingId = ref<number | null>(null);
const passwordVisible = ref(false);
const passwordTarget = ref<AccountView | null>(null);
const newPassword = ref('');

const form = reactive<AccountPayload & { username: string }>({
  username: '',
  password: '',
  displayName: '',
  tenantId: null,
  departmentId: null,
  positionId: null,
  roleIds: []
});

const roleOptions = computed(() =>
  roles.value.map(item => ({
    label: `${item.role.name}${item.role.builtin ? '（内置）' : ''}`,
    value: item.role.id
  }))
);
const tenantOptions = computed(() =>
  tenants.value.map(item => ({ label: item.tenant.name, value: item.tenant.id }))
);
const departmentOptions = computed(() => flattenDepartments(departments.value, 0));
const positionOptions = computed(() => positions.value.map(item => ({ label: item.name, value: item.id })));

function flattenDepartments(nodes: DepartmentNode[], depth: number): { label: string; value: number }[] {
  return nodes.flatMap(node => [
    { label: `${'　'.repeat(depth)}${node.name}`, value: node.id },
    ...flattenDepartments(node.children ?? [], depth + 1)
  ]);
}

const columns = computed<DataTableColumns<AccountView>>(() => {
  const base: DataTableColumns<AccountView> = [
    { title: '用户名', key: 'username', width: 150 },
    { title: '显示名称', key: 'displayName', width: 140 },
    {
      title: '角色',
      key: 'roles',
      width: 200,
      render: row =>
        h(
          NSpace,
          { size: 4 },
          { default: () => row.roles.map(item => h(NTag, { size: 'small' }, { default: () => item.name })) }
        )
    },
    { title: '部门', key: 'departmentName', width: 130 },
    { title: '岗位', key: 'positionName', width: 110 },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: row =>
        h(
          NTag,
          { type: row.status === 'ACTIVE' ? 'success' : 'warning', size: 'small' },
          { default: () => (row.status === 'ACTIVE' ? '启用' : '禁用') }
        )
    },
    { title: '最近登录', key: 'lastLoginAt', width: 180 }
  ];
  if (isPlatform.value) {
    base.splice(2, 0, { title: '租户', key: 'tenantName', width: 140 });
  }
  base.push({
    title: '操作',
    key: 'actions',
    width: 240,
    render: row =>
      h(NSpace, { size: 6 }, {
        default: () => {
          if (!canManage.value) {
            return [];
          }
          return [
            h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
            h(NButton, { size: 'small', onClick: () => openPassword(row) }, { default: () => '重置密码' }),
            h(
              NButton,
              { size: 'small', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 'ACTIVE' ? '禁用' : '启用') }
            ),
            h(
              NPopconfirm,
              { onPositiveClick: () => remove(row) },
              {
                trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
                default: () => `确认删除账号 ${row.username}？其历史操作记录会保留。`
              }
            )
          ];
        }
      })
  });
  return base;
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [accountResult, roleResult] = await Promise.all([
      fetchAccounts({ tenantId: tenantFilter.value, keyword: keyword.value }),
      fetchRoles({ tenantId: tenantFilter.value })
    ]);
    rows.value = accountResult.data ?? [];
    roles.value = roleResult.data ?? [];
    if (isPlatform.value) {
      const tenantResult = await fetchTenants();
      tenants.value = tenantResult.data ?? [];
    }
    await loadOrganization(tenantFilter.value);
  } finally {
    loading.value = false;
  }
}

/** 部门与岗位都是租户内实体；平台管理员必须先选租户才有可选项。 */
async function loadOrganization(tenantId: number | null) {
  if (isPlatform.value && !tenantId) {
    departments.value = [];
    positions.value = [];
    return;
  }
  const [deptResult, positionResult] = await Promise.all([
    fetchDepartmentTree({ tenantId }),
    fetchPositions({ tenantId })
  ]);
  departments.value = deptResult.data ?? [];
  positions.value = positionResult.data ?? [];
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, {
    username: '',
    password: '',
    displayName: '',
    tenantId: isPlatform.value ? tenantFilter.value : null,
    departmentId: null,
    positionId: null,
    roleIds: []
  });
  modalVisible.value = true;
}

function openEdit(row: AccountView) {
  editingId.value = row.id;
  Object.assign(form, {
    username: row.username,
    password: '',
    displayName: row.displayName ?? '',
    tenantId: row.tenantId ?? null,
    departmentId: row.departmentId ?? null,
    positionId: row.positionId ?? null,
    roleIds: row.roles.map(item => item.id)
  });
  loadOrganization(row.tenantId ?? null);
  modalVisible.value = true;
}

async function submit() {
  if (!form.username.trim()) {
    message.warning('用户名不能为空');
    return;
  }
  if (!form.roleIds.length) {
    message.warning('至少需要绑定一个角色');
    return;
  }
  submitting.value = true;
  try {
    const payload: AccountPayload = {
      username: form.username.trim(),
      password: form.password || undefined,
      displayName: form.displayName || null,
      tenantId: form.tenantId,
      departmentId: form.departmentId,
      positionId: form.positionId,
      roleIds: form.roleIds
    };
    const result = editingId.value
      ? await updateAccount(editingId.value, payload)
      : await createAccount(payload);
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '账号已更新' : '账号已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

function openPassword(row: AccountView) {
  passwordTarget.value = row;
  newPassword.value = '';
  passwordVisible.value = true;
}

async function submitPassword() {
  if (!passwordTarget.value) {
    return;
  }
  if (newPassword.value.length < 8) {
    message.warning('密码至少 8 个字符');
    return;
  }
  const result = await resetAccountPassword(passwordTarget.value.id, newPassword.value);
  if (result.error) {
    return;
  }
  // 重置后目标账号的全部会话会被撤销，必须用新密码重新登录。
  message.success('密码已重置，该账号需重新登录');
  passwordVisible.value = false;
}

async function toggleStatus(row: AccountView) {
  const result = await changeAccountStatus(row.id, row.status !== 'ACTIVE');
  if (result.error) {
    return;
  }
  message.success(row.status === 'ACTIVE' ? '账号已禁用，其会话立即失效' : '账号已启用');
  await load();
}

async function remove(row: AccountView) {
  const result = await deleteAccount(row.id);
  if (result.error) {
    return;
  }
  message.success('账号已删除');
  await load();
}
</script>

<template>
  <NCard title="用户管理" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NSelect
          v-if="isPlatform"
          v-model:value="tenantFilter"
          class="w-180px"
          clearable
          placeholder="全部租户"
          :options="tenantOptions"
          @update:value="load"
        />
        <NInput v-model:value="keyword" class="w-180px" clearable placeholder="用户名或显示名称" @keyup.enter="load" />
        <NButton @click="load">查询</NButton>
        <NButton v-permission="'system:account:manage'" type="primary" @click="openCreate">新增账号</NButton>
      </NSpace>
    </template>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :scroll-x="1200"
      :row-key="row => row.id"
      size="small"
    />

    <NModal v-model:show="modalVisible" preset="card" class="w-560px" :title="editingId ? '编辑账号' : '新增账号'">
      <NForm label-placement="left" :label-width="90">
        <NFormItem label="用户名" required>
          <NInput v-model:value="form.username" :disabled="Boolean(editingId)" placeholder="登录用户名，全局唯一" />
        </NFormItem>
        <NFormItem v-if="!editingId" label="初始密码" required>
          <NInput v-model:value="form.password" type="password" placeholder="至少 8 个字符" />
        </NFormItem>
        <NFormItem label="显示名称">
          <NInput v-model:value="form.displayName" placeholder="用于界面展示" />
        </NFormItem>
        <NFormItem v-if="isPlatform && !editingId" label="所属租户">
          <NSelect
            v-model:value="form.tenantId"
            clearable
            placeholder="留空表示平台账号"
            :options="tenantOptions"
            @update:value="loadOrganization"
          />
        </NFormItem>
        <NFormItem label="部门">
          <NSelect v-model:value="form.departmentId" clearable placeholder="可不分配" :options="departmentOptions" />
        </NFormItem>
        <NFormItem label="岗位">
          <NSelect v-model:value="form.positionId" clearable placeholder="仅作人事标签" :options="positionOptions" />
        </NFormItem>
        <NFormItem label="角色" required>
          <NSelect v-model:value="form.roleIds" multiple placeholder="可多选，权限取并集" :options="roleOptions" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="passwordVisible" preset="card" class="w-420px" title="重置密码">
      <NAlert type="warning" :bordered="false" class="mb-12px">
        重置后该账号的全部会话立即失效，需以新密码重新登录。
      </NAlert>
      <NInput v-model:value="newPassword" type="password" placeholder="至少 8 个字符" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="passwordVisible = false">取消</NButton>
          <NButton type="primary" @click="submitPassword">确认重置</NButton>
        </NSpace>
      </template>
    </NModal>
  </NCard>
</template>
