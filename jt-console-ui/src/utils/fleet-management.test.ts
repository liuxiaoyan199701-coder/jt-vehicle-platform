import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildFleetAssignmentMap,
  filterFleets,
  fleetMemberLinks,
  membershipChangeSummary
} from './fleet-management';

test('fleet filtering covers code, name, manager and contact', () => {
  const fleets = [
    { code: 'NORTH-01', name: '北区配送', manager: '张三', contact: '13800000001' },
    { code: 'SOUTH-02', name: '南区运输', manager: '李四', contact: '13800000002' }
  ];

  assert.deepEqual(filterFleets(fleets, ' south '), [fleets[1]]);
  assert.deepEqual(filterFleets(fleets, '张三'), [fleets[0]]);
  assert.deepEqual(filterFleets(fleets, '0002'), [fleets[1]]);
  assert.deepEqual(filterFleets(fleets, 'missing'), []);
});

test('assignment lookup preserves exact canonical device IDs', () => {
  const assignments = buildFleetAssignmentMap([
    {
      id: 1,
      code: 'F-001',
      name: '第一车队',
      members: [{ deviceId: '00123' }]
    },
    {
      id: 2,
      code: 'F-002',
      name: '第二车队',
      members: [{ deviceId: '123' }]
    }
  ]);

  assert.equal(assignments.get('00123')?.fleetId, 1);
  assert.equal(assignments.get('123')?.fleetId, 2);
  assert.equal(assignments.size, 2);
});

test('membership summary separates additions, removals and cross-fleet transfers', () => {
  const assignments = buildFleetAssignmentMap([
    {
      id: 2,
      code: 'F-002',
      name: '第二车队',
      members: [{ deviceId: 'other-fleet' }]
    }
  ]);

  assert.deepEqual(
    membershipChangeSummary(['kept', 'removed'], ['kept', 'new', 'other-fleet'], assignments, 1),
    {
      added: ['new', 'other-fleet'],
      removed: ['removed'],
      transferred: ['other-fleet']
    }
  );
});

test('member links carry the unmodified device ID to every target page', () => {
  const links = fleetMemberLinks('00123/ABC');

  assert.deepEqual(links.vehicle, { name: 'vehicle', query: { device: '00123/ABC' } });
  assert.deepEqual(links.monitor, { name: 'monitor', query: { device: '00123/ABC' } });
  assert.deepEqual(links.track, { name: 'track', query: { device: '00123/ABC' } });
  assert.deepEqual(links.alarm, { name: 'alarm', query: { device: '00123/ABC' } });
});
