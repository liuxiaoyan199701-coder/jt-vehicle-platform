/**
 * AI 动作确认卡片上的参数取值展示。
 *
 * 这张卡片是执行前的最后一道人工闸门，所以嵌套结构必须逐层展开：`String(对象)` 会得到
 * `[object Object]`，把参数错误恰好藏在最该被看见的地方——助手把围栏顶点写成
 * `{lat,lng}` 对象那次，卡片上就是一排 `[object Object]`，只能点下去才知道会失败。
 */
export function formatActionParam(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (Array.isArray(value)) {
    if (!value.length) return '—';
    // 坐标对（[lat, lng]）写成 (lat, lng)，一眼能对出是哪个点
    if (isCoordinate(value)) return `(${value[0]}, ${value[1]})`;
    return value.map(formatActionParam).join('、');
  }
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, nested]) => `${key}=${formatActionParam(nested)}`)
      .join(' ');
  }
  return String(value);
}

function isCoordinate(value: unknown[]): boolean {
  return value.length === 2 && value.every(item => typeof item === 'number');
}
