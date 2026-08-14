import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchEffectiveConfig } from '@/service/api';

/** 与后端 ConfigKeys 一一对应。键由后端代码定义，前端只消费。 */
export const CONFIG_KEYS = {
  platformName: 'platform.name',
  amapKey: 'map.amap.key',
  amapSecurityCode: 'map.amap.securityCode',
  movingSpeedKph: 'operations.movingSpeedKph'
} as const;

/**
 * 登录后生效的租户配置。
 *
 * 地图 Key 等取值必须在运行时才能确定——同一份构建要服务多个租户，各自可能用不同的 Key。
 * 因此这里做一次性拉取并缓存，多个页面并发调用共享同一个请求。
 */
export const useRuntimeConfigStore = defineStore('runtime-config', () => {
  const values = ref<Record<string, string>>({});
  const loaded = ref(false);
  let inflight: Promise<void> | null = null;

  const platformName = computed(() => values.value[CONFIG_KEYS.platformName] || '车联网监控平台');
  const amapKey = computed(() => values.value[CONFIG_KEYS.amapKey] || '');
  const amapSecurityCode = computed(() => values.value[CONFIG_KEYS.amapSecurityCode] || '');

  async function ensureLoaded() {
    if (loaded.value) {
      return;
    }
    if (inflight) {
      await inflight;
      return;
    }
    inflight = (async () => {
      const { data } = await fetchEffectiveConfig();
      values.value = data ?? {};
      loaded.value = true;
    })().finally(() => {
      inflight = null;
    });
    await inflight;
  }

  /** 配置改动后强制重新拉取；地图脚本已加载时需要刷新页面才会换 Key。 */
  async function reload() {
    loaded.value = false;
    await ensureLoaded();
  }

  function reset() {
    values.value = {};
    loaded.value = false;
  }

  return { values, loaded, platformName, amapKey, amapSecurityCode, ensureLoaded, reload, reset };
});
