<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { NButton, NPopconfirm, NSpace, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { createVehicle, deleteVehicle, fetchFleet, fetchFleets, fetchVehicles, updateVehicle, type Vehicle } from '@/service/api';
import { buildFleetAssignmentMap } from '@/utils/fleet-management';
import VehicleProfileDrawer from '@/components/business/vehicle-profile-drawer.vue';

defineOptions({ name: 'VehicleIndex' });

const message = useMessage();
const route = useRoute();
const loading = ref(false);
const rows = ref<Vehicle[]>([]);
const modalVisible = ref(false);
const isEdit = ref(false);
const submitting = ref(false);
const profileVisible = ref(false);
const profileDeviceId = ref<string | null>(null);
const fleetDetails = ref<Awaited<ReturnType<typeof fetchFleet>>['data'][]>([]);
const fleetAssignments = computed(() => buildFleetAssignmentMap(
  fleetDetails.value.flatMap(item => item ? [{
    id: item.fleet.id,
    code: item.fleet.code,
    name: item.fleet.name,
    members: item.members.map(member => ({ deviceId: member.vehicle.deviceId }))
  }] : [])
));

const form = reactive<Vehicle>({
  deviceId: '',
  plateNo: '',
  plateColor: '蓝色',
  brand: '',
  channelCount: 4,
  remark: ''
});

const plateColors = ['蓝色', '黄色', '黑色', '白色', '绿色'].map(value => ({ label: value, value }));

onMounted(async () => {
  await load();
  const deviceId = typeof route.query.device === 'string' ? route.query.device : '';
  if (deviceId && rows.value.some(item => item.deviceId === deviceId)) openProfile(deviceId);
});

async function load() {
  loading.value = true;
  const [vehicleResult, fleetResult] = await Promise.all([fetchVehicles(), fetchFleets()]);
  rows.value = vehicleResult.data ?? [];
  const details = await Promise.all((fleetResult.data ?? []).map(item => fetchFleet(item.fleet.id)));
  fleetDetails.value = details.map(result => result.data);
  loading.value = false;
}

function openCreate() {
  isEdit.value = false;
  Object.assign(form, {
    deviceId: '',
    plateNo: '',
    plateColor: '蓝色',
    brand: '',
    channelCount: 4,
    remark: ''
  });
  modalVisible.value = true;
}

function openEdit(row: Vehicle) {
  isEdit.value = true;
  Object.assign(form, row);
  modalVisible.value = true;
}

async function submit() {
  if (!form.deviceId.trim()) {
    message.warning('请填写终端号');
    return;
  }
  if (!form.plateNo.trim()) {
    message.warning('请填写车牌号');
    return;
  }

  submitting.value = true;
  const payload = { ...form };
  const { error } = isEdit.value
    ? await updateVehicle(payload.deviceId, payload)
    : await createVehicle(payload);
  submitting.value = false;

  if (error) {
    message.error(error.message || '保存失败');
    return;
  }
  message.success(isEdit.value ? '已更新' : '已新增');
  modalVisible.value = false;
  await load();
}

async function remove(row: Vehicle) {
  const { error } = await deleteVehicle(row.deviceId);
  if (error) {
    message.error(error.message || '删除失败');
    return;
  }
  message.success('已删除');
  await load();
}

function openProfile(deviceId: string) {
  profileDeviceId.value = deviceId;
  profileVisible.value = true;
}

const columns: DataTableColumns<Vehicle> = [
  { title: '车牌号', key: 'plateNo', width: 140 },
  { title: '终端号', key: 'deviceId', width: 160 },
  { title: '车牌颜色', key: 'plateColor', width: 100 },
  { title: '品牌型号', key: 'brand', width: 160 },
  {
    title: '所属车队',
    key: 'fleet',
    width: 150,
    render: row => fleetAssignments.value.get(row.deviceId)?.fleetName ?? '未分配'
  },
  { title: '通道数', key: 'channelCount', width: 90 },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        h(NButton, { size: 'tiny', quaternary: true, onClick: () => openProfile(row.deviceId) }, () => '详情'),
        h(NButton, { size: 'tiny', type: 'primary', quaternary: true, onClick: () => openEdit(row) }, () => '编辑'),
        h(
          NPopconfirm,
          { onPositiveClick: () => remove(row) },
          {
            trigger: () => h(NButton, { size: 'tiny', type: 'error', quaternary: true }, () => '删除'),
            default: () => `确认删除 ${row.plateNo}？该车辆的历史轨迹不会被删除。`
          }
        )
      ])
  }
];
</script>

<template>
  <NCard title="车辆档案" :bordered="false" class="h-full">
    <template #header-extra>
      <NSpace>
        <NButton size="small" @click="load">刷新</NButton>
        <NButton size="small" type="primary" @click="openCreate">新增车辆</NButton>
      </NSpace>
    </template>

    <NAlert type="warning" :bordered="false" class="mb-12px text-13px">
      <div>
        <b>设备号必须填写平台投递信封中的 canonical deviceId，即协议解码后的 mobileNo/SIM。</b>
        T0100 中厂家定义的终端 ID 只是信令路由别名，不能代替车辆档案主键。
      </div>
      <div class="mt-4px">
        不确定填什么，就查
        <NButton text tag="a" href="/api/diagnostics/events" target="_blank" type="primary">
          /api/diagnostics/events
        </NButton>
        ，里面的 deviceId 就是平台实际收到的值，照抄即可。
      </div>
      <div class="mt-4px text-#999">
        保存时只会去除首尾空白，不会按数值去掉前导零；协议解码后仍不同的
        <code>00123</code> 与 <code>123</code> 会作为两台不同设备保持隔离。
      </div>
    </NAlert>

    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="row => row.deviceId"
      :pagination="{ pageSize: 15 }"
      size="small"
    />

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="isEdit ? '编辑车辆' : '新增车辆'"
      class="w-520px max-w-[calc(100vw-24px)]"
    >
      <NForm label-placement="left" :label-width="90">
        <NFormItem label="终端号" required>
          <NInput v-model:value="form.deviceId" :disabled="isEdit" placeholder="协议解码后的 mobileNo/SIM" />
        </NFormItem>
        <NFormItem label="车牌号" required>
          <NInput v-model:value="form.plateNo" placeholder="如 京A12345" />
        </NFormItem>
        <NFormItem label="车牌颜色">
          <NSelect v-model:value="form.plateColor" :options="plateColors" />
        </NFormItem>
        <NFormItem label="品牌型号">
          <NInput v-model:value="form.brand" placeholder="选填" />
        </NFormItem>
        <NFormItem label="视频通道数">
          <NInputNumber v-model:value="form.channelCount" :min="1" :max="16" class="w-full" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <VehicleProfileDrawer v-model:visible="profileVisible" :device-id="profileDeviceId" />
  </NCard>
</template>
