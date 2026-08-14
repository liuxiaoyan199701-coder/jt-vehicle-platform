<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type PlanView,
  type TenantOrder,
  type TenantPayload,
  type TenantView,
  changeTenantStatus,
  createTenant,
  deleteTenant,
  fetchPlans,
  fetchTenantOrders,
  fetchTenants,
  renewTenant,
  updateTenant
} from '@/service/api';

defineOptions({ name: 'SystemTenant' });

const message = useMessage();
const loading = ref(false);
const submitting = ref(false);
const rows = ref<TenantView[]>([]);
const plans = ref<PlanView[]>([]);
const orders = ref<TenantOrder[]>([]);

const modalVisible = ref(false);
const renewVisible = ref(false);
const ordersVisible = ref(false);
const editingId = ref<number | null>(null);
const renewTarget = ref<TenantView | null>(null);

const form = reactive<TenantPayload>({
  code: '',
  name: '',
  planId: null,
  expiresAt: null,
  contactName: '',
  contactPhone: '',
  remark: ''
});
const renewForm = reactive({ planId: null as number | null, months: 12, amountYuan: 0, remark: '' });

const columns: DataTableColumns<TenantView> = [
  { title: '租户名称', key: 'name', width: 160, render: row => row.tenant.name },
  { title: '编码', key: 'code', width: 140, render: row => row.tenant.code },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: row => {
      if (row.expired) {
        return h(NTag, { size: 'small', type: 'error' }, { default: () => '已到期' });
      }
      const status = row.tenant.status;
      const label =
        status === 'ACTIVE'
          ? '正常'
          : status === 'SUSPENDED'
            ? '已停用'
            : status === 'PENDING_APPROVAL'
              ? '待审批'
              : '已拒绝';
      return h(
        NTag,
        { size: 'small', type: status === 'ACTIVE' ? 'success' : 'warning' },
        { default: () => label }
      );
    }
  },
  { title: '套餐', key: 'planName', width: 150, render: row => row.planName ?? '未绑定' },
  {
    title: '车辆',
    key: 'vehicles',
    width: 110,
    render: row => `${row.vehicleCount} / ${row.maxVehicles || '不限'}`
  },
  {
    title: '账号',
    key: 'accounts',
    width: 110,
    render: row => `${row.accountCount} / ${row.maxAccounts || '不限'}`
  },
  { title: '有效期至', key: 'expiresAt', width: 200, render: row => row.tenant.expiresAt ?? '永不过期' },
  {
    title: '操作',
    key: 'actions',
    width: 300,
    render: row =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(NButton, { size: 'small', onClick: () => openRenew(row) }, { default: () => '续费' }),
          h(NButton, { size: 'small', onClick: () => openOrders(row) }, { default: () => '台账' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => toggle(row) },
            {
              trigger: () =>
                h(NButton, { size: 'small' }, { default: () => (row.tenant.status === 'ACTIVE' ? '停用' : '启用') }),
              default: () =>
                row.tenant.status === 'ACTIVE'
                  ? '停用后该租户用户立即无法登录、会话被撤销，其设备也会被网关断开。数据不会丢失。'
                  : '启用后该租户恢复登录与设备接入。'
            }
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => remove(row) },
            {
              trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
              default: () => '仅当租户下没有车辆与账号时才能删除'
            }
          )
        ]
      })
  }
];

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [tenantResult, planResult] = await Promise.all([fetchTenants(), fetchPlans()]);
    rows.value = tenantResult.data ?? [];
    plans.value = planResult.data ?? [];
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, {
    code: '',
    name: '',
    planId: null,
    expiresAt: null,
    contactName: '',
    contactPhone: '',
    remark: ''
  });
  modalVisible.value = true;
}

function openEdit(row: TenantView) {
  editingId.value = row.tenant.id;
  Object.assign(form, {
    code: row.tenant.code,
    name: row.tenant.name,
    planId: row.tenant.planId ?? null,
    expiresAt: row.tenant.expiresAt ?? null,
    contactName: row.tenant.contactName ?? '',
    contactPhone: row.tenant.contactPhone ?? '',
    remark: row.tenant.remark ?? ''
  });
  modalVisible.value = true;
}

