import assert from 'node:assert/strict';
import test from 'node:test';
import {
  alarmLevelLabel,
  alarmLevelTagType,
  normalizeAlarmPage,
  normalizeDashboardOverview,
  normalizeGeofences,
  normalizeVehicleProfile
} from './fleet-operations';

test('alarm levels preserve the four-level protocol contract', () => {
  assert.deepEqual(
    (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).map(level => [
      alarmLevelLabel(level),
      alarmLevelTagType(level)
    ]),
    [
      ['严重', 'error'],
      ['高', 'warning'],
      ['中', 'info'],
      ['低', 'default']
    ]
  );
});

test('dashboard normalization supplies zero values and all alarm levels', () => {
  const dashboard = normalizeDashboardOverview(undefined);
  assert.equal(dashboard.summary.fleetVehicles, 0);
  assert.equal(dashboard.summary.todayDistanceKm, 0);
  assert.deepEqual(dashboard.alarmLevels, [
    { level: 'CRITICAL', count: 0 },
    { level: 'HIGH', count: 0 },
    { level: 'MEDIUM', count: 0 },
    { level: 'LOW', count: 0 }
  ]);
});

test('dashboard trend is sorted and invalid metrics become zero', () => {
  const dashboard = normalizeDashboardOverview({
    summary: {} as never,
    dailyTrend: [
      { date: '2026-08-11', distanceKm: Number.NaN, activeVehicles: 2, newAlarms: 1 },
      { date: '2026-08-10', distanceKm: 8.5, activeVehicles: 1, newAlarms: 0 }
    ],
    alarmLevels: [{ level: 'HIGH', count: 2 }],
    recentAlarms: []
  });
  assert.deepEqual(dashboard.dailyTrend.map(item => item.date), ['2026-08-10', '2026-08-11']);
  assert.equal(dashboard.dailyTrend[1].distanceKm, 0);
  assert.equal(dashboard.alarmLevels[1].count, 2);
});

test('vehicle profile normalization keeps exact canonical device id', () => {
  const profile = normalizeVehicleProfile({
    vehicle: { deviceId: '00123', plateNo: '测试A123', channelCount: 4 },
    status: null,
    today: { date: '2026-08-11' } as never,
    last7Days: {} as never,
    openAlarmCount: 0,
    recentAlarms: []
  });
  assert.equal(profile.vehicle?.deviceId, '00123');
  assert.equal(profile.today.distanceKm, 0);
  assert.equal(profile.last7Days.activeDays, 0);
});

test('alarm page normalization supplies stable pagination', () => {
  assert.deepEqual(normalizeAlarmPage(undefined), { items: [], total: 0, page: 1, pageSize: 20 });
});

test('geofence normalization deduplicates assignments without normalizing ids', () => {
  const [geofence] = normalizeGeofences([
    {
      id: 1,
      name: '园区',
      centerGcjLat: 39.9,
      centerGcjLng: 116.4,
      radiusMeters: 500,
      shape: 'circle',
      points: [],
      color: '#18a058',
      enabled: true,
      alertOnEnter: true,
      alertOnExit: true,
      speedLimitKph: null,
      vehicleIds: ['00123', '00123', '123'],
      assignedVehicleCount: 3,
      createdAt: '',
      updatedAt: ''
    }
  ]);
  assert.deepEqual(geofence.vehicleIds, ['00123', '123']);
});
