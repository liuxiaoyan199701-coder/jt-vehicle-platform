import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeTrackTargets } from './track-targets';

test('track targets include unarchived live devices and preserve exact canonical IDs', () => {
  const targets = mergeTrackTargets(
    [{ deviceId: '00123', plateNo: 'TEST-00123' }],
    [
      { deviceId: '00123', plateNo: null },
      { deviceId: '123', plateNo: null }
    ]
  );

  assert.deepEqual(targets, [
    { deviceId: '00123', plateNo: 'TEST-00123' },
    { deviceId: '123', plateNo: null }
  ]);
});

test('an archived plate takes precedence over a live fallback label', () => {
  assert.deepEqual(
    mergeTrackTargets(
      [{ deviceId: 'device-1', plateNo: null }],
      [{ deviceId: 'device-1', plateNo: 'TEST-A1' }]
    ),
    [{ deviceId: 'device-1', plateNo: 'TEST-A1' }]
  );
});
