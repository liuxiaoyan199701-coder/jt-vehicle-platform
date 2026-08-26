import assert from 'node:assert/strict';
import test from 'node:test';
import {
  briefNoticeFacts,
  noticeCategoryIcon,
  noticeSeverityClass,
  noticeTimeLabel
} from './notice-display';

const NOW = Date.parse('2026-08-25T21:00:00.000+08:00');

test('recent notices read as relative time and older ones as a date', () => {
  assert.equal(noticeTimeLabel('2026-08-25T20:59:40.000+08:00', NOW), '刚刚');
  assert.equal(noticeTimeLabel('2026-08-25T20:42:00.000+08:00', NOW), '18 分钟前');
  assert.equal(noticeTimeLabel('2026-08-25T14:00:00.000+08:00', NOW), '7 小时前');
  assert.equal(noticeTimeLabel('2026-08-20T09:30:00.000+08:00', NOW), '08-20 09:30');
});

test('an unparseable timestamp is shown as-is instead of being called just now', () => {
  assert.equal(noticeTimeLabel('not-a-timestamp', NOW), 'not-a-timestamp');
  assert.equal(noticeTimeLabel(null, NOW), '');
  assert.equal(noticeTimeLabel(undefined, NOW), '');
});

test('a clock skewed into the future never renders as a negative age', () => {
  assert.equal(noticeTimeLabel('2026-08-25T21:05:00.000+08:00', NOW), '08-25 21:05');
});

test('only the first three usable facts are shown so the sentence is not drowned', () => {
  const facts = {
    车牌: '京A12345',
    设备号: '138000000000',
    '离线时长(小时)': 26,
    最后上报: '2026-08-24T19:00:00.000+08:00'
  };

  assert.deepEqual(briefNoticeFacts(facts), [
    ['车牌', '京A12345'],
    ['设备号', '138000000000'],
    ['离线时长(小时)', 26]
  ]);
});

test('empty and missing fact values are dropped rather than rendered blank', () => {
  assert.deepEqual(briefNoticeFacts({ 车牌: '', 台数: 0, 备注: null, 来源: undefined }), [
    ['台数', 0]
  ]);
  assert.deepEqual(briefNoticeFacts(null), []);
  assert.deepEqual(briefNoticeFacts(undefined), []);
});

test('unknown categories and severities still render something instead of nothing', () => {
  assert.equal(noticeSeverityClass('CRITICAL'), 'sev-critical');
  assert.equal(noticeSeverityClass('WHAT_IS_THIS'), 'sev-info');
  assert.equal(noticeCategoryIcon('OFFLINE'), 'mdi:wifi-off');
  assert.equal(noticeCategoryIcon('BRAND_NEW_CATEGORY'), 'mdi:information-outline');
});
