<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import {
  type ConfigKeyDefinition,
  type TenantView,
  fetchConfigKeys,
  fetchConfigOverrides,
  fetchTenants,
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
    const [keyResult, overrideResult] = await Promise.all([
      fetchConfigKeys(),
      fetchConfigOverrides(tenantFilter.value)
    ]);
    keys.value = keyResult.data ?? [];
    values.value = { ...(overrideResult.data ?? {}) };
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
</template>
