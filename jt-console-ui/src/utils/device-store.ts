import type { LiveStatus } from '@/service/api';
import type { LiveLocationUpdate } from '@/hooks/use-live-socket';
import { isLiveStatusNewer } from './live-status';

/**
 * 监控页的设备仓库：承载全部设备、维护排序索引与关键词过滤。
 *
 * 刻意不使用 Vue 的响应式 API——万台设备下，把设备集合本身做成响应式源意味着
 * 「写一台设备」等价于「让整个列表失效」，一条位置上报就要重新 diff 全部行。
 * 这里只做纯数据运算，由调用方决定何时把 `visibleRows()` 的结果推给视图。
 *
 * 行对象不可变：更新一台设备是「换一个新对象」，没变的设备保持原引用不变，
 * 于是渲染层能靠一次浅比较就跳过绝大多数行。
 */

export interface DeviceRow {
  deviceId: string;
  plateNo: string | null;
  /** 展示名：有车牌用车牌，没有就退回终端号 */
  label: string;
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
  /** 入库时解析一次，渲染层不再碰 JSON */
  alarms: string[];
  activeAlarmCount: number;
  /**
   * `alarms` 的来源串，仅用于判断下一次快照的告警是否真的变了。
   * 增量推送直接给数组，没有来源串，置 null。
   */
  alarmSource: string | null;
  /** 预先拼好并小写的检索串，过滤时只做一次 includes */
  search: string;
}

export interface StoreChange {
  /** 有任何字段变化，视图需要重新投影 */
  changed: boolean;
  /** 成员增删或排序键（在线态、车牌）变化，索引需要重排 */
  resort: boolean;
}

export interface UpdateOutcome extends StoreChange {
  /** 这条增量来自仓库里没有的设备 */
  unknown: boolean;
}

const NO_CHANGE: UpdateOutcome = { changed: false, resort: false, unknown: false };

function searchKey(deviceId: string, plateNo: string | null) {
  return `${deviceId} ${plateNo ?? ''}`.toLowerCase();
}

