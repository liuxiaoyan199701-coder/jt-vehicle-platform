import { onBeforeUnmount, onMounted, ref } from 'vue';
import { localStg } from '@/utils/storage';
import { fetchGetUserInfo } from '@/service/api';
import { request } from '@/service/request';
import { ensureFreshAccessToken } from '@/service/request/shared';
import {
  AUTH_SESSION_CHANGE_EVENT,
  type AuthSessionChange
} from '@/store/modules/auth/shared';
import { decideLiveAuthChange, decideLiveSessionConnection, isConnectionStable } from './live-session-policy';

export interface LiveLocationUpdate {
  deviceId: string;
  deviceTime: string | null;
  receivedAt: string;
  lat: number;
  lng: number;
  gcjLat: number;
  gcjLng: number;
  speedKph: number | null;
  direction: number | null;
  altitude: number | null;
  mileage: number | null;
  accOn: boolean | null;
  online: boolean;
  alarms: string[];
  activeAlarmCount: number;
}

export type LiveConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'stopped';

/**
 * 服务端推来一条主动通知时在 window 上派发的事件。
 *
 * 用事件而不是回调参数：socket 只挂在首页与监控页，而通知铃铛在顶栏（任意页面都在）。
 * 让铃铛去订阅一个全局事件，两边就不必互相知道对方是否存在——铃铛照常靠定时拉取兜底，
 * 恰好有页面连着 socket 时就顺带快一步。
 */
export const LIVE_NOTICE_EVENT = 'jt-console:notice';

export interface LiveSocketOptions {
  onConnected?: () => void | Promise<void>;
}

const APPLICATION_PROTOCOL = 'jt-console.v1';
const BEARER_PROTOCOL_PREFIX = 'bearer.';
const INITIAL_RETRY_MS = 1000;
const MAX_RETRY_MS = 30000;
const STABLE_CONNECTION_MS = 10000;

