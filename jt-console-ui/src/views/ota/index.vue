<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useMessage } from 'naive-ui';
import {
  deleteUpgradePackage,
  fetchUpgradePackages,
  fetchVehicles,
  sendDeviceCommand,
  uploadUpgradePackage,
  type UpgradePackage,
  type Vehicle
} from '@/service/api';

defineOptions({ name: 'OtaIndex' });

const message = useMessage();
const packages = ref<UpgradePackage[]>([]);
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);
const uploading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

const form = reactive({ name: '', version: '', makerId: '', file: null as File | null });
const issue = reactive({ deviceId: null as string | null });
const issuingId = ref<number | null>(null);

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

onMounted(load);

async function load() {
  loading.value = true;
  const [packageResult, vehicleResult] = await Promise.all([fetchUpgradePackages(), fetchVehicles()]);
  loading.value = false;
  if (packageResult.error) {
    message.error(packageResult.error.message || '升级包列表加载失败');
  } else {
    packages.value = packageResult.data ?? [];
  }
  vehicles.value = vehicleResult.data ?? [];
}

function pickFile(event: Event) {
  const input = event.target as HTMLInputElement;
  form.file = input.files?.[0] ?? null;
}

async function upload() {
  if (!form.name.trim() || !form.version.trim() || !form.makerId.trim()) {
    message.warning('请填写名称、版本与制造商 ID');
    return;
  }
  if (!form.file) {
    message.warning('请选择升级包文件');
    return;
  }
  uploading.value = true;
  const { data, error } = await uploadUpgradePackage(
    form.file, form.name.trim(), form.version.trim(), form.makerId.trim()
  );
  uploading.value = false;
  if (error || !data) {
    message.error(error?.message || '上传失败');
    return;
  }
  message.success('升级包已上传');
  Object.assign(form, { name: '', version: '', makerId: '', file: null });
  if (fileInput.value) fileInput.value.value = '';
  await load();
}

async function remove(item: UpgradePackage) {
  const { error } = await deleteUpgradePackage(item.id);
  if (error) {
    message.error(error.message || '删除失败');
    return;
  }
  message.success('升级包已删除');
  await load();
}

async function issueUpgrade(item: UpgradePackage) {
  if (!issue.deviceId) {
    message.warning('请先选择目标车辆');
    return;
  }
  issuingId.value = item.id;
  const { data, error } = await sendDeviceCommand('upgrade', {
    deviceId: issue.deviceId,
    packageId: item.id
  });
  issuingId.value = null;
  if (error || !data) {
    message.error(error?.message || '下发失败');
    return;
  }
  if (data.success === false) {
    message.error(data.message);
    return;
  }
  message.success(data.message);
}

function fmtSize(bytes: number) {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
}
</script>

<template>
  <div class="p-16px">
    <NCard :bordered="false" size="small">
      <template #header><span>OTA 升级包</span></template>

      <div class="mb-12px flex flex-wrap items-end gap-12px">
        <NFormItem label="名称" class="mb-0!"><NInput v-model:value="form.name" class="w-140px" /></NFormItem>
        <NFormItem label="版本号" class="mb-0!"><NInput v-model:value="form.version" class="w-120px" /></NFormItem>
        <NFormItem label="制造商 ID" class="mb-0!"><NInput v-model:value="form.makerId" class="w-140px" /></NFormItem>
        <NFormItem label="文件" class="mb-0!">
          <input ref="fileInput" type="file" @change="pickFile" />
        </NFormItem>
        <NButton type="primary" size="small" :loading="uploading" @click="upload">上传</NButton>
      </div>

      <div class="mb-12px flex items-center gap-8px">
        <span class="text-13px text-#666">目标车辆</span>
        <NSelect
          v-model:value="issue.deviceId"
          :options="vehicleOptions"
          filterable
          clearable
          placeholder="选择要升级的车辆"
          class="w-260px"
        />
      </div>

      <NSpin :show="loading">
        <NEmpty v-if="!loading && packages.length === 0" description="还没有升级包" class="py-40px" />
        <div v-for="item in packages" :key="item.id" class="ota-row">
          <div class="min-w-0 flex-1">
            <div class="flex items-center justify-between gap-8px">
              <span class="font-medium">{{ item.name }} <span class="text-12px text-gray-500">v{{ item.version }}</span></span>
              <NSpace size="small">
                <NButton text type="primary" size="tiny" :loading="issuingId === item.id" @click="issueUpgrade(item)">下发</NButton>
                <NPopconfirm @positive-click="remove(item)">
                  <template #trigger><NButton text type="error" size="tiny">删除</NButton></template>
                  删除后不能再下发该升级包。
                </NPopconfirm>
              </NSpace>
            </div>
            <div class="mt-4px text-12px text-gray-500">
              {{ item.makerId }} · {{ fmtSize(item.sizeBytes) }} · SHA-256 {{ item.sha256.slice(0, 16) }}…
            </div>
          </div>
        </div>
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped>
.ota-row { padding: 10px 12px; border-top: 1px solid rgb(239 239 245); }
.ota-row:hover { background: rgb(24 160 88 / 7%); }
</style>