async function submit() {
  if (!form.code.trim() || !form.name.trim()) {
    message.warning('租户编码与名称不能为空');
    return;
  }
  submitting.value = true;
  try {
    const result = editingId.value ? await updateTenant(editingId.value, form) : await createTenant(form);
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '租户已更新' : '租户已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

function openRenew(row: TenantView) {
  renewTarget.value = row;
  Object.assign(renewForm, { planId: row.tenant.planId ?? null, months: 12, amountYuan: 0, remark: '' });
  renewVisible.value = true;
}

async function submitRenew() {
  if (!renewTarget.value || renewForm.months === 0) {
    message.warning('续费时长不能为 0');
    return;
  }
  const result = await renewTenant(renewTarget.value.tenant.id, {
    planId: renewForm.planId,
    months: renewForm.months,
    // 金额以「分」传输，避免浮点累计误差
    amountCents: Math.round(renewForm.amountYuan * 100),
    remark: renewForm.remark
  });
  if (result.error) {
    return;
  }
  message.success('续费已录入，有效期已延展');
  renewVisible.value = false;
  await load();
}

async function openOrders(row: TenantView) {
  const { data } = await fetchTenantOrders(row.tenant.id);
  orders.value = data ?? [];
  ordersVisible.value = true;
}

async function toggle(row: TenantView) {
  const result = await changeTenantStatus(row.tenant.id, row.tenant.status !== 'ACTIVE');
  if (result.error) {
    return;
  }
  message.success('租户状态已更新');
  await load();
}

async function remove(row: TenantView) {
  const result = await deleteTenant(row.tenant.id);
  if (result.error) {
    return;
  }
  message.success('租户已删除');
  await load();
}
</script>

<template>
  <NCard title="租户管理" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NButton v-permission="'platform:tenant:manage'" type="primary" @click="openCreate">新增租户</NButton>
    </template>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :scroll-x="1300"
      :row-key="row => row.tenant.id"
      size="small"
    />

    <NModal v-model:show="modalVisible" preset="card" class="w-540px" :title="editingId ? '编辑租户' : '新增租户'">
      <NForm label-placement="left" :label-width="90">
        <NFormItem label="租户编码" required>
          <NInput v-model:value="form.code" placeholder="全局唯一，如 acme-logistics" />
        </NFormItem>
        <NFormItem label="租户名称" required>
          <NInput v-model:value="form.name" placeholder="客户公司名称" />
        </NFormItem>
        <NFormItem label="套餐">
          <NSelect
            v-model:value="form.planId"
            clearable
            placeholder="不绑定则不限配额"
            :options="plans.map(item => ({ label: item.plan.name, value: item.plan.id }))"
          />
        </NFormItem>
        <NFormItem label="有效期至">
          <NInput v-model:value="form.expiresAt" placeholder="ISO-8601，如 2027-01-01T00:00:00Z；留空表示永不过期" />
        </NFormItem>
        <NFormItem label="联系人">
          <NInput v-model:value="form.contactName" />
        </NFormItem>
        <NFormItem label="联系电话">
          <NInput v-model:value="form.contactPhone" />
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

    <NModal v-model:show="renewVisible" preset="card" class="w-480px" title="录入续费">
      <NAlert type="info" :bordered="false" class="mb-12px">
        台账只增不改。录错请再录一条负时长、负金额的红冲记录纠正。
      </NAlert>
      <NForm label-placement="left" :label-width="90">
        <NFormItem label="套餐">
          <NSelect
            v-model:value="renewForm.planId"
            clearable
            placeholder="不改则沿用当前套餐"
            :options="plans.map(item => ({ label: item.plan.name, value: item.plan.id }))"
          />
        </NFormItem>
        <NFormItem label="时长（月）">
          <NInputNumber v-model:value="renewForm.months" class="w-full" />
        </NFormItem>
        <NFormItem label="金额（元）">
          <NInputNumber v-model:value="renewForm.amountYuan" class="w-full" :precision="2" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="renewForm.remark" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="renewVisible = false">取消</NButton>
          <NButton type="primary" @click="submitRenew">确认录入</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="ordersVisible" preset="card" class="w-720px" title="续费台账">
      <NDataTable
        size="small"
        :data="orders"
        :row-key="row => row.id"
        :columns="[
          { title: '时间', key: 'createdAt', width: 190 },
          { title: '套餐', key: 'planName', width: 140 },
          { title: '时长（月）', key: 'months', width: 100 },
          { title: '金额（元）', key: 'amount', width: 110, render: row => (row.amountCents / 100).toFixed(2) },
          { title: '延展至', key: 'newExpiresAt', width: 190 },
          { title: '经办人', key: 'operator', width: 110 },
          { title: '备注', key: 'remark' }
        ]"
      />
    </NModal>
  </NCard>
</template>