/** Subscribe to authenticated live updates and own exactly one reconnecting socket. */
export function useLiveSocket(
  onLocation: (update: LiveLocationUpdate) => void,
  options: LiveSocketOptions = {}
) {
  const connected = ref(false);
  const state = ref<LiveConnectionState>('idle');
  let socket: WebSocket | null = null;
  let socketAccessToken: string | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let stabilityTimer: ReturnType<typeof setTimeout> | null = null;
  let retryDelay = INITIAL_RETRY_MS;
  let connectInFlight = false;
  let disposed = false;
  let listenersRegistered = false;

  function resolveUrl() {
    const configured = import.meta.env.VITE_WS_URL;
    if (configured) {
      return configured;
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/ws/live`;
  }

  async function connect() {
    if (
      disposed ||
      connectInFlight ||
      socket?.readyState === WebSocket.CONNECTING ||
      socket?.readyState === WebSocket.OPEN
    ) {
      return;
    }

    connectInFlight = true;
    state.value = retryDelay === INITIAL_RETRY_MS ? 'connecting' : 'reconnecting';
    try {
      const decision = await decideLiveSessionConnection({
        ensureFreshAccessToken: () => ensureFreshAccessToken(request.state),
        probeAuthenticatedSession: async () => {
          const { data } = await fetchGetUserInfo();
          return Boolean(data);
        },
        hasAccessToken: () => Boolean(localStg.get('token'))
      });
      if (decision === 'retry') {
        scheduleReconnect();
        return;
      }
      if (decision === 'stop') {
        state.value = 'stopped';
        return;
      }

      const token = localStg.get('token');
      if (!token || disposed) {
        state.value = 'stopped';
        return;
      }

      const candidate = new WebSocket(resolveUrl(), [APPLICATION_PROTOCOL, `${BEARER_PROTOCOL_PREFIX}${token}`]);
      socket = candidate;
      socketAccessToken = token;

      candidate.addEventListener('open', () => {
        if (socket !== candidate || disposed) return;
        if (candidate.protocol !== APPLICATION_PROTOCOL) {
          candidate.close(1002, 'Required subprotocol was not selected');
          return;
        }
        connected.value = true;
        state.value = 'connected';
        clearStabilityTimer();
        const openedAt = Date.now();
        stabilityTimer = setTimeout(() => {
          stabilityTimer = null;
          if (
            socket === candidate &&
            candidate.readyState === WebSocket.OPEN &&
            isConnectionStable(openedAt, Date.now(), STABLE_CONNECTION_MS)
          ) {
            retryDelay = INITIAL_RETRY_MS;
          }
        }, STABLE_CONNECTION_MS);
        Promise.resolve(options.onConnected?.()).catch(() => undefined);
      });

      candidate.addEventListener('message', event => {
        if (socket !== candidate) return;
        try {
          const message = JSON.parse(event.data);
          if (message.type === 'location' && message.data) {
            onLocation(message.data as LiveLocationUpdate);
          } else if (message.type === 'notice') {
            // 只当作「去重新拉一下」的信号，不把推来的内容直接渲染：
            // 列表与未读数一律以接口为准，免得推送与接口各说一套。
            window.dispatchEvent(new CustomEvent(LIVE_NOTICE_EVENT));
          }
        } catch {
          // Ignore one malformed message without tearing down a healthy stream.
        }
      });

      candidate.addEventListener('close', () => {
        if (socket !== candidate) return;
        clearStabilityTimer();
        socket = null;
        socketAccessToken = null;
        connected.value = false;
        scheduleReconnect();
      });

      candidate.addEventListener('error', () => {
        if (socket === candidate) {
          candidate.close();
        }
      });
    } catch {
      socket = null;
      connected.value = false;
      scheduleReconnect();
    } finally {
      connectInFlight = false;
    }
  }

  function scheduleReconnect() {
    if (disposed || reconnectTimer) {
      return;
    }
    state.value = 'reconnecting';
    const jitteredDelay = Math.min(
      MAX_RETRY_MS,
      Math.round(retryDelay * (0.8 + Math.random() * 0.4))
    );
    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      void connect();
    }, jitteredDelay);
  }

  function clearStabilityTimer() {
    if (stabilityTimer) {
      clearTimeout(stabilityTimer);
      stabilityTimer = null;
    }
  }

  function reconnectWithCurrentCredentials() {
    if (disposed) return;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    clearStabilityTimer();
    if (socket) {
      const activeSocket = socket;
      socket = null;
      socketAccessToken = null;
      activeSocket.close();
    }
    connected.value = false;
    void connect();
  }

  function handleCurrentAuthState() {
    const currentAccessToken = localStg.get('token') || null;
    const decision = decideLiveAuthChange(socketAccessToken, currentAccessToken);
    if (decision === 'stop') {
      dispose();
    } else if (decision === 'reconnect') {
      reconnectWithCurrentCredentials();
    }
  }

  function handleAuthSessionChange(event: Event) {
    const change = (event as CustomEvent<AuthSessionChange>).detail;
    if (change === 'invalidated') {
      dispose();
      return;
    }
    handleCurrentAuthState();
  }

  function dispose() {
    disposed = true;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    clearStabilityTimer();
    if (socket) {
      const activeSocket = socket;
      socket = null;
      socketAccessToken = null;
      activeSocket.close();
    }
    if (listenersRegistered) {
      window.removeEventListener(AUTH_SESSION_CHANGE_EVENT, handleAuthSessionChange);
      window.removeEventListener('storage', handleCurrentAuthState);
      listenersRegistered = false;
    }
    connected.value = false;
    state.value = 'stopped';
  }

  onMounted(() => {
    listenersRegistered = true;
    window.addEventListener(AUTH_SESSION_CHANGE_EVENT, handleAuthSessionChange);
    window.addEventListener('storage', handleCurrentAuthState);
    void connect();
  });
  onBeforeUnmount(dispose);

  return { connected, state, connect, dispose };
}
