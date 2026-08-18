import { onBeforeUnmount, ref, shallowRef } from 'vue';
import { useRuntimeConfigStore } from '@/store/modules/runtime-config';

/**
 * 高德地图 JS API 加载与地图实例管理。
 *
 * Key 在运行时从「生效配置」接口取，而不是构建期的 VITE_AMAP_KEY：租户级 Key 隔离要求
 * 登录后才知道该用哪把 Key。构建期变量降级为无租户覆盖时的全局默认来源，由后端合并。
 *
 * 未配置 Key 时不会抛异常，而是把 error 置位让调用方显示占位提示——
 * 地图缺失不应该让整个监控页白屏。
 */

declare global {
  interface Window {
    AMap?: any;
    _AMapSecurityConfig?: { securityJsCode: string };
  }
}

const AMAP_VERSION = '2.0';
const PLUGINS = ['AMap.Scale', 'AMap.ToolBar', 'AMap.MoveAnimation'];

let loadPromise: Promise<any> | null = null;

/** 全局只加载一次脚本，多个页面并发调用共享同一个 Promise */
async function loadAMapScript(): Promise<any> {
  if (window.AMap) {
    return window.AMap;
  }
  if (loadPromise) {
    return loadPromise;
  }

  const runtimeConfig = useRuntimeConfigStore();
  await runtimeConfig.ensureLoaded();

  const key = runtimeConfig.amapKey;
  if (!key) {
    return Promise.reject(new Error('未配置高德地图 Key，请在「系统管理 → 租户配置」中填写'));
  }

  const securityCode = runtimeConfig.amapSecurityCode;
  if (securityCode) {
    // 2.0 起，若 key 在控制台绑定了安全密钥，必须在脚本加载前设置，否则所有请求返回 INVALID_USER_SCODE
    window._AMapSecurityConfig = { securityJsCode: securityCode };
  }

  loadPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.type = 'text/javascript';
    script.async = true;
    script.src = `https://webapi.amap.com/maps?v=${AMAP_VERSION}&key=${key}&plugin=${PLUGINS.join(',')}`;
    script.onload = () => {
      if (window.AMap) {
        resolve(window.AMap);
      } else {
        loadPromise = null;
        reject(new Error('高德地图脚本已加载但 AMap 未挂载，请检查 key 是否有效'));
      }
    };
    script.onerror = () => {
      loadPromise = null;
      reject(new Error('高德地图脚本加载失败，请检查网络或 key 配置'));
    };
    document.head.appendChild(script);
  });

  return loadPromise;
}

export interface UseAMapOptions {
  /** 初始中心点（GCJ-02），默认北京 */
  center?: [number, number];
  zoom?: number;
  /**
   * 是否显示比例尺与工具条。默认 true，保证既有三个全页地图零改动。
   *
   * 小尺寸容器里必须关掉：工具条固定在右下角，在 200px 高的对话内嵌地图上会盖住相当一部分画面。
   */
  controls?: boolean;
  /**
   * `setFitView` 的四周留白（像素），默认 60。
   *
   * 同样是给小容器用的：60px 在 200px 高的地图里上下就吃掉 120px，车永远是正中间一个点，
   * 看不出周围环境。
   */
  fitPadding?: number;
}

/** 默认留白。导出是为了让调用方在自己调 `setFitView` 时能取到同一个值，而不是各写各的。 */
export const DEFAULT_FIT_PADDING = 60;

export function useAMap(options: UseAMapOptions = {}) {
  const map = shallowRef<any>(null);
  const AMapRef = shallowRef<any>(null);
  const ready = ref(false);
  const error = ref<string>('');

  async function init(container: HTMLElement) {
    try {
      const AMap = await loadAMapScript();
      AMapRef.value = AMap;
      map.value = new AMap.Map(container, {
        zoom: options.zoom ?? 12,
        center: options.center ?? [116.397428, 39.90923],
        viewMode: '2D'
      });
      if (options.controls ?? true) {
        map.value.addControl(new AMap.Scale());
        map.value.addControl(new AMap.ToolBar({ position: 'RB' }));
      }
      ready.value = true;
    } catch (loadError) {
      error.value = loadError instanceof Error ? loadError.message : String(loadError);
      ready.value = false;
    }
  }

  function destroy() {
    if (map.value) {
      map.value.destroy();
      map.value = null;
    }
    ready.value = false;
  }

  /**
   * 按本实例配置的留白做一次视野自适应。
   *
   * <p>提供它是为了让留白只在一处定义：调用方各自写 `[60,60,60,60]` 的话，
   * `fitPadding` 这个选项就形同虚设。
   */
  function fitView(overlays?: any[]) {
    if (!map.value) return;
    const padding = options.fitPadding ?? DEFAULT_FIT_PADDING;
    map.value.setFitView(overlays ?? null, false, [padding, padding, padding, padding]);
  }

  onBeforeUnmount(destroy);

  return { map, AMap: AMapRef, ready, error, init, destroy, fitView };
}
