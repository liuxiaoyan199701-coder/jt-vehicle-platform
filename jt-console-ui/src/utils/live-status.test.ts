import assert from 'node:assert/strict';
import test from 'node:test';
import { reconcileLiveStatusSnapshot } from './live-status';

test('a late full snapshot preserves a newer socket increment', () => {
  type Status = { deviceId: string; lastSeenAt: string; online: boolean; plateNo: string | null };
  const current: Status[] = [
    { deviceId: '00123', lastSeenAt: '2026-08-11T12:00:10Z', online: true, plateNo: null }
  ];
  const snapshot: Status[] = [
    { deviceId: '00123', lastSeenAt: '2026-08-11T12:00:00Z', online: false, plateNo: '测试A123' }
  ];

  assert.deepEqual(reconcileLiveStatusSnapshot(current, snapshot), [
    { deviceId: '00123', lastSeenAt: '2026-08-11T12:00:10Z', online: true, plateNo: '测试A123' }
  ]);
});

test('an increment absent from the snapshot remains visible', () => {
  const current = [{ deviceId: 'new-device', lastSeenAt: '2026-08-11T12:00:10Z' }];
  assert.deepEqual(reconcileLiveStatusSnapshot(current, []), current);
});
