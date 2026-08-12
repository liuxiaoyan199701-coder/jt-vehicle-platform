import assert from 'node:assert/strict';
import test from 'node:test';
import { decideUnauthorizedAction, hasCredentialGenerationChanged } from './auth-retry-policy';

test('a late 401 retries the current access token without a second refresh rotation', () => {
  const oldAuthorization = 'Bearer access-generation-1';
  const newAuthorization = 'Bearer access-generation-2';
  let currentAuthorization = oldAuthorization;
  let refreshCount = 0;

  const firstAction = decideUnauthorizedAction(oldAuthorization, currentAuthorization, false);
  assert.equal(firstAction, 'refresh');
  refreshCount += 1;
  currentAuthorization = newAuthorization;

  const lateAction = decideUnauthorizedAction(oldAuthorization, currentAuthorization, false);
  assert.equal(lateAction, 'retry-current');
  assert.equal(refreshCount, 1);
});

test('a retried request rejected with the current token logs out', () => {
  const authorization = 'Bearer current-access';
  assert.equal(decideUnauthorizedAction(authorization, authorization, true), 'logout');
});

test('a tab waiting for the refresh lock reuses credentials rotated by another tab', () => {
  assert.equal(
    hasCredentialGenerationChanged(
      'Bearer access-generation-1',
      'Bearer access-generation-2',
      'refresh-generation-1',
      'refresh-generation-2'
    ),
    true
  );
});
