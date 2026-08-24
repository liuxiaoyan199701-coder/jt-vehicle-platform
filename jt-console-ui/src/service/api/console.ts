import { request } from '../request';

/** 车辆档案 */
export interface Vehicle {
  deviceId: string;
  plateNo: string;
  plateColor?: string | null;
  brand?: string | null;
  channelCount: number;
  remark?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 设备实时状态。
 *
 * lat/lng 是设备上报的 WGS-84 原值；地图渲染一律用 gcjLat/gcjLng，
 * 直接拿 lat/lng 打到高德上会偏出几百米。
 */
export interface LiveStatus {
  deviceId: string;
  plateNo: string | null;
  online: boolean;
  lastSeenAt: string | null;
  deviceTime: string | null;
  lat: number | null;
  lng: number | null;
  gcjLat: number | null;
  gcjLng: number | null;
  speedKph: number | null;
  direction: number | null;
  altitude: number | null;
  mileage: number | null;
  accOn: boolean | null;
  positioned: boolean | null;
  alarmJson: string | null;
  statusJson: string | null;
  activeAlarmCount?: number;
}

export interface TrackPoint {
  deviceTime: string;
  receivedAt: string;
  lat: number;
  lng: number;
  gcjLat: number;
  gcjLng: number;
  speedKph: number | null;
  direction: number | null;
  altitude: number | null;
  mileage: number | null;
}

export interface TrackResult {
  deviceId: string;
  points: TrackPoint[];
  count: number;
  truncated: boolean;
  distanceKm: number;
  maxSpeedKph: number;
  avgSpeedKph: number;
  startTime?: string;
  endTime?: string;
}

export interface MonitorStats {
  total: number;
  online: number;
  offline: number;
  subscribers: number;
}

/** 网关返回的开流票据，wsUrl 已带一次性 token */
export interface StreamTicket {
  streamId: string;
  instanceId: string;
  wsUrl: string;
  token: string;
  state: string;
}

export type AlarmStatus = 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED';
export type AlarmLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
export type AlarmSource = 'PROTOCOL' | 'GEOFENCE' | 'RULE';

export interface AlarmEvent {
  id: number;
  deviceId: string;
  plateNo: string | null;
  type: string;
  title: string;
  source: AlarmSource;
  level: AlarmLevel;
  status: AlarmStatus;
  occurredAt: string;
  lastOccurredAt: string;
  gcjLat: number | null;
  gcjLng: number | null;
  geofenceId: number | null;
  geofenceName: string | null;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  acknowledgeNote: string | null;
  closedAt: string | null;
  closedBy: string | null;
  closeNote: string | null;
}

export interface AlarmQuery {
  status?: AlarmStatus;
  level?: AlarmLevel;
  source?: AlarmSource;
  deviceId?: string;
  type?: string;
  keyword?: string;
  start?: string;
  end?: string;
  page?: number;
  pageSize?: number;
}

export interface AlarmPage {
  items: AlarmEvent[];
  total: number;
  page: number;
  pageSize: number;
}

export type DeviceLogDirection = 'UP' | 'DOWN' | 'CONNECTION';

export interface DeviceLog {
  id: number;
  eventId: string;
  deviceId: string;
  tenantId: number | null;
  direction: DeviceLogDirection;
  /** 十进制的 808 消息 ID；连接事件与解码失败帧为 null */
  msgId: number | null;
  msgIdHex: string | null;
  serialNo: number | null;
  logTime: string;
  summary: string | null;
  rawHex: string | null;
  parsedJson: string | null;
  decodeError: boolean;
  truncated: boolean;
  instanceId: string | null;
}

export interface DeviceLogQuery {
  deviceId: string;
  start?: string;
  end?: string;
  direction?: DeviceLogDirection;
  /** 0x0200 与 512 两种写法后端都认 */
  msgId?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

export interface DeviceLogPage {
  items: DeviceLog[];
  total: number;
  page: number;
  pageSize: number;
}

export interface TerminalSummary {
  deviceId: string;
  /** 0x0100 正文里终端自报的终端 ID，与 deviceId（手机号）是两个东西 */
  terminalId: string | null;
  makerId: string | null;
  deviceModel: string | null;
  provinceId: number | null;
  cityId: number | null;
  /** 终端自报的车牌，未经确认；与 plateNo（档案车牌）分开呈现，不可混为一谈 */
  reportedPlate: string | null;
  reportedColor: number | null;
  protocolVersion: string | null;
  firstSeenAt: string;
  /** 最近一次注册/鉴权的时间，不是「最近在线」——长连不断的终端不会刷新它 */
  lastSeenAt: string;
  lastResult: string | null;
  archived: boolean;
  /** 车辆档案里的车牌；未建档时为 null */
  plateNo: string | null;
  tenantId: number | null;
  online: boolean;
  onlineSeenAt: string | null;
}

export interface TerminalQuery {
  keyword?: string;
  archived?: boolean;
  online?: boolean;
  start?: string;
  end?: string;
  page?: number;
  pageSize?: number;
}

export interface TerminalPage {
  items: TerminalSummary[];
  total: number;
  page: number;
  pageSize: number;
}

export interface Geofence {
  id: number;
  name: string;
  centerGcjLat: number;
  centerGcjLng: number;
  radiusMeters: number;
  shape: 'circle' | 'rectangle' | 'polygon' | 'route';
  points: [number, number][];
  color: string;
  enabled: boolean;
  alertOnEnter: boolean;
  alertOnExit: boolean;
  speedLimitKph: number | null;
  vehicleIds: string[];
  assignedVehicleCount: number;
  createdAt: string;
  updatedAt: string;
}

export type GeofenceMutation = Pick<
  Geofence,
  | 'name'
  | 'centerGcjLat'
  | 'centerGcjLng'
  | 'radiusMeters'
  | 'shape'
  | 'points'
  | 'color'
  | 'enabled'
  | 'alertOnEnter'
  | 'alertOnExit'
  | 'speedLimitKph'
  | 'vehicleIds'
>;

export interface FleetSummary {
  fleetVehicles: number;
  online: number;
  offline: number;
  moving: number;
  idle: number;
  unknownOnline: number;
  openAlarms: number;
  criticalOpenAlarms: number;
  todayDistanceKm: number;
}

export interface FleetDailyTrend {
  date: string;
  distanceKm: number;
  activeVehicles: number;
  newAlarms: number;
}

export interface AlarmLevelCount {
  level: AlarmLevel;
  count: number;
}

export interface DashboardOverview {
  summary: FleetSummary;
  dailyTrend: FleetDailyTrend[];
  alarmLevels: AlarmLevelCount[];
  recentAlarms: AlarmEvent[];
}

export interface VehicleDailyOperations {
  date: string;
  distanceKm: number;
  pointCount: number;
  movingPoints: number;
  maxSpeedKph: number;
  alarmCount: number;
}

export interface VehiclePeriodOperations {
  distanceKm: number;
  activeDays: number;
  maxSpeedKph: number;
  alarmCount: number;
}

export interface ConnectionEventItem {
  id: number;
  eventId: string;
  deviceId: string;
  tenantId: number | null;
  kind: string;
  reasonCode: number | null;
  reason: string | null;
  remoteAddr: string | null;
  repeatCount: number;
  eventTime: string;
  receivedAt: string;
}

export interface ConnectionLogResult {
  deviceId: string;
  summary: {
    eventCount: number;
    lastConnectedAt: string | null;
    disconnectReasons: Record<string, number>;
    registrationFailures: Record<string, number>;
    authenticationFailures: Record<string, number>;
    note?: string;
  };
  timeline: ConnectionEventItem[];
  page: number;
  pageSize: number;
  total: number;
}

export interface VehicleOperationsProfile {
  /** 未建档设备为 null：状态、轨迹与告警仍按 deviceId 聚合返回 */
  vehicle: Vehicle | null;
  status: LiveStatus | null;
  today: VehicleDailyOperations;
  last7Days: VehiclePeriodOperations;
  openAlarmCount: number;
  recentAlarms: AlarmEvent[];
}

export interface Fleet {
  id: number;
  code: string;
  name: string;
  manager: string | null;
  contactPhone: string | null;
  remark: string | null;
  createdAt: string;
  updatedAt: string;
}

export type FleetReference = Pick<Fleet, 'id' | 'code' | 'name'>;

/** 后端 FleetSummary 读模型；与运营首页的 FleetSummary 类型分开命名。 */
export interface FleetListItem {
  fleet: Fleet;
  totalVehicles: number;
  online: number;
  moving: number;
  idle: number;
  offline: number;
  openAlarms: number;
  todayDistanceKm: number;
}

export interface FleetMember {
  vehicle: Vehicle;
  fleet: FleetReference;
  online: boolean;
  speedKph: number | null;
  lastSeenAt: string | null;
  openAlarmCount: number;
  todayDistanceKm: number;
}

export interface FleetDetails {
  fleet: Fleet;
  summary: FleetListItem;
  members: FleetMember[];
}

export type FleetMutation = Pick<Fleet, 'code' | 'name' | 'manager' | 'contactPhone' | 'remark'>;

function encodePathSegment(value: string | number) {
  return encodeURIComponent(String(value));
}

// ---------------- 车辆档案 ----------------

export function fetchVehicles() {
  return request<Vehicle[]>({ url: '/vehicles' });
}

export function createVehicle(vehicle: Vehicle) {
  return request<Vehicle>({ url: '/vehicles', method: 'post', data: vehicle });
}

export function updateVehicle(deviceId: string, vehicle: Vehicle) {
  return request<Vehicle>({ url: `/vehicles/${encodePathSegment(deviceId)}`, method: 'put', data: vehicle });
}

export function deleteVehicle(deviceId: string) {
  return request<void>({ url: `/vehicles/${encodePathSegment(deviceId)}`, method: 'delete' });
}

export function fetchVehicleProfile(deviceId: string) {
  return request<VehicleOperationsProfile>({ url: `/vehicles/${encodePathSegment(deviceId)}/profile` });
}

export function fetchConnectionLog(deviceId: string, start?: string, end?: string) {
  return request<ConnectionLogResult>({
    url: '/diagnostics/connection-log',
    params: { deviceId, start, end, page: 1, pageSize: 100 }
  });
}

// ---------------- 车队管理 ----------------

export function fetchFleets(keyword?: string) {
  return request<FleetListItem[]>({
    url: '/fleets',
    params: keyword?.trim() ? { keyword: keyword.trim() } : undefined
  });
}

export function fetchFleet(id: number) {
  return request<FleetDetails>({ url: `/fleets/${encodePathSegment(id)}` });
}

export function createFleet(fleet: FleetMutation) {
  return request<FleetDetails>({ url: '/fleets', method: 'post', data: fleet });
}

export function updateFleet(id: number, fleet: FleetMutation) {
  return request<FleetDetails>({ url: `/fleets/${encodePathSegment(id)}`, method: 'put', data: fleet });
}

export function replaceFleetVehicles(id: number, deviceIds: string[]) {
  return request<FleetDetails>({
    url: `/fleets/${encodePathSegment(id)}/vehicles`,
    method: 'put',
    data: { deviceIds }
  });
}

export function deleteFleet(id: number) {
  return request<void>({ url: `/fleets/${encodePathSegment(id)}`, method: 'delete' });
}

// ---------------- 电子运单 ----------------

export interface WaybillItem {
  id: number;
  deviceId: string;
  reportedAt: string;
  receivedAt: string;
  rawLength: number;
  preview: string;
  utf8: boolean;
}

export interface WaybillPage {
  items: WaybillItem[];
  total: number;
  page: number;
  pageSize: number;
}

export function fetchWaybills(deviceId: string, page = 1, pageSize = 20) {
  return request<WaybillPage>({
    url: `/vehicles/${encodePathSegment(deviceId)}/waybills`,
    params: { page, pageSize }
  });
}

export interface WaybillRaw {
  base64: string;
  length: number;
  fileName: string;
}

export function fetchWaybillRaw(deviceId: string, waybillId: number) {
  return request<WaybillRaw>({
    url: `/vehicles/${encodePathSegment(deviceId)}/waybills/${encodePathSegment(waybillId)}/raw`
  });
}

// ---------------- 实时监控 ----------------

export function fetchLiveStatus() {
  return request<LiveStatus[]>({ url: '/monitor/live' });
}

export function fetchMonitorStats() {
  return request<MonitorStats>({ url: '/monitor/stats' });
}

// ---------------- 轨迹 ----------------

/**
 * @param start 无时区本地时间，如 2026-08-11T00:00:00（与设备上报的 deviceTime 同格式）
 */
export function fetchTrack(deviceId: string, start: string, end: string) {
  return request<TrackResult>({ url: '/tracks', params: { deviceId, start, end } });
}

// ---------------- 视频开流 ----------------

export function openStream(deviceId: string, channel: number, streamKind: 'main' | 'sub' = 'main') {
  return request<StreamTicket>({
    url: '/stream/open',
    method: 'post',
    data: { deviceId, channel, streamKind }
  });
}

export interface RecordingRange {
  startTime: string;
  endTime: string;
  channel?: number;
  streamKind?: string;
  source?: string;
}

export interface DeviceRecordingResource {
  channel: number;
  startTime: string;
  endTime: string;
  warnBit: number;
  mediaType: number;
  streamType: number;
  storageType: number;
  size: number;
}

export interface RecordingSearchResult {
  platform: {
    available: boolean;
    reason?: string | null;
    segments: RecordingRange[];
  };
  device: {
    available: boolean;
    reason?: string | null;
    resources: DeviceRecordingResource[];
  };
}

export function searchRecordings(
  deviceId: string,
  channel: number,
  startTime: string,
  endTime: string,
  streamKind: 'main' | 'sub' = 'main'
) {
  return request<RecordingSearchResult>({
    url: '/recordings/search',
    params: { deviceId, channel, streamKind, startTime, endTime }
  });
}

export function fetchRecordingsAround(deviceId: string, at: string, channel = 1) {
  return request<RecordingRange[]>({
    url: '/recordings/around',
    params: { deviceId, at, channel }
  });
}

export interface RecordingUploadTask {
  id: string;
  deviceId: string;
  commandSerialNo: number | null;
  channelNo: number;
  startAt: string;
  endAt: string;
  status: 'CREATED' | 'DISPATCHED' | 'FILE_RECEIVED' | 'COMPLETED' | 'FAILED';
  resultCode: number | null;
  fileName: string | null;
  fileSize: number | null;
  accessAddress: string | null;
  contentType: string | null;
  createdAt: string;
  updatedAt: string;
}

export function createRecordingUpload(
  deviceId: string,
  resource: DeviceRecordingResource,
  condition = 7
) {
  return request<RecordingUploadTask>({
    url: '/recording-uploads',
    method: 'post',
    data: {
      deviceId,
      channel: resource.channel,
      startTime: resource.startTime,
      endTime: resource.endTime,
      warnBit1: resource.warnBit % 0x1_0000_0000,
      warnBit2: Math.floor(resource.warnBit / 0x1_0000_0000),
      mediaType: resource.mediaType,
      streamType: resource.streamType,
      storageType: resource.storageType,
      condition
    }
  });
}

export function fetchRecordingUploads(deviceId: string, limit = 50) {
  return request<RecordingUploadTask[]>({
    url: '/recording-uploads',
    params: { deviceId, limit }
  });
}

export interface RecordingStorageMetrics {
  recordingOccupiedBytes: number;
  recordingUsableBytes: number;
  recordingTotalBytes: number;
  retentionDays: number;
  maxBytes: number;
  realtimeEnabled: boolean;
  playbackEnabled: boolean;
}

export function fetchRecordingStorage() {
  return request<RecordingStorageMetrics>({ url: '/system/recording-storage' });
}

export function openPlaybackStream(
  deviceId: string,
  channel: number,
  startTime: string,
  endTime: string
) {
  return request<StreamTicket>({
    url: '/stream/open-playback',
    method: 'post',
    data: { deviceId, channel, startTime, endTime }
  });
}

// ---------------- 运营首页 ----------------

export function fetchDashboardOverview() {
  return request<DashboardOverview>({ url: '/dashboard/overview' });
}

// ---------------- 终端管理 ----------------

export function fetchTerminals(params: TerminalQuery = {}) {
  return request<TerminalPage>({ url: '/terminals', params });
}

/** 把台账里的终端建成车辆档案。设备号由后端从台账取，请求体只带档案字段。 */
export function archiveTerminal(deviceId: string, vehicle: Partial<Vehicle>) {
  return request<Vehicle>({
    url: `/terminals/${encodePathSegment(deviceId)}/archive`,
    method: 'post',
    data: vehicle
  });
}

// ---------------- 设备日志 ----------------

export function fetchDeviceLogs(params: DeviceLogQuery) {
  return request<DeviceLogPage>({ url: '/device-logs', params });
}

// ---------------- 告警处置 ----------------

export function fetchAlarms(params: AlarmQuery = {}) {
  return request<AlarmPage>({ url: '/alarms', params });
}

export function fetchAlarm(id: number) {
  return request<AlarmEvent>({ url: `/alarms/${encodePathSegment(id)}` });
}

export function acknowledgeAlarm(id: number, note: string) {
  return request<AlarmEvent>({
    url: `/alarms/${encodePathSegment(id)}/acknowledge`,
    method: 'post',
    data: { note }
  });
}

export function closeAlarm(id: number, note: string) {
  return request<AlarmEvent>({
    url: `/alarms/${encodePathSegment(id)}/close`,
    method: 'post',
    data: { note }
  });
}

// ---------------- 电子围栏 ----------------

export function fetchGeofences() {
  return request<Geofence[]>({ url: '/geofences' });
}

export function fetchGeofence(id: number) {
  return request<Geofence>({ url: `/geofences/${encodePathSegment(id)}` });
}

export function createGeofence(geofence: GeofenceMutation) {
  return request<Geofence>({ url: '/geofences', method: 'post', data: geofence });
}

export function updateGeofence(id: number, geofence: GeofenceMutation) {
  return request<Geofence>({ url: `/geofences/${encodePathSegment(id)}`, method: 'put', data: geofence });
}

export function replaceGeofenceVehicles(id: number, deviceIds: string[]) {
  return request<Geofence>({
    url: `/geofences/${encodePathSegment(id)}/vehicles`,
    method: 'put',
    data: { deviceIds }
  });
}

export function setGeofenceEnabled(id: number, enabled: boolean) {
  return request<Geofence>({
    url: `/geofences/${encodePathSegment(id)}/enabled`,
    method: 'put',
    data: { enabled }
  });
}

export function deleteGeofence(id: number) {
  return request<void>({ url: `/geofences/${encodePathSegment(id)}`, method: 'delete' });
}

export type AlarmRuleType = 'SPEED_LIMIT' | 'IDLE_TIMEOUT' | 'FATIGUE_DRIVING';

export interface AlarmRule {
  id: number;
  name: string;
  type: AlarmRuleType;
  thresholdKph: number;
  durationMinutes: number;
  level: AlarmLevel;
  enabled: boolean;
  vehicleIds: string[];
  assignedVehicleCount: number;
  createdAt: string;
  updatedAt: string;
}

export type AlarmRuleMutation = Pick<
  AlarmRule,
  'name' | 'type' | 'thresholdKph' | 'durationMinutes' | 'level' | 'enabled' | 'vehicleIds'
>;

export function fetchAlarmRules() {
  return request<AlarmRule[]>({ url: '/alarm-rules' });
}

export function createAlarmRule(rule: AlarmRuleMutation) {
  return request<AlarmRule>({ url: '/alarm-rules', method: 'post', data: rule });
}

export function updateAlarmRule(id: number, rule: AlarmRuleMutation) {
  return request<AlarmRule>({ url: `/alarm-rules/${encodePathSegment(id)}`, method: 'put', data: rule });
}

export function setAlarmRuleEnabled(id: number, enabled: boolean) {
  return request<AlarmRule>({
    url: `/alarm-rules/${encodePathSegment(id)}/enabled`,
    method: 'put',
    data: { enabled }
  });
}

export function deleteAlarmRule(id: number) {
  return request<void>({ url: `/alarm-rules/${encodePathSegment(id)}`, method: 'delete' });
}

export interface VehicleReportRow {
  deviceId: string;
  plateNo: string;
  totalDistanceKm: number;
  activeDays: number;
  totalAlarms: number;
  maxSpeedKph: number;
}

export function fetchVehicleReport(start: string, end: string) {
  return request<VehicleReportRow[]>({
    url: '/reports/vehicles',
    params: { start, end }
  });
}

// ---------------- 远程控制（下行指令代理） ----------------

export type CommandName =
  | 'text'
  | 'ptz'
  | 'ptz-adjust'
  | 'vehicle-control'
  | 'photo'
  | 'callback'
  | 'track-follow'
  | 'query-params'
  | 'query-attributes'
  | 'upgrade';

/** 指令应答：后端已把 T0001/T0805/T0201_0500 统一成 message + success */
export interface CommandResult {
  message: string;
  success: boolean;
  resultCode?: number;
  result?: number;
  photoIds?: number[];
}

export function sendDeviceCommand(command: CommandName, payload: Record<string, unknown>) {
  return request<CommandResult>({ url: `/commands/${command}`, method: 'post', data: payload });
}

export function queryTerminalInfo(command: 'query-params' | 'query-attributes', deviceId: string) {
  return request<Record<string, unknown>>({
    url: `/commands/${command}`,
    method: 'post',
    data: { deviceId }
  });
}

export interface UpgradePackage {
  id: number;
  name: string;
  version: string;
  makerId: string;
  fileName: string;
  filePath: string;
  sizeBytes: number;
  sha256: string;
  createdAt: string;
  updatedAt: string;
}

export function fetchUpgradePackages() {
  return request<UpgradePackage[]>({ url: '/upgrade-packages' });
}

export function uploadUpgradePackage(file: File, name: string, version: string, makerId: string) {
  const form = new FormData();
  form.append('file', file);
  form.append('name', name);
  form.append('version', version);
  form.append('makerId', makerId);
  return request<UpgradePackage>({
    url: '/upgrade-packages',
    method: 'post',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}

export function deleteUpgradePackage(id: number) {
  return request<void>({ url: `/upgrade-packages/${encodePathSegment(id)}`, method: 'delete' });
}

export interface Driver {
  id: number;
  name: string;
  idCard: string;
  licenseNo: string;
  institution: string | null;
  licenseValidPeriod: string | null;
  phone: string | null;
  remark: string | null;
  departmentId: number | null;
  tenantId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface DriverMutation {
  name: string;
  idCard: string;
  licenseNo: string;
  institution?: string | null;
  licenseValidPeriod?: string | null;
  phone?: string | null;
  remark?: string | null;
  departmentId?: number | null;
  tenantId?: number | null;
}

export interface DriverIdentityEvent {
  id: number;
  eventId: string;
  deviceId: string;
  status: number;
  cardStatus: number;
  name: string | null;
  licenseNo: string | null;
  institution: string | null;
  licenseValidPeriod: string | null;
  idCard: string | null;
  driverId: number | null;
  deviceTime: string;
  receivedAt: string;
}

export interface DriverSession {
  id: number;
  deviceId: string;
  driverId: number | null;
  driverName: string | null;
  licenseNo: string | null;
  startedAt: string;
  endedAt: string | null;
  source: string;
}

export interface CurrentDriver {
  deviceId: string;
  driverId: number | null;
  driverName: string | null;
  licenseNo: string | null;
  startedAt: string;
  source: string;
}

export function fetchDrivers(params?: {
  keyword?: string;
  departmentId?: number;
  page?: number;
  pageSize?: number;
}) {
  return request<{ items: Driver[]; total: number }>({ url: '/drivers', params });
}

export function fetchDriver(id: number) {
  return request<Driver>({ url: `/drivers/${encodePathSegment(id)}` });
}

export function createDriver(driver: DriverMutation) {
  return request<Driver>({ url: '/drivers', method: 'post', data: driver });
}

export function updateDriver(id: number, driver: DriverMutation) {
  return request<Driver>({ url: `/drivers/${encodePathSegment(id)}`, method: 'put', data: driver });
}

export function deleteDriver(id: number) {
  return request<void>({ url: `/drivers/${encodePathSegment(id)}`, method: 'delete' });
}

export function fetchDriverSessions(id: number) {
  return request<DriverSession[]>({ url: `/drivers/${encodePathSegment(id)}/sessions` });
}

export function fetchIdentityEvents(params?: {
  deviceId?: string;
  unmatched?: boolean;
  failed?: boolean;
  start?: string;
  end?: string;
  page?: number;
  pageSize?: number;
}) {
  return request<DriverIdentityEvent[]>({ url: '/drivers/identity-events', params });
}

export function fetchCurrentDriver(deviceId: string) {
  return request<CurrentDriver | null>({
    url: `/vehicles/${encodePathSegment(deviceId)}/driver`
  });
}

export function bindDriver(deviceId: string, driverId: number) {
  return request<DriverSession>({
    url: `/vehicles/${encodePathSegment(deviceId)}/driver`,
    method: 'post',
    data: { driverId }
  });
}

export function unbindDriver(deviceId: string) {
  return request<void>({
    url: `/vehicles/${encodePathSegment(deviceId)}/driver`,
    method: 'delete'
  });
}

// ---------------- 多媒体（拍照结果） ----------------

export interface MediaFileItem {
  id: number;
  deviceId: string;
  fileId: number;
  fileType: string;
  fileFormat: string | null;
  fileName: string | null;
  size: number | null;
  accessAddress: string | null;
  channelId: number | null;
  /**
   * 0x0801 的事件项编码：0 平台下发指令、1 定时动作、2 抢劫报警触发、3 碰撞侧翻报警触发。
   * **不是告警 ID**——协议没有提供能定位到具体某条告警的字段。
   */
  eventCode: number | null;
  /** 抓拍位置。设备当时未定位则为 null，**不会是 0**，可直接用于判断是否显示地图。 */
  lat: number | null;
  lng: number | null;
  gcjLat: number | null;
  gcjLng: number | null;
  capturedAt: string;
}

export interface MediaPage {
  items: MediaFileItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface MediaQuery {
  deviceId?: string;
  fileType?: string;
  channelId?: number | null;
  /** manual = 指令或定时；alarm = 报警触发 */
  trigger?: 'manual' | 'alarm' | null;
  locatedOnly?: boolean;
  start?: string;
  end?: string;
  page?: number;
  pageSize?: number;
}

export function fetchRecentMedia(deviceId: string, limit = 20) {
  return request<MediaFileItem[]>({ url: '/media/recent', params: { deviceId, limit } });
}

/** 多媒体检索。不给 deviceId 即跨车辆查。 */
export function fetchMedia(query: MediaQuery) {
  return request<MediaPage>({ url: '/media', params: query });
}

/**
 * 某台车在某个时刻前后的抓拍。
 *
 * 用于告警详情的「该时段抓拍」。注意它返回的是**时间邻近**的照片而不是「属于这条告警」的
 * 照片——0x0801 没有携带告警标识，因果关系无从建立，界面文案不要暗示因果。
 */
export function fetchMediaAround(deviceId: string, at: string, limit = 20) {
  return request<MediaFileItem[]>({ url: '/media/around', params: { deviceId, at, limit } });
}

// ---------------- 看板今日要点 ----------------

export interface BriefingLink {
  /** vue-router 路由名，如 track / monitor / alarm / media */
  routeName: string;
  query: Record<string, string>;
  label: string | null;
}

export interface BriefingItem {
  id: string;
  category: 'OFFLINE' | 'ALARM' | 'MILEAGE' | 'CAMERA' | 'FLEET';
  severity: 'INFO' | 'WARN' | 'CRITICAL';
  /** 模型改写过的措辞。**数字来自 facts，不来自这句话** */
  text: string;
  /** 支撑数据，由服务端计算后原样透传，未经模型 */
  facts: Record<string, unknown>;
  deviceIds: string[];
  link: BriefingLink | null;
}

export interface Briefing {
  items: BriefingItem[];
  /** OK 正常 / DEGRADED 模型降级但有内容 / FAILED 生成失败 / PENDING 尚未生成 / NONE 不适用 */
  status: string;
  updatedAt: string | null;
  error: string | null;
  /** 是否因数据范围隐藏了部分要点。为 true 时要向用户说明，免得以为平台漏报 */
  filtered: boolean;
}

export function fetchBriefing() {
  return request<Briefing>({ url: '/dashboard/briefing' });
}

/** 手动重新分析。会真的调一次模型，前端要给出加载态。 */
export function refreshBriefing() {
  return request<Briefing>({ url: '/dashboard/briefing/refresh', method: 'post' });
}

// ---------------- AI 对话图片附件 ----------------

/** 上传一张对话里要发给 AI 的图片，返回附件 id。 */
export function uploadAiAttachment(file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<{ id: string }>({
    url: '/ai/attachments',
    method: 'post',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}
