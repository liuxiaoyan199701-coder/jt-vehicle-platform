export interface FleetSearchItem {
  code: string;
  name: string;
  manager?: string | null;
  contact?: string | null;
}

export interface FleetAssignmentSource {
  id: number;
  code: string;
  name: string;
  members: readonly { deviceId: string }[];
}

export interface FleetAssignment {
  fleetId: number;
  fleetCode: string;
  fleetName: string;
}

export interface FleetMemberLink {
  name: 'vehicle' | 'monitor' | 'track' | 'alarm';
  query: { device: string };
}

export function filterFleets<T extends FleetSearchItem>(fleets: readonly T[], keyword: string): T[] {
  const word = keyword.trim().toLocaleLowerCase();
  if (!word) return [...fleets];

  return fleets.filter(fleet =>
    [fleet.code, fleet.name, fleet.manager, fleet.contact]
      .filter((value): value is string => typeof value === 'string')
      .some(value => value.toLocaleLowerCase().includes(word))
  );
}

/** Build a current-assignment lookup without normalizing canonical device IDs. */
export function buildFleetAssignmentMap(fleets: readonly FleetAssignmentSource[]): Map<string, FleetAssignment> {
  const assignments = new Map<string, FleetAssignment>();

  for (const fleet of fleets) {
    for (const member of fleet.members) {
      assignments.set(member.deviceId, {
        fleetId: fleet.id,
        fleetCode: fleet.code,
        fleetName: fleet.name
      });
    }
  }

  return assignments;
}

export function membershipChangeSummary(
  currentDeviceIds: readonly string[],
  nextDeviceIds: readonly string[],
  assignments: ReadonlyMap<string, FleetAssignment>,
  targetFleetId: number
) {
  const current = new Set(currentDeviceIds);
  const next = new Set(nextDeviceIds);

  return {
    added: [...next].filter(deviceId => !current.has(deviceId)),
    removed: [...current].filter(deviceId => !next.has(deviceId)),
    transferred: [...next].filter(deviceId => {
      const assignment = assignments.get(deviceId);
      return assignment !== undefined && assignment.fleetId !== targetFleetId;
    })
  };
}

export function fleetMemberLinks(deviceId: string): Record<FleetMemberLink['name'], FleetMemberLink> {
  return {
    vehicle: { name: 'vehicle', query: { device: deviceId } },
    monitor: { name: 'monitor', query: { device: deviceId } },
    track: { name: 'track', query: { device: deviceId } },
    alarm: { name: 'alarm', query: { device: deviceId } }
  };
}
