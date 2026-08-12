export interface TrackTarget {
  deviceId: string;
  plateNo: string | null;
}

/** Combine archived vehicles and observed devices without normalizing canonical IDs. */
export function mergeTrackTargets(...sources: TrackTarget[][]): TrackTarget[] {
  const targets = new Map<string, TrackTarget>();

  sources.flat().forEach(source => {
    const deviceId = source.deviceId.trim();
    if (!deviceId) return;

    const current = targets.get(deviceId);
    if (!current) {
      targets.set(deviceId, { deviceId, plateNo: source.plateNo });
    } else if (!current.plateNo && source.plateNo) {
      current.plateNo = source.plateNo;
    }
  });

  return [...targets.values()];
}
