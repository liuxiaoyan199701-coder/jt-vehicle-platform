/**
 * 通知在界面上的呈现规则。
 *
 * 抽成纯函数是为了能直接测：这些规则（哪条算「刚刚」、徽标上限、显示哪几个数据）
 * 单独看都很小，但错了会天天出现在每个人的顶栏上。
 */

/** 严重度决定左侧色带。用颜色而不是文字标签——一列色带能被眼睛一次扫完，一列标签不能。 */
export const NOTICE_SEVERITY_CLASS: Record<string, string> = {
  CRITICAL: 'sev-critical',
  WARN: 'sev-warn',
  INFO: 'sev-info'
};

/** 与首页要点同一套图标：两处展示的是同一件事，换了图标只会让人以为是两回事。 */
export const NOTICE_CATEGORY_ICON: Record<string, string> = {
  OFFLINE: 'mdi:wifi-off',
  ALARM: 'mdi:alert-outline',
  MILEAGE: 'mdi:speedometer-slow',
  CAMERA: 'mdi:camera-outline',
  FLEET: 'mdi:truck-outline',
  DRIVER: 'mdi:card-account-details-outline',
  CONNECTION: 'mdi:lan-disconnect'
};

export function noticeSeverityClass(severity: string) {
  return NOTICE_SEVERITY_CLASS[severity] ?? 'sev-info';
}

export function noticeCategoryIcon(category: string) {
  return NOTICE_CATEGORY_ICON[category] ?? 'mdi:information-outline';
}

/** 只显示前三项数据：一条通知里塞满键值对，会把那句话本身淹没。 */
export function briefNoticeFacts(facts: Record<string, unknown> | null | undefined) {
  return Object.entries(facts ?? {})
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .slice(0, 3);
}

/**
 * 徽标上的数字。
 *
 * 超过 99 显示 99+：三位数在顶栏里既挤又没有信息量——「到底是 137 条还是 138 条」
 * 不会改变任何人的下一步动作。
 */
export const NOTICE_BADGE_MAX = 99;

/**
 * 「多久以前」。
 *
 * 近处用相对时间（一眼判断新旧），远处用绝对时间（相对时间到了「3 天前」就不再有用）。
 * 解析不出来时返回原值而不是「刚刚」——一个认不出的时间戳是数据问题，
 * 把它说成刚刚发生只会让人去查一件其实很久以前的事。
 */
export function noticeTimeLabel(value: string | null | undefined, now: number = Date.now()) {
  if (!value) return '';
  const at = Date.parse(value);
  if (Number.isNaN(at)) return value;

  const elapsedMinutes = Math.floor((now - at) / 60000);
  if (elapsedMinutes < 0) return value.replace('T', ' ').slice(5, 16);
  if (elapsedMinutes < 1) return '刚刚';
  if (elapsedMinutes < 60) return `${elapsedMinutes} 分钟前`;

  const elapsedHours = Math.floor(elapsedMinutes / 60);
  if (elapsedHours < 24) return `${elapsedHours} 小时前`;

  return value.replace('T', ' ').slice(5, 16);
}
