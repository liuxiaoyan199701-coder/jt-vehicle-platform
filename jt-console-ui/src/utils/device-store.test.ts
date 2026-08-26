import assert from 'node:assert/strict';
import test from 'node:test';
import type { LiveStatus } from '@/service/api';
import type { LiveLocationUpdate } from '@/hooks/use-live-socket';
import { DeviceStore } from './device-store';

function live(deviceId: string, overrides: Partial<LiveStatus> = {}): LiveStatus {
  return {
    deviceId,
    plateNo: null,
    online: true,
    lastSeenAt: '2026-08-25T10:00:00Z',
    deviceTime: '2026-08-25T10:00:00Z',
    lat: 39.9,
    lng: 116.4,
    gcjLat: 39.9,
    gcjLng: 116.4,
    speedKph: 0,
    direction: 0,
    altitude: 0,
    mileage: 0,
    accOn: true,
    positioned: true,
    alarmJson: null,
    statusJson: null,
    activeAlarmCount: 0,
    ...overrides
  };
}

function update(deviceId: string, overrides: Partial<LiveLocationUpdate> = {}): LiveLocationUpdate {
  return {
    deviceId,
    deviceTime: '2026-08-25T10:00:10Z',
    receivedAt: '2026-08-25T10:00:10Z',
    lat: 39.91,
    lng: 116.41,
    gcjLat: 39.91,
    gcjLng: 116.41,
    speedKph: 42,
    direction: 90,
    altitude: 30,
    mileage: 1200,
    accOn: true,
    online: true,
    alarms: [],
    activeAlarmCount: 0,
    ...overrides
  };
}

test('a late full snapshot does not roll a newer increment back', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { plateNo: '京A00001', lastSeenAt: '2026-08-25T10:00:00Z' })]);
  store.applyUpdate(update('A', { receivedAt: '2026-08-25T10:00:30Z', speedKph: 60 }));

  store.applySnapshot([live('A', { plateNo: '京A00001', lastSeenAt: '2026-08-25T10:00:10Z', speedKph: 5 })]);

  assert.equal(store.get('A')?.speedKph, 60);
  assert.equal(store.get('A')?.lastSeenAt, '2026-08-25T10:00:30Z');
});

test('a late snapshot still fills in a plate the increment could not carry', () => {
  const store = new DeviceStore();
  // 增量先建了档，此时还不知道车牌
  store.applyUpdate(update('A', { receivedAt: '2026-08-25T10:00:30Z' }));
  assert.equal(store.get('A')?.plateNo, null);

  store.applySnapshot([live('A', { plateNo: '京A00001', lastSeenAt: '2026-08-25T10:00:00Z' })]);

  assert.equal(store.get('A')?.plateNo, '京A00001');
  assert.equal(store.get('A')?.label, '京A00001');
  // 车牌补上了，但位置仍是更新的那份
  assert.equal(store.get('A')?.lastSeenAt, '2026-08-25T10:00:30Z');
});

test('an unchanged device keeps its row object across a refresh', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { plateNo: '京A00001' }), live('B', { plateNo: '京B00002' })]);
  const before = store.visibleRows();

  store.applyUpdate(update('A', { speedKph: 88 }));
  const after = store.visibleRows();

  assert.notEqual(after[0], before[0], '变化的设备应换成新对象');
  assert.equal(after[1], before[1], '未变化的设备应保持同一引用');
});

test('a repeated snapshot leaves every row object untouched', () => {
  const store = new DeviceStore();
  const snapshot = [live('A', { plateNo: '京A00001' }), live('B', { plateNo: '京B00002' })];
  store.applySnapshot(snapshot);
  const before = store.visibleRows();

  const outcome = store.applySnapshot(snapshot.map(item => ({ ...item })));

  assert.equal(outcome.changed, false);
  assert.equal(outcome.resort, false);
  assert.deepEqual(store.visibleRows(), before);
  assert.equal(store.visibleRows()[0], before[0]);
});

test('an increment for a known device is not reported as unknown', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A')]);

  assert.equal(store.applyUpdate(update('A')).unknown, false);
});

test('an increment for an unfamiliar device is reported so the caller can calibrate', () => {
  const store = new DeviceStore();
  const outcome = store.applyUpdate(update('ghost'));

  assert.equal(outcome.unknown, true);
  assert.equal(outcome.resort, true);
  assert.equal(store.size, 1);
  assert.equal(store.get('ghost')?.plateNo, null);
});

test('a stale increment is dropped', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { lastSeenAt: '2026-08-25T10:00:30Z' })]);

  const outcome = store.applyUpdate(update('A', { receivedAt: '2026-08-25T10:00:10Z', speedKph: 7 }));

  assert.equal(outcome.changed, false);
  assert.notEqual(store.get('A')?.speedKph, 7);
});

test('a position report does not ask for a resort', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { plateNo: '京A00001' })]);

  const outcome = store.applyUpdate(update('A', { speedKph: 60, gcjLat: 31.2, gcjLng: 121.4 }));

  assert.equal(outcome.changed, true);
  assert.equal(outcome.resort, false);
});

