import assert from 'node:assert/strict';
import test from 'node:test';
import { formatActionParam } from './action-params';

test('geofence vertices are readable before the user confirms, in both the right and the wrong shape', () => {
  // 平台要的格式：每个顶点一对坐标
  assert.equal(
    formatActionParam([
      [22.643463, 114.030807],
      [22.634454, 114.021051]
    ]),
    '(22.643463, 114.030807)、(22.634454, 114.021051)'
  );

  // 助手写错时用的对象格式：也必须看得见内容，否则错误就藏在 [object Object] 里
  assert.equal(
    formatActionParam([
      { lat: 22.643463, lng: 114.030807 },
      { lat: 22.634454, lng: 114.021051 }
    ]),
    'lat=22.643463 lng=114.030807、lat=22.634454 lng=114.021051'
  );
});

test('empty and scalar values keep their existing presentation', () => {
  assert.equal(formatActionParam(null), '—');
  assert.equal(formatActionParam(undefined), '—');
  assert.equal(formatActionParam(''), '—');
  assert.equal(formatActionParam([]), '—');
  assert.equal(formatActionParam(true), '是');
  assert.equal(formatActionParam(false), '否');
  assert.equal(formatActionParam(1000), '1000');
  assert.equal(formatActionParam(['京A12345', '湘A875435']), '京A12345、湘A875435');
});
