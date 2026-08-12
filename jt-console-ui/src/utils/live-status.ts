export interface TimestampedLiveStatus {
  deviceId: string;
  lastSeenAt: string | null;
  plateNo?: string | null;
}

export function isLiveStatusNewer(left: string | null, right: string | null) {
  const leftTime = left ? Date.parse(left) : Number.NEGATIVE_INFINITY;
  const rightTime = right ? Date.parse(right) : Number.NEGATIVE_INFINITY;
  if (Number.isNaN(leftTime)) return false;
  if (Number.isNaN(rightTime)) return true;
  return leftTime > rightTime;
}

/** Merge a possibly stale full snapshot without overwriting newer socket increments. */
export function reconcileLiveStatusSnapshot<T extends TimestampedLiveStatus>(current: T[], snapshot: T[]): T[] {
  const currentById = new Map(current.map(vehicle => [vehicle.deviceId, vehicle]));
  const reconciled = snapshot.map(remote => {
    const local = currentById.get(remote.deviceId);
    currentById.delete(remote.deviceId);
    if (!local || !isLiveStatusNewer(local.lastSeenAt, remote.lastSeenAt)) {
      return remote;
    }
    return {
      ...remote,
      ...local,
      plateNo: local.plateNo ?? remote.plateNo
    } as T;
  });

  currentById.forEach(local => reconciled.push(local));
  return reconciled;
}
