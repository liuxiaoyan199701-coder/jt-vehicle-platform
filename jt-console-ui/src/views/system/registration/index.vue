<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import { NButton, NSpace, NTag, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  type PlanView,
  type TenantRegistration,
  approveRegistration,
  fetchPlans,
  fetchRegistrations,
  rejectRegistration
} from '@/service/api';

defineOptions({ name: 'SystemRegistration' });

const message = useMessage();
const loading = ref(false);
const rows = ref<TenantRegistration[]>([]);
const plans = ref<PlanView[]>([]);
const statusFilter = ref<string | null>('PENDING');

const approveVisible = ref(false);
const rejectVisible = ref(false);
const target = ref<TenantRegistration | null>(null);
const approveForm = reactive({ planId: null as number | null, months: null as number | null });
const rejectReason = ref('');

const statusOptions = [
  { label: '待审批', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已过期', value: 'EXPIRED' }
];

const columns: DataTableColumns<TenantRegistration> = [
  { title: '提交时间', key: 'createdAt', width: 190 },
  { title: '企业名称', key: 'companyName', width: 180 },
  { title: '联系人', key: 'contactName', width: 110 },
  { title: '联系电话', key: 'contactPhone', width: 140 },
  { title: '管理员用户名', key: 'username', width: 140 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row =>
      h(
        NTag,
        {
          size: 'small',
          type: row.status === 'APPROVED' ? 'success' : row.status === 'PENDING' ? 'info' : 'warning'
        },
        { default: () => statusOptions.find(item => item.value === row.status)?.label ?? row.status }
      )
  },
  { title: '审批人', key: 'reviewedBy', width: 120 },
  { title: '审批备注', key: 'reviewNote', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: row => {
      if (row.status !== 'PENDING') {
        return null;
      }
      return h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, { size: 'small', type: 'primary', onClick: () => openApprove(row) }, { default: () => '通过' }),
          h(NButton, { size: 'small', onClick: () => openReject(row) }, { default: () => '拒绝' })
        ]
      });
    }
  }
];

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [registrationResult, planResult] = await Promise.all([
      fetchRegistrations(statusFilter.value ?? undefined),
      fetchPlans()
    ]);
    rows.value = registrationResult.data ?? [];
    plans.value = planResult.data ?? [];
  } finally {
    loading.value = false;
  }
}

function openApprove(row: TenantRegistration) {
  target.value = row;
  Object.assign(approveForm, { planId: null, months: null });
  approveVisible.value = true;
}

async function submitApprove() {
  if (!target.value) {
    return;
  }
  const result = await approveRegistration(target.value.id, {
    planId: approveForm.planId,
    months: approveForm.months
  });
  if (result.error) {
    return;
  }
  message.success('已通过，该租户与管理员账号已激活');
  approveVisible.value = false;
  await load();
}

function openReject(row: TenantRegistration) {
  target.value = row;
  rejectReason.value = '';
  rejectVisible.value = true;
}

async function submitReject() {
  if (!target.value) {
    return;
  }
  if (!rejectReason.value.trim()) {
    message.warning('请填写拒绝原因');
    return;
  }
  const result = await rejectRegistration(target.value.id, rejectReason.value.trim());
  if (result.error) {
    return;
  }
  message.success('已拒绝，原因已留档');
  rejectVisible.value = false;
  await load();
}
</script>

<template>
  <NCard title="注册审批" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NSelect
          v-model:value="statusFilter"
          class="w-140px"
          clearable
          placeholder="全部状态"
          :options="statusOptions"
          @update:value="load"
        />
        <NButton @click="load">刷新</NButton>
      </NSpace>
    </template>

    <NAlert type="info" :bordered="false" class="mb-12px">
      注册不等于开通：待审批期间租户不可登录、不占配额。通过时绑定套餐与有效期后才真正激活。
    </NAlert>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :scroll-x="1200"
      :row-key="row => row.id"
      size="small"
    />

    <NModal v-model:show="approveVisible" preset="card" class="w-460px" title="审批通过">
      <NForm label-placement="left" :label-width="100">
        <NFormItem label="套餐">
          <NSelect
            v-model:value="approveForm.planId"
            clearable
            placeholder="不选则不限配额、不设有效期"
            :options="plans.map(item => ({ label: item.plan.name, value: item.plan.id }))"
          />
        </NFormItem>
        <NFormItem label="时长（月）">
          <NInputNumber v-model:value="approveForm.months" class="w-full" :min="1" placeholder="留空则按套餐周期" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="approveVisible = false">取消</NButton>
          <NButton type="primary" @click="submitApprove">确认通过</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="rejectVisible" preset="card" class="w-460px" title="拒绝申请">
      <NInput v-model:value="rejectReason" type="textarea" :rows="3" placeholder="拒绝原因，会留档备查" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="rejectVisible = false">取消</NButton>
          <NButton type="error" @click="submitReject">确认拒绝</NButton>
        </NSpace>
      </template>
    </NModal>
  </NCard>
</template>