function parseAlarms(alarmJson: string | null): string[] {
  if (!alarmJson) return [];
  try {
    const parsed = JSON.parse(alarmJson);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/**
 * 排序：在线优先 → 车牌升序 → 终端号兜底。
 *
 * 无车牌的设备排在有车牌之后：它们多半是尚未建档的终端，
 * 让一串没有可读名字的设备霸占列表顶部对使用者没有意义。
 * 兜底比较终端号是为了让「同为离线且都没车牌」的设备之间也有确定顺序，
 * 否则每次重排它们的相对位置都会跳。
 */
export function compareDeviceRows(left: DeviceRow, right: DeviceRow): number {
  if (left.online !== right.online) {
    return left.online ? -1 : 1;
  }

  const leftPlate = left.plateNo ?? '';
  const rightPlate = right.plateNo ?? '';
  if (leftPlate !== rightPlate) {
    if (!leftPlate) return 1;
    if (!rightPlate) return -1;
    return leftPlate < rightPlate ? -1 : 1;
  }

  if (left.deviceId === right.deviceId) return 0;
  return left.deviceId < right.deviceId ? -1 : 1;
}

/** 快照里的这台设备与本地行是否逐字段一致——一致就保留原对象引用。 */
function matchesSnapshot(local: DeviceRow, remote: LiveStatus): boolean {
  return (
    local.plateNo === (remote.plateNo ?? null) &&
    local.online === remote.online &&
    local.lastSeenAt === remote.lastSeenAt &&
    local.deviceTime === remote.deviceTime &&
    local.lat === remote.lat &&
    local.lng === remote.lng &&
    local.gcjLat === remote.gcjLat &&
    local.gcjLng === remote.gcjLng &&
    local.speedKph === remote.speedKph &&
    local.direction === remote.direction &&
    local.altitude === remote.altitude &&
    local.mileage === remote.mileage &&
    local.accOn === remote.accOn &&
    local.positioned === remote.positioned &&
    local.activeAlarmCount === (remote.activeAlarmCount ?? 0) &&
    local.alarmSource === remote.alarmJson
  );
}

function rowFromSnapshot(remote: LiveStatus, local: DeviceRow | undefined): DeviceRow {
  const plateNo = remote.plateNo ?? null;
  // 告警串没变就沿用已解析的数组，不重复 parse
  const alarms = local && local.alarmSource === remote.alarmJson ? local.alarms : parseAlarms(remote.alarmJson);
  return {
    deviceId: remote.deviceId,
    plateNo,
    label: plateNo ?? remote.deviceId,
    online: remote.online,
    lastSeenAt: remote.lastSeenAt,
    deviceTime: remote.deviceTime,
    lat: remote.lat,
    lng: remote.lng,
    gcjLat: remote.gcjLat,
    gcjLng: remote.gcjLng,
    speedKph: remote.speedKph,
    direction: remote.direction,
    altitude: remote.altitude,
    mileage: remote.mileage,
    accOn: remote.accOn,
    positioned: remote.positioned,
    alarms,
    activeAlarmCount: remote.activeAlarmCount ?? 0,
    alarmSource: remote.alarmJson,
    search: local && local.plateNo === plateNo ? local.search : searchKey(remote.deviceId, plateNo)
  };
}

export class DeviceStore {
  private readonly rows = new Map<string, DeviceRow>();

  /** 排好序的全部 deviceId，过滤前的权威顺序 */
  private order: string[] = [];

  /** 排序 + 过滤后的投影结果，视图直接拿去渲染 */
  private projection: DeviceRow[] = [];

  private keyword = '';

  private needsResort = false;

  private needsProject = false;

  private online = 0;

  get size() {
    return this.rows.size;
  }

  get onlineCount() {
    return this.online;
  }

  get(deviceId: string) {
    return this.rows.get(deviceId);
  }

  /** 遍历全部设备，不产生中间数组——地图图层每次同步都要走一遍。 */
  forEach(visit: (row: DeviceRow) => void) {
    this.rows.forEach(visit);
  }

  /** 返回关键词是否真的变了，没变就不必惊动视图。 */
  setKeyword(word: string): boolean {
    const normalized = word.trim().toLowerCase();
    if (normalized === this.keyword) return false;
    this.keyword = normalized;
    this.needsProject = true;
    return true;
  }

  /**
   * 合入一次全量快照。
   *
   * 快照可能比某台设备的最新增量还旧（请求在途期间设备又上报了），
   * 这时保留本地值，只补车牌这类快照才有、增量不带的字段。
   * 快照里没有的本地设备一律保留——它们通常是刚由增量新建、快照尚未追上的设备。
   */
  applySnapshot(snapshot: LiveStatus[]): StoreChange {
    let changed = false;
    let resort = false;

    for (const remote of snapshot) {
      const local = this.rows.get(remote.deviceId);

      if (!local) {
        this.put(rowFromSnapshot(remote, undefined), undefined);
        changed = true;
        resort = true;
        continue;
      }

      if (isLiveStatusNewer(local.lastSeenAt, remote.lastSeenAt)) {
        const plateNo = remote.plateNo ?? null;
        if (local.plateNo === null && plateNo !== null) {
          this.put({ ...local, plateNo, label: plateNo, search: searchKey(local.deviceId, plateNo) }, local);
          changed = true;
          resort = true;
        }
        continue;
      }

      if (matchesSnapshot(local, remote)) continue;

      const next = rowFromSnapshot(remote, local);
      this.put(next, local);
      changed = true;
      if (next.online !== local.online || next.plateNo !== local.plateNo) {
        resort = true;
      }
    }

    if (resort) this.needsResort = true;
    if (changed) this.needsProject = true;
    return { changed, resort };
  }

  /** 合入一条实时增量。O(1) 按 key 命中，不遍历其他设备。 */
  applyUpdate(update: LiveLocationUpdate): UpdateOutcome {
    const local = this.rows.get(update.deviceId);

    if (!local) {
      this.put(
        {
          deviceId: update.deviceId,
          plateNo: null,
          label: update.deviceId,
          online: update.online,
          lastSeenAt: update.receivedAt,
          deviceTime: update.deviceTime,
          lat: update.lat,
          lng: update.lng,
          gcjLat: update.gcjLat,
          gcjLng: update.gcjLng,
          speedKph: update.speedKph,
          direction: update.direction,
          altitude: update.altitude,
          mileage: update.mileage,
          accOn: update.accOn,
          positioned: true,
          alarms: update.alarms,
          activeAlarmCount: update.activeAlarmCount,
          alarmSource: null,
          search: searchKey(update.deviceId, null)
        },
        undefined
      );
      this.needsResort = true;
      this.needsProject = true;
      return { changed: true, resort: true, unknown: true };
    }

    // 本地已有更新的状态，说明这条增量迟到了
    if (isLiveStatusNewer(local.lastSeenAt, update.receivedAt)) {
      return NO_CHANGE;
    }

    const next: DeviceRow = {
      ...local,
      online: true,
      lastSeenAt: update.receivedAt,
      deviceTime: update.deviceTime,
      lat: update.lat,
      lng: update.lng,
      gcjLat: update.gcjLat,
      gcjLng: update.gcjLng,
      speedKph: update.speedKph,
      direction: update.direction,
      altitude: update.altitude,
      mileage: update.mileage,
      accOn: update.accOn,
      positioned: true,
      alarms: update.alarms,
      activeAlarmCount: update.activeAlarmCount,
      alarmSource: null
    };
    this.put(next, local);

    // 位置、速度、告警的变化不影响顺序，只有在线态翻转才需要重排
    const resort = next.online !== local.online;
    if (resort) this.needsResort = true;
    this.needsProject = true;
    return { changed: true, resort, unknown: false };
  }

  /**
   * 当前应当渲染的行。索引惰性重建：只重排一次、只投影一次，
   * 同一状态下反复调用不产生额外开销。
   */
  visibleRows(): DeviceRow[] {
    if (this.needsResort) {
      const sorted = [...this.rows.values()].sort(compareDeviceRows);
      this.order = sorted.map(row => row.deviceId);
      this.needsResort = false;
      this.needsProject = true;
    }

    if (this.needsProject) {
      const word = this.keyword;
      const next: DeviceRow[] = [];
      for (const deviceId of this.order) {
        const row = this.rows.get(deviceId);
        if (!row) continue;
        if (word && !row.search.includes(word)) continue;
        next.push(row);
      }
      this.projection = next;
      this.needsProject = false;
    }

    return this.projection;
  }

  private put(row: DeviceRow, previous: DeviceRow | undefined) {
    if (previous) {
      if (previous.online !== row.online) {
        this.online += row.online ? 1 : -1;
      }
    } else if (row.online) {
      this.online += 1;
    }
    this.rows.set(row.deviceId, row);
  }
}
