import assert from 'node:assert/strict';
import test from 'node:test';
import { decideLiveAuthChange, decideLiveSessionConnection, isConnectionStable } from './live-session-policy';

test('connects after the protected probe refreshes a revoked but locally unexpired token', async () => {
  const calls: string[] = [];

  const decision = await decideLiveSessionConnection({
    ensureFreshAccessToken: () => {
      calls.push('local-expiry-check');
      return true;
    },
    probeAuthenticatedSession: () => {
      calls.push('protected-probe-and-single-flight-refresh');
      return true;
    },
    hasAccessToken: () => true
  });

  assert.equal(decision, 'connect');
  assert.deepEqual(calls, ['local-expiry-check', 'protected-probe-and-single-flight-refresh']);
});

test('stops when a revoked token cannot be refreshed and auth storage is cleared', async () => {
  const decision = await decideLiveSessionConnection({
    ensureFreshAccessToken: () => true,
    probeAuthenticatedSession: () => false,
    hasAccessToken: () => false
  });

  assert.equal(decision, 'stop');
});

test('keeps the login state and retries after a pure network probe failure', async () => {
  const decision = await decideLiveSessionConnection({
    ensureFreshAccessToken: () => true,
    probeAuthenticatedSession: () => {
      throw new Error('network unavailable');
    },
    hasAccessToken: () => true
  });

  assert.equal(decision, 'retry');
});

test('auth changes stop a logged-out socket and reconnect a rotated token', () => {
  assert.equal(decideLiveAuthChange('access-1', null), 'stop');
  assert.equal(decideLiveAuthChange('access-1', 'access-2'), 'reconnect');
  assert.equal(decideLiveAuthChange('access-2', 'access-2'), 'keep');
});

test('a short-lived open connection does not reset retry backoff', () => {
  assert.equal(isConnectionStable(1000, 1999, 1000), false);
  assert.equal(isConnectionStable(1000, 2000, 1000), true);
});
