<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import {
  type ConfigKeyDefinition,
  type TenantView,
  fetchConfigKeys,
  fetchConfigOverrides,
  fetchRecordingStorage,
  fetchTenants,
  type RecordingStorageMetrics,
  saveConfigOverrides
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';
import { useRuntimeConfigStore } from '@/store/modules/runtime-config';

defineOptions({ name: 'SystemConfig' });

const message = useMessage();
const authStore = useAuthStore();
const runtimeConfig = useRuntimeConfigStore();
const isPlatform = computed(() => Boolean(authStore.userInfo.platform));

const loading = ref(false);
const saving = ref(false);
const keys = ref<ConfigKeyDefinition[]>([]);
const values = ref<Record<string, string>>({});
const tenants = ref<TenantView[]>([]);
const tenantFilter = ref<number | null>(null);
const recordingStorage = ref<RecordingStorageMetrics | null>(null);
const storageUnavailable = ref(false);

const storagePercent = computed(() => {
  const value = recordingStorage.value;
  if (!value) return 0;
  const filesystemCapacity = value.recordingOccupiedBytes + value.recordingUsableBytes;
  const capacity = value.maxBytes > 0 ? Math.min(value.maxBytes, filesystemCapacity) : filesystemCapacity;
  return capacity > 0 ? Math.min(100, (value.recordingOccupiedBytes / capacity) * 100) : 0;
});
const storageStatus = computed(() => storagePercent.value >= 90 ? 'error' : storagePercent.value >= 80 ? 'warning' : 'success');

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

const scopeLabel = computed(() => {
  if (!isPlatform.value) {
    return `当前租户（${authStore.userInfo.tenantName ?? ''}）`;
  }
  const target = tenants.value.find(item => item.tenant.id === tenantFilter.value);
  return target ? `租户「${target.tenant.name}」` : '全局默认';
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [keyResult, overrideResult, storageResult] = await Promise.all([
      fetchConfigKeys(),
      fetchConfigOverrides(tenantFilter.value),
      fetchRecordingStorage()
    ]);
    keys.value = keyResult.data ?? [];
    values.value = { ...(overrideResult.data ?? {}) };
    recordingStorage.value = storageResult.data ?? null;
    storageUnavailable.value = Boolean(storageResult.error);
    if (isPlatform.value && !tenants.value.length) {
      const tenantResult = await fetchTenants();
      tenants.value = tenantResult.data ?? [];
    }
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    const result = await saveConfigOverrides(values.value, tenantFilter.value);
    if (result.error) {
      return;
    }
    message.success('配置已保存，最迟 30 秒内生效');
    await runtimeConfig.reload();
    await load();
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col gap-12px">
  <NCard title="录像存储" :bordered="false" size="small">
    <NAlert v-if="storageUnavailable" type="warning" :bordered="false">录像存储状态暂不可用</NAlert>
    <div v-else-if="recordingStorage" class="grid gap-16px md:grid-cols-3">
      <NStatistic label="录像占用" :value="formatBytes(recordingStorage.recordingOccupiedBytes)" />
      <NStatistic label="磁盘可用" :value="formatBytes(recordingStorage.recordingUsableBytes)" />
      <NStatistic label="保留期" :value="recordingStorage.retentionDays || '未按天限制'" :suffix="recordingStorage.retentionDays ? '天' : ''" />
      <div class="md:col-span-3">
        <div class="mb-6px flex justify-between text-12px">
          <span>录像占用率</span><span>{{ storagePercent.toFixed(1) }}%</span>
        </div>
        <NProgress type="line" :percentage="storagePercent" :status="storageStatus" :show-indicator="false" />
        <p class="mt-6px text-12px text-gray-500">
          容量上限 {{ recordingStorage.maxBytes ? formatBytes(recordingStorage.maxBytes) : '未配置' }}；
          实时录像 {{ recordingStorage.realtimeEnabled ? '已开启' : '未开启' }}。
        </p>
      </div>
    </div>
  </NCard>

  <NCard title="租户配置" :bordered="false" size="small" class="h-full">
    <template #header-extra>
      <NSpace align="center">
        <NSelect
          v-if="isPlatform"
          v-model:value="tenantFilter"
          class="w-200px"
          clearable
          placeholder="全局默认"
          :options="tenants.map(item => ({ label: item.tenant.name, value: item.tenant.id }))"
          @update:value="load"
        />
        <NButton
          v-permission="['system:config:manage', 'platform:config:manage']"
          type="primary"
          :loading="saving"
          @click="save"
        >
          保存
        </NButton>
      </NSpace>
    </template>

    <NAlert type="info" :bordered="false" class="mb-16px">
      当前编辑范围：{{ scopeLabel }}。留空表示不覆盖，回落到全局默认。
      地图 Key 修改后需要刷新页面，地图脚本才会用新 Key 重新加载。
    </NAlert>

    <NSpin :show="loading">
      <NForm label-placement="left" :label-width="160">
        <NFormItem v-for="item in keys" :key="item.key" :label="item.name">
          <NInput
            v-model:value="values[item.key]"
            :type="item.sensitive ? 'password' : 'text'"
            :show-password-on="item.sensitive ? 'click' : undefined"
            :placeholder="item.sensitive ? '留空表示不修改；显示 ******** 表示已配置' : '留空表示使用全局默认'"
          />
        </NFormItem>
      </NForm>
    </NSpin>
  </NCard>
  </div>
</template>
