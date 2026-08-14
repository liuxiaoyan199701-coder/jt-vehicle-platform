<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type PlanView,
  type TenantOrder,
  createPlan,
  deletePlan,
  fetchPlans,
  fetchRecentOrders,
  updatePlan
} from '@/service/api';

defineOptions({ name: 'SystemPlan' });

const message = useMessage();
const loading = ref(false);
const submitting = ref(false);
const rows = ref<PlanView[]>([]);
const orders = ref<TenantOrder[]>([]);
const modalVisible = ref(false);
const editingId = ref<number | null>(null);

const form = reactive({
  name: '',
  maxVehicles: 0,
  maxAccounts: 0,
  priceYuan: 0,
  periodMonths: 12,
  enabled: true,
  remark: ''
});

const columns: DataTableColumns<PlanView> = [
  { title: '套餐名称', key: 'name', width: 180, render: row => row.plan.name },
  {
    title: '车辆上限',
    key: 'maxVehicles',
    width: 110,
    render: row => (row.plan.maxVehicles === 0 ? '不限' : row.plan.maxVehicles)
  },
  {
    title: '账号上限',
    key: 'maxAccounts',
    width: 110,
    render: row => (row.plan.maxAccounts === 0 ? '不限' : row.plan.maxAccounts)
  },
  { title: '价格（元）', key: 'price', width: 120, render: row => (row.plan.priceCents / 100).toFixed(2) },
  { title: '周期（月）', key: 'periodMonths', width: 110, render: row => row.plan.periodMonths },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: row =>
      h(
        NTag,
        { size: 'small', type: row.plan.enabled ? 'success' : 'warning' },
        { default: () => (row.plan.enabled ? '可用' : '停用') }
      )
  },
  { title: '绑定租户', key: 'tenantCount', width: 100 },
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
              default: () => '仍被租户绑定的套餐不能删除'
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
    const [planResult, orderResult] = await Promise.all([fetchPlans(), fetchRecentOrders(50)]);
    rows.value = planResult.data ?? [];
    orders.value = orderResult.data ?? [];
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  Object.assign(form, {
    name: '',
    maxVehicles: 0,
    maxAccounts: 0,
    priceYuan: 0,
    periodMonths: 12,
    enabled: true,
    remark: ''
  });
  modalVisible.value = true;
}

function openEdit(row: PlanView) {
  editingId.value = row.plan.id;
  Object.assign(form, {
    name: row.plan.name,
    maxVehicles: row.plan.maxVehicles,
    maxAccounts: row.plan.maxAccounts,
    priceYuan: row.plan.priceCents / 100,
    periodMonths: row.plan.periodMonths,
    enabled: row.plan.enabled,
    remark: row.plan.remark ?? ''
  });
  modalVisible.value = true;
}

async function submit() {
  if (!form.name.trim()) {
    message.warning('套餐名称不能为空');
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      name: form.name.trim(),
      maxVehicles: form.maxVehicles,
      maxAccounts: form.maxAccounts,
      priceCents: Math.round(form.priceYuan * 100),
      periodMonths: form.periodMonths,
      enabled: form.enabled,
      remark: form.remark
    };
    const result = editingId.value ? await updatePlan(editingId.value, payload) : await createPlan(payload);
    if (result.error) {
      return;
    }
    message.success(editingId.value ? '套餐已更新' : '套餐已创建');
    modalVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

async function remove(row: PlanView) {
  const result = await deletePlan(row.plan.id);
  if (result.error) {
    return;
  }
  message.success('套餐已删除');
  await load();
}
</script>

<template>
  <NSpace vertical :size="12">
    <NCard title="套餐管理" :bordered="false" size="small">
      <template #header-extra>
        <NButton v-permission="'platform:plan:manage'" type="primary" @click="openCreate">新增套餐</NButton>
      </template>
      <NAlert type="info" :bordered="false" class="mb-12px">
        配额填 0 表示不限量。收紧套餐不会回收已有数据，只会阻止新增。
      </NAlert>
      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :scroll-x="1000"
        :row-key="row => row.plan.id"
        size="small"
      />
    </NCard>

    <NCard title="最近续费台账" :bordered="false" size="small">
      <NDataTable
        size="small"
        :data="orders"
        :row-key="row => row.id"
        :columns="[
          { title: '时间', key: 'createdAt', width: 190 },
          { title: '租户', key: 'tenantId', width: 90 },
          { title: '套餐', key: 'planName', width: 140 },
          { title: '时长（月）', key: 'months', width: 100 },
          { title: '金额（元）', key: 'amount', width: 110, render: row => (row.amountCents / 100).toFixed(2) },
          { title: '延展至', key: 'newExpiresAt', width: 190 },
          { title: '经办人', key: 'operator', width: 110 },
          { title: '备注', key: 'remark' }
        ]"
      />
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" class="w-500px" :title="editingId ? '编辑套餐' : '新增套餐'">
      <NForm label-placement="left" :label-width="100">
        <NFormItem label="名称" required>
          <NInput v-model:value="form.name" />
        </NFormItem>
        <NFormItem label="车辆上限">
          <NInputNumber v-model:value="form.maxVehicles" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="账号上限">
          <NInputNumber v-model:value="form.maxAccounts" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="价格（元）">
          <NInputNumber v-model:value="form.priceYuan" class="w-full" :min="0" :precision="2" />
        </NFormItem>
        <NFormItem label="周期（月）">
          <NInputNumber v-model:value="form.periodMonths" class="w-full" :min="1" />
        </NFormItem>
        <NFormItem label="状态">
          <NSwitch v-model:value="form.enabled" />
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
  </NSpace>
</template>