test('coming online asks for a resort and lifts the device above offline ones', () => {
  const store = new DeviceStore();
  store.applySnapshot([
    live('A', { plateNo: '京A00001', online: false }),
    live('B', { plateNo: '京B00002', online: true })
  ]);
  assert.deepEqual(
    store.visibleRows().map(row => row.deviceId),
    ['B', 'A']
  );

  const outcome = store.applyUpdate(update('A', { receivedAt: '2026-08-25T10:00:30Z' }));

  assert.equal(outcome.resort, true);
  assert.deepEqual(
    store.visibleRows().map(row => row.deviceId),
    ['A', 'B']
  );
});

test('the online tally follows both snapshots and increments', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { online: true }), live('B', { online: false }), live('C', { online: true })]);
  assert.equal(store.onlineCount, 2);

  store.applyUpdate(update('B', { receivedAt: '2026-08-25T10:00:30Z' }));
  assert.equal(store.onlineCount, 3);

  store.applySnapshot([live('A', { online: false, lastSeenAt: '2026-08-25T10:01:00Z' })]);
  assert.equal(store.onlineCount, 2);
  assert.equal(store.size, 3);
});

test('rows sort by online first, then plate, with the device id as the tie-break', () => {
  const store = new DeviceStore();
  store.applySnapshot([
    live('D2', { plateNo: null, online: false }),
    live('D1', { plateNo: null, online: false }),
    live('C1', { plateNo: '京C00003', online: false }),
    live('B1', { plateNo: '京B00002', online: true }),
    live('A1', { plateNo: null, online: true })
  ]);

  assert.deepEqual(
    store.visibleRows().map(row => row.deviceId),
    ['B1', 'A1', 'C1', 'D1', 'D2']
  );
});

test('devices without a plate keep a stable order across resorts', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('D2', { online: false }), live('D1', { online: false })]);
  const first = store.visibleRows().map(row => row.deviceId);

  store.applySnapshot([live('D3', { online: false })]);
  const second = store.visibleRows().map(row => row.deviceId);

  assert.deepEqual(first, ['D1', 'D2']);
  assert.deepEqual(second, ['D1', 'D2', 'D3']);
});

test('the keyword matches device id and plate regardless of case', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('abc123', { plateNo: '京A00001' }), live('xyz789', { plateNo: '沪B00002' })]);

  store.setKeyword('ABC');
  assert.deepEqual(
    store.visibleRows().map(row => row.deviceId),
    ['abc123']
  );

  store.setKeyword('沪B');
  assert.deepEqual(
    store.visibleRows().map(row => row.deviceId),
    ['xyz789']
  );

  store.setKeyword('   ');
  assert.equal(store.visibleRows().length, 2);
});

test('setting the same keyword twice reports no change', () => {
  const store = new DeviceStore();
  assert.equal(store.setKeyword('abc'), true);
  assert.equal(store.setKeyword('  ABC  '), false);
});

test('a filtered-out device still counts toward the totals', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { online: true }), live('B', { online: true })]);
  store.setKeyword('A');

  assert.equal(store.visibleRows().length, 1);
  assert.equal(store.size, 2);
  assert.equal(store.onlineCount, 2);
});

test('alarms are parsed once at write time, not on every read', () => {
  const store = new DeviceStore();
  const alarmJson = JSON.stringify(['超速', '疲劳驾驶']);
  store.applySnapshot([live('A', { alarmJson })]);

  const parsed = store.get('A')?.alarms;
  assert.deepEqual(parsed, ['超速', '疲劳驾驶']);

  // 同样的告警串再来一次快照，应沿用同一个已解析的数组
  store.applySnapshot([live('A', { alarmJson, lastSeenAt: '2026-08-25T10:00:20Z' })]);
  assert.equal(store.get('A')?.alarms, parsed);
});

test('a malformed alarm payload degrades to an empty list instead of throwing', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { alarmJson: '{oops' }), live('B', { alarmJson: '"not-an-array"' })]);

  assert.deepEqual(store.get('A')?.alarms, []);
  assert.deepEqual(store.get('B')?.alarms, []);
  assert.equal(store.visibleRows().length, 2);
});

test('an increment carries its alarms straight through', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A')]);
  store.applyUpdate(update('A', { alarms: ['紧急报警'], activeAlarmCount: 1 }));

  assert.deepEqual(store.get('A')?.alarms, ['紧急报警']);
  assert.equal(store.get('A')?.activeAlarmCount, 1);
});

test('a device the snapshot has not caught up with is kept, not dropped', () => {
  const store = new DeviceStore();
  store.applyUpdate(update('fresh'));

  store.applySnapshot([live('A', { plateNo: '京A00001' })]);

  assert.equal(store.size, 2);
  assert.ok(store.get('fresh'));
});

test('the label falls back to the device id when there is no plate', () => {
  const store = new DeviceStore();
  store.applySnapshot([live('A', { plateNo: null }), live('B', { plateNo: '京B00002' })]);

  assert.equal(store.get('A')?.label, 'A');
  assert.equal(store.get('B')?.label, '京B00002');
});
