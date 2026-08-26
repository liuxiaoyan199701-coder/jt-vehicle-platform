import { escapeHtmlText } from '@/utils/html';
import type { DeviceRow } from '@/utils/device-store';
import type { DeviceStore } from '@/utils/device-store';

/**
 * 车辆标记图层：标记的创建、更新与回收收口在这里，不再挂在列表刷新循环上。
 *
 * 两条与规模有关的取舍：
 *
 * 1. **只为视野内的设备建标记**。一万台设备就是一万个带 innerHTML 的 DOM 标记，
 *    而屏幕上真正看得见的通常只有几十上百个。视野外的设备照常在仓库里，
 *    只是不占标记；地图一移动就按新视野增删。
 * 2. **纯坐标变化只挪位置**。`setContent` 会重建标记的 HTML，
 *    而车辆位置每几秒就变一次、外观却几乎不变，
 *    只有在线态、方向或车牌真的变了才值得重建。
 */

/** 标记数量上限。触顶说明视野内设备过密，此时应提示放大而不是继续堆 DOM。 */
const DEFAULT_MAX_MARKERS = 500;

/** 视野四周各外扩半屏做缓冲，避免小幅拖动就频繁增删标记。 */
const VIEWPORT_BUFFER_RATIO = 0.5;

export interface VehicleMarkerLayerOptions {
  map: any;
  AMap: any;
  onSelect: (deviceId: string) => void;
  /** 视野内设备超过上限时回报，供页面提示用户放大 */
  onCappedChange?: (capped: boolean) => void;
  maxMarkers?: number;
}

interface MarkerEntry {
  marker: any;
  /** 影响外观的字段指纹，变了才重建 HTML */
  signature: string;
  lng: number;
  lat: number;
}

function markerSignature(row: DeviceRow) {
  return `${row.online ? '1' : '0'}|${normalizeDirection(row.direction)}|${row.label}`;
}

function normalizeDirection(direction: number | null) {
  const raw = Number(direction ?? 0);
  if (!Number.isFinite(raw)) return 0;
  return ((raw % 360) + 360) % 360;
}

function markerContent(row: DeviceRow) {
  const color = row.online ? '#18a058' : '#909399';
  const label = escapeHtmlText(row.label);
  const direction = normalizeDirection(row.direction);
  return `
    <div style="display:flex;flex-direction:column;align-items:center;">
      <div style="width:0;height:0;border-left:9px solid transparent;border-right:9px solid transparent;
                  border-bottom:20px solid ${color};filter:drop-shadow(0 1px 2px rgba(0,0,0,.4));
                  transform:rotate(${direction}deg);transform-origin:center;"></div>
      <div style="margin-top:2px;padding:1px 6px;background:${color};color:#fff;border-radius:3px;
                  font-size:12px;white-space:nowrap;">${label}</div>
    </div>`;
}

export interface VehicleMarkerLayer {
  /** 按当前视野同步标记。每次视图发布、以及地图移动缩放后调用。 */
  sync: (store: DeviceStore) => void;
  /** 把视野收到能看见全部已定位设备。不依赖标记是否已创建。 */
  fitAll: (store: DeviceStore) => void;
  destroy: () => void;
}

export function createVehicleMarkerLayer(options: VehicleMarkerLayerOptions): VehicleMarkerLayer {
  const { map, AMap, onSelect } = options;
  const maxMarkers = options.maxMarkers ?? DEFAULT_MAX_MARKERS;
  const entries = new Map<string, MarkerEntry>();
  const touched = new Set<string>();
  let capped = false;
  let destroyed = false;

  function reportCapped(next: boolean) {
    if (next === capped) return;
    capped = next;
    options.onCappedChange?.(next);
  }

  /** 当前视野加缓冲。地图还没出图时返回 null，此时不做裁剪。 */
  function bufferedBounds() {
    const bounds = map?.getBounds?.();
    if (!bounds) return null;

    const southWest = bounds.getSouthWest?.();
    const northEast = bounds.getNorthEast?.();
    if (!southWest || !northEast) return null;

    const lngPadding = (northEast.lng - southWest.lng) * VIEWPORT_BUFFER_RATIO;
    const latPadding = (northEast.lat - southWest.lat) * VIEWPORT_BUFFER_RATIO;
    return {
      minLng: southWest.lng - lngPadding,
      maxLng: northEast.lng + lngPadding,
      minLat: southWest.lat - latPadding,
      maxLat: northEast.lat + latPadding
    };
  }

  function upsert(row: DeviceRow, lng: number, lat: number) {
    const existing = entries.get(row.deviceId);
    const signature = markerSignature(row);

    if (existing) {
      if (existing.lng !== lng || existing.lat !== lat) {
        existing.marker.setPosition([lng, lat]);
        existing.lng = lng;
        existing.lat = lat;
      }
      // 外观没变就不重建 HTML——位置每几秒一变，外观几乎不变
      if (existing.signature !== signature) {
        existing.marker.setContent(markerContent(row));
        existing.signature = signature;
      }
      return true;
    }

    if (entries.size >= maxMarkers) {
      return false;
    }

    const marker = new AMap.Marker({
      position: [lng, lat],
      content: markerContent(row),
      offset: new AMap.Pixel(-18, -18),
      title: row.label
    });
    marker.on('click', () => onSelect(row.deviceId));
    map.add(marker);
    entries.set(row.deviceId, { marker, signature, lng, lat });
    return true;
  }

  function sync(store: DeviceStore) {
    if (destroyed || !map) return;

    const bounds = bufferedBounds();
    touched.clear();
    let overflowed = false;

    store.forEach(row => {
      const lng = row.gcjLng;
      const lat = row.gcjLat;
      if (lng == null || lat == null) return;
      if (bounds && (lng < bounds.minLng || lng > bounds.maxLng || lat < bounds.minLat || lat > bounds.maxLat)) {
        return;
      }

      if (upsert(row, lng, lat)) {
        touched.add(row.deviceId);
      } else {
        overflowed = true;
      }
    });

    // 移出视野或已不存在的标记就地回收
    entries.forEach((entry, deviceId) => {
      if (touched.has(deviceId)) return;
      map.remove(entry.marker);
      entries.delete(deviceId);
    });

    reportCapped(overflowed);
  }

  function fitAll(store: DeviceStore) {
    if (destroyed || !map) return;

    let minLng = Number.POSITIVE_INFINITY;
    let maxLng = Number.NEGATIVE_INFINITY;
    let minLat = Number.POSITIVE_INFINITY;
    let maxLat = Number.NEGATIVE_INFINITY;
    let count = 0;

    store.forEach(row => {
      if (row.gcjLng == null || row.gcjLat == null) return;
      count += 1;
      minLng = Math.min(minLng, row.gcjLng);
      maxLng = Math.max(maxLng, row.gcjLng);
      minLat = Math.min(minLat, row.gcjLat);
      maxLat = Math.max(maxLat, row.gcjLat);
    });

    if (count === 0) return;

    // 视野从坐标直接算，不经由标记——裁剪之后标记本来就不齐全
    if (count === 1) {
      map.setZoomAndCenter(14, [minLng, minLat]);
      return;
    }

    map.setBounds(new AMap.Bounds([minLng, minLat], [maxLng, maxLat]), false, [60, 60, 60, 60]);
  }

  function destroy() {
    destroyed = true;
    entries.forEach(entry => map?.remove(entry.marker));
    entries.clear();
    touched.clear();
  }

  return { sync, fitAll, destroy };
}
