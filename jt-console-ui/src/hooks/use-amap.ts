import { onBeforeUnmount, ref, shallowRef } from 'vue';

/**
 * 高德地图 JS API 加载与地图实例管理。
 *
 * 未配置 VITE_AMAP_KEY 时不会抛异常，而是把 error 置位让调用方显示占位提示——
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
function loadAMapScript(): Promise<any> {
  if (window.AMap) {
    return Promise.resolve(window.AMap);
  }
  if (loadPromise) {
    return loadPromise;
  }

  const key = import.meta.env.VITE_AMAP_KEY;
  if (!key) {
    return Promise.reject(new Error('未配置高德地图 key，请在 .env 中设置 VITE_AMAP_KEY'));
  }

  const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE;
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
}

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
      map.value.addControl(new AMap.Scale());
      map.value.addControl(new AMap.ToolBar({ position: 'RB' }));
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

  onBeforeUnmount(destroy);

  return { map, AMap: AMapRef, ready, error, init, destroy };
}
