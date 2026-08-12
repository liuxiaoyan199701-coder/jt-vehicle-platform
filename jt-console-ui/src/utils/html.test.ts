import assert from 'node:assert/strict';
import test from 'node:test';
import { escapeHtmlText } from './html';

test('vehicle labels cannot inject marker HTML', () => {
  const escaped = escapeHtmlText('<img src=x onerror="steal()">');
  assert.equal(escaped, '&lt;img src=x onerror=&quot;steal()&quot;&gt;');
  assert.equal(escaped.includes('<img'), false);
});
