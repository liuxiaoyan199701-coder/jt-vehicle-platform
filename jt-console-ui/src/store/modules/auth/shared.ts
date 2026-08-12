import { localStg } from '@/utils/storage';

export const AUTH_SESSION_CHANGE_EVENT = 'jt-console:auth-session-change';
export type AuthSessionChange = 'updated' | 'invalidated';

export function notifyAuthSessionChange(change: AuthSessionChange) {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent<AuthSessionChange>(AUTH_SESSION_CHANGE_EVENT, { detail: change }));
  }
}

/** Get token */
export function getToken() {
  return localStg.get('token') || '';
}

/** Clear auth storage */
export function clearAuthStorage() {
  notifyAuthSessionChange('invalidated');
  localStg.remove('token');
  localStg.remove('refreshToken');
  localStg.remove('accessTokenExpiresAt');
  localStg.remove('refreshTokenExpiresAt');
}
