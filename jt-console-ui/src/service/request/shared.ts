import { useAuthStore } from '@/store/modules/auth';
import { notifyAuthSessionChange } from '@/store/modules/auth/shared';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import { hasCredentialGenerationChanged } from './auth-retry-policy';
import type { RequestInstanceState } from './type';

const REFRESH_LOCK_NAME = 'jt-console:refresh-token';

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

/** refresh token */
async function withCrossTabRefreshLock<T>(task: () => Promise<T>) {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request(REFRESH_LOCK_NAME, { mode: 'exclusive' }, task);
  }
  return task();
}

async function handleRefreshToken(failedAuthorization?: string | null) {
  const { resetStore } = useAuthStore();

  const observedRefreshToken = localStg.get('refreshToken') || '';
  if (!observedRefreshToken) {
    await resetStore();
    return false;
  }

  return withCrossTabRefreshLock(async () => {
    const currentRefreshToken = localStg.get('refreshToken') || '';
    if (
      hasCredentialGenerationChanged(
        failedAuthorization,
        getAuthorization(),
        observedRefreshToken,
        currentRefreshToken
      )
    ) {
      return true;
    }
    if (!currentRefreshToken) {
      await resetStore();
      return false;
    }

    const { error, data } = await fetchRefreshToken(currentRefreshToken);
    if (!error) {
      localStg.set('token', data.token);
      localStg.set('refreshToken', data.refreshToken);
      localStg.set('accessTokenExpiresAt', data.accessTokenExpiresAt);
      localStg.set('refreshTokenExpiresAt', data.refreshTokenExpiresAt);
      notifyAuthSessionChange('updated');
      return true;
    }

    if (
      hasCredentialGenerationChanged(
        failedAuthorization,
        getAuthorization(),
        currentRefreshToken,
        localStg.get('refreshToken') || ''
      )
    ) {
      return true;
    }
    await resetStore();
    return false;
  });
}

export async function handleExpiredRequest(state: RequestInstanceState, failedAuthorization?: string | null) {
  const currentAuthorization = getAuthorization();
  if (failedAuthorization && currentAuthorization && failedAuthorization !== currentAuthorization) {
    return true;
  }

  if (!state.refreshTokenPromise) {
    state.refreshTokenPromise = handleRefreshToken(failedAuthorization);
    const pending = state.refreshTokenPromise;
    void pending.finally(() => {
      if (state.refreshTokenPromise === pending) {
        state.refreshTokenPromise = null;
      }
    });
  }

  return state.refreshTokenPromise;
}

/** Refresh before opening a WebSocket when the access token is already near expiry. */
export async function ensureFreshAccessToken(state: RequestInstanceState, skewMs = 5000) {
  const token = localStg.get('token');
  if (!token) {
    return false;
  }

  const expiresAt = localStg.get('accessTokenExpiresAt');
  const expiration = expiresAt ? Date.parse(expiresAt) : Number.NaN;
  if (Number.isFinite(expiration) && expiration <= Date.now() + skewMs) {
    return handleExpiredRequest(state);
  }
  return true;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
