import { computed, onBeforeUnmount, ref, shallowRef } from 'vue';
import type { ComputedRef, Ref, ShallowRef } from 'vue';
import { fetchLiveStatus } from '@/service/api';
import { DeviceStore, type DeviceRow } from '@/utils/device-store';
import { createPublishScheduler } from '@/utils/publish-scheduler';
import { createSingleFlight } from '@/utils/single-flight';
import { useLiveSocket, type LiveLocationUpdate } from './use-live-socket';

/**
 * 监控页的数据源：把 WebSocket 增量与周期性全量快照汇进 `DeviceStore`，
 * 再按固定窗口合并成一次视图发布。
 *
 * 对外只暴露一个浅层的 `rows` 与几个标量，响应式的面积因此与设备总数无关——
 * 一万台设备变化一轮，Vue 需要感知的仍然只是「数组换了个引用」。
 */

/** 合并窗口。位置本身是秒级更新，再密的刷新率也没有可感知收益。 */
const PUBLISH_WINDOW_MS = 200;

/** 收到陌生设备后的静默期：期内不再有新的陌生设备才去校准一次。 */
const UNKNOWN_DEBOUNCE_MS = 2000;

/**
 * 全量校准的周期随规模放大。
 *
 * 校准是补 WebSocket 漏包的兜底而非主通路，设备越多、单次快照越贵，
 * 固定 30 秒在万台规模下就变成一笔可观的固定开销。
 */
export function calibrationIntervalMs(deviceCount: number) {
  if (deviceCount <= 500) return 30_000;
  if (deviceCount <= 2000) return 60_000;
  return 120_000;
}

export interface DeviceFeed {
  store: DeviceStore;
  rows: ShallowRef<DeviceRow[]>;
  total: Ref<number>;
  onlineCount: Ref<number>;
  calibrating: Ref<boolean>;
  degraded: Ref<boolean>;
  calibrationError: Ref<string>;
  lastCalibratedAt: Ref<string | null>;
  connectionLabel: ComputedRef<string>;
  connectionTagType: ComputedRef<'success' | 'warning' | 'default'>;
  setKeyword: (word: string) => void;
  calibrate: () => Promise<void>;
  /** 每次视图发布后的回调，供地图图层等消费方挂钩 */
  onPublish: (listener: () => void) => void;
}

export function useDeviceFeed(): DeviceFeed {
  const store = new DeviceStore();

  const rows = shallowRef<DeviceRow[]>([]);
  const total = ref(0);
  const onlineCount = ref(0);
  const calibrating = ref(false);
  const degraded = ref(false);
  const calibrationError = ref('');
  const lastCalibratedAt = ref<string | null>(null);

  const listeners: (() => void)[] = [];
  let calibrationTimer: ReturnType<typeof setTimeout> | null = null;
  let unknownTimer: ReturnType<typeof setTimeout> | null = null;
  let disposed = false;

  const scheduler = createPublishScheduler(publish, PUBLISH_WINDOW_MS, {
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
    scheduleFrame: fn => requestAnimationFrame(fn),
    isHidden: () => document.hidden
  });

  function publish() {
    rows.value = store.visibleRows();
    total.value = store.size;
    onlineCount.value = store.onlineCount;
    listeners.forEach(listener => listener());
  }

  /** 有变化就发布，且要立刻可见（用户输入、手动重试、首次加载）。 */
  function publishNow() {
    scheduler.request();
    scheduler.flush();
  }

  /**
   * 陌生设备去抖。
   *
   * 冷启动或一批新终端同时接入时，逐台触发全量校准会打出一串重复请求；
   * 这里等静默期内不再出现新的陌生设备，才合并成一次校准。
   */
  function noteUnknownDevice() {
    if (unknownTimer) clearTimeout(unknownTimer);
    unknownTimer = setTimeout(() => {
      unknownTimer = null;
      void calibrate();
    }, UNKNOWN_DEBOUNCE_MS);
  }

  function handleLiveUpdate(update: LiveLocationUpdate) {
    const outcome = store.applyUpdate(update);
    if (outcome.unknown) {
      noteUnknownDevice();
    }
    if (outcome.changed) {
      scheduler.request();
    }
  }

  function armCalibrationTimer() {
    if (disposed) return;
    if (calibrationTimer) clearTimeout(calibrationTimer);
    calibrationTimer = setTimeout(() => void calibrate(), calibrationIntervalMs(store.size));
  }

  const calibrate = createSingleFlight(async () => {
    calibrating.value = true;
    try {
      const { data, error } = await fetchLiveStatus();
      if (error || !data) {
        degraded.value = true;
        calibrationError.value = error?.message || '实时状态校准失败';
        return;
      }

      store.applySnapshot(data);
      degraded.value = false;
      calibrationError.value = '';
      lastCalibratedAt.value = new Date().toISOString();
      publishNow();
    } finally {
      calibrating.value = false;
      armCalibrationTimer();
    }
  });

  const { connected, state: liveState } = useLiveSocket(handleLiveUpdate, {
    onConnected: () => calibrate()
  });

  const connectionLabel = computed(() => {
    if (degraded.value) return '数据降级';
    if (connected.value) return '实时';
    return liveState.value === 'connecting' ? '连接中' : '重连中';
  });

  const connectionTagType = computed<'success' | 'warning' | 'default'>(() => {
    if (degraded.value) return 'warning';
    return connected.value ? 'success' : 'default';
  });

  function onVisibilityChange() {
    if (document.hidden) return;
    // 回到前台补发积压的变化，让用户看到最新状态而不是切走那一刻的旧画面
    scheduler.flush();
  }

  document.addEventListener('visibilitychange', onVisibilityChange);
  armCalibrationTimer();

  onBeforeUnmount(() => {
    disposed = true;
    document.removeEventListener('visibilitychange', onVisibilityChange);
    scheduler.dispose();
    if (calibrationTimer) clearTimeout(calibrationTimer);
    if (unknownTimer) clearTimeout(unknownTimer);
    calibrationTimer = null;
    unknownTimer = null;
    listeners.length = 0;
  });

  return {
    store,
    rows,
    total,
    onlineCount,
    calibrating,
    degraded,
    calibrationError,
    lastCalibratedAt,
    connectionLabel,
    connectionTagType,
    setKeyword(word: string) {
      if (!store.setKeyword(word)) return;
      publishNow();
    },
    calibrate,
    onPublish(listener: () => void) {
      listeners.push(listener);
    }
  };
}
