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
export type AlarmSource = 'PROTOCOL' | 'GEOFENCE';

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

export interface Geofence {
  id: number;
  name: string;
  centerGcjLat: number;
  centerGcjLng: number;
  radiusMeters: number;
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

// ---------------- 运营首页 ----------------

export function fetchDashboardOverview() {
  return request<DashboardOverview>({ url: '/dashboard/overview' });
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

// ---------------- 远程控制（下行指令代理） ----------------

export type CommandName =
  | 'text'
  | 'ptz'
  | 'ptz-adjust'
  | 'vehicle-control'
  | 'photo'
  | 'callback'
  | 'track-follow';

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
