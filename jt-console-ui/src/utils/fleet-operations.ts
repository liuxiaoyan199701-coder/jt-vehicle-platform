import type {
  AlarmEvent,
  AlarmLevel,
  AlarmPage,
  AlarmStatus,
  DashboardOverview,
  FleetDailyTrend,
  Geofence,
  VehicleOperationsProfile
} from '@/service/api';

export type AlarmTagType = 'error' | 'warning' | 'info' | 'default';

const levelLabels: Record<AlarmLevel, string> = {
  CRITICAL: '严重',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低'
};

const levelTagTypes: Record<AlarmLevel, AlarmTagType> = {
  CRITICAL: 'error',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'default'
};

const statusLabels: Record<AlarmStatus, string> = {
  OPEN: '待处置',
  ACKNOWLEDGED: '已确认',
  CLOSED: '已关闭'
};

export function alarmLevelLabel(level: AlarmLevel) {
  return levelLabels[level] ?? level;
}

export function alarmLevelTagType(level: AlarmLevel): AlarmTagType {
  return levelTagTypes[level] ?? 'default';
}

export function alarmStatusLabel(status: AlarmStatus) {
  return statusLabels[status] ?? status;
}

export function alarmStatusTagType(status: AlarmStatus): AlarmTagType {
  if (status === 'OPEN') return 'error';
  if (status === 'ACKNOWLEDGED') return 'warning';
  return 'default';
}

export function formatConsoleTime(value: string | null | undefined) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').replace('Z', '');
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function normalizeDashboardOverview(value: DashboardOverview | null | undefined): DashboardOverview {
  const summary = value?.summary;
  const levelCounts = new Map((value?.alarmLevels ?? []).map(item => [item.level, numberOrZero(item.count)]));

  return {
    summary: {
      fleetVehicles: numberOrZero(summary?.fleetVehicles),
      online: numberOrZero(summary?.online),
      offline: numberOrZero(summary?.offline),
      moving: numberOrZero(summary?.moving),
      idle: numberOrZero(summary?.idle),
      unknownOnline: numberOrZero(summary?.unknownOnline),
      openAlarms: numberOrZero(summary?.openAlarms),
      criticalOpenAlarms: numberOrZero(summary?.criticalOpenAlarms),
      todayDistanceKm: numberOrZero(summary?.todayDistanceKm)
    },
    dailyTrend: normalizeDailyTrend(value?.dailyTrend ?? []),
    alarmLevels: (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as AlarmLevel[]).map(level => ({
      level,
      count: levelCounts.get(level) ?? 0
    })),
    recentAlarms: value?.recentAlarms ?? []
  };
}

export function normalizeVehicleProfile(
  value: VehicleOperationsProfile
): VehicleOperationsProfile {
  return {
    ...value,
    today: {
      ...value.today,
      distanceKm: numberOrZero(value.today?.distanceKm),
      pointCount: numberOrZero(value.today?.pointCount),
      movingPoints: numberOrZero(value.today?.movingPoints),
      maxSpeedKph: numberOrZero(value.today?.maxSpeedKph),
      alarmCount: numberOrZero(value.today?.alarmCount)
    },
    last7Days: {
      ...value.last7Days,
      distanceKm: numberOrZero(value.last7Days?.distanceKm),
      activeDays: numberOrZero(value.last7Days?.activeDays),
      maxSpeedKph: numberOrZero(value.last7Days?.maxSpeedKph),
      alarmCount: numberOrZero(value.last7Days?.alarmCount)
    },
    openAlarmCount: numberOrZero(value.openAlarmCount),
    recentAlarms: value.recentAlarms ?? []
  };
}

export function normalizeAlarmPage(value: AlarmPage | null | undefined): AlarmPage {
  return {
    items: value?.items ?? [],
    total: Math.max(0, numberOrZero(value?.total)),
    page: Math.max(1, numberOrZero(value?.page) || 1),
    pageSize: Math.max(1, numberOrZero(value?.pageSize) || 20)
  };
}

export function normalizeGeofences(items: Geofence[] | null | undefined): Geofence[] {
  return (items ?? []).map(item => ({
    ...item,
    radiusMeters: numberOrZero(item.radiusMeters),
    assignedVehicleCount: numberOrZero(item.assignedVehicleCount),
    shape: item.shape ?? 'circle',
    points: item.points ?? [],
    vehicleIds: [...new Set(item.vehicleIds ?? [])]
  }));
}

export function alarmVehicleLabel(alarm: Pick<AlarmEvent, 'plateNo' | 'deviceId'>) {
  return alarm.plateNo ? `${alarm.plateNo} (${alarm.deviceId})` : alarm.deviceId;
}

function normalizeDailyTrend(items: FleetDailyTrend[]) {
  return [...items]
    .map(item => ({
      date: item.date,
      distanceKm: numberOrZero(item.distanceKm),
      activeVehicles: numberOrZero(item.activeVehicles),
      newAlarms: numberOrZero(item.newAlarms)
    }))
    .sort((left, right) => left.date.localeCompare(right.date));
}

function numberOrZero(value: number | null | undefined) {
  const result = Number(value ?? 0);
  return Number.isFinite(result) ? result : 0;
}
