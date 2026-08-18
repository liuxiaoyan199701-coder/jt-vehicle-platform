/**
 * 把图表描述变成渲染配置。**这是唯一允许做这件事的地方。**
 *
 * 服务端只下发受约束的中间表示（图形、分类、系列、单位），绝不下发原始渲染配置——那不是
 * 「模型不该管渲染」的洁癖，是三个具体的注入面：提示框的 formatter 在默认模式下返回值直接进
 * innerHTML；标记点符号支持 `image://<url>` 可向任意外部地址发请求（数据外传信道）；graphic
 * 组件可塞任意元素。本项目的 markdown 都要过 DOMPurify，直通渲染配置是同一个洞开得更大。
 */

export interface AiChartSeries {
  name: string;
  type?: string | null;
  unit?: string | null;
  data: (number | null)[];
}

export interface AiChartSpec {
  chartType: string;
  source: string;
  categories: string[];
  series: AiChartSeries[];
  stacked?: boolean;
}

/** 从视图事件的参数里取出图表描述。形状不对就返回空，由调用方显示提示而不是渲染半张图。 */
export function toChartSpec(params: Record<string, unknown>): AiChartSpec | null {
  const chartType = params.chartType;
  const categories = params.categories;
  const series = params.series;
  if (typeof chartType !== 'string' || !Array.isArray(categories) || !Array.isArray(series)) {
    return null;
  }
  return {
    chartType,
    source: typeof params.source === 'string' ? params.source : '',
    categories: categories.map(String),
    series: series as AiChartSeries[],
    stacked: params.stacked === true
  };
}

/**
 * 按单位分配纵轴。
 *
 * 模型只写单位、不写轴索引——轴的分配是渲染细节。最多两条：三条以上的轴会把绘图区挤没，
 * 而「里程 + 告警数」这种双单位组合已经覆盖了绝大多数真实需求。
 */
function assignAxes(series: AiChartSeries[]) {
  const units: string[] = [];
  for (const item of series) {
    const unit = item.unit ?? '';
    if (!units.includes(unit) && units.length < 2) units.push(unit);
  }
  return units;
}

export function buildChartOption(spec: AiChartSpec, containerWidth: number) {
  if (spec.chartType === 'pie') {
    const only = spec.series[0];
    return {
      // richText 把 HTML 通道整个关掉：提示框在 canvas 内渲染，天然没有 innerHTML。
      // 不押在第三方库默认转义上——那不在我们的测试覆盖里，升级时没人会重新验证。
      tooltip: { trigger: 'item', renderMode: 'richText' },
      legend: { bottom: 0, type: 'scroll' },
      series: [
        {
          type: 'pie',
          radius: ['38%', '62%'],
          center: ['50%', '44%'],
          label: { show: false },
          data: spec.categories.map((name, index) => ({
            name,
            value: only?.data?.[index] ?? 0
          }))
        }
      ]
    };
  }

  const units = assignAxes(spec.series);
  // 分类多而容器窄时旋转标签，否则日期会叠成一团黑。这是渲染决策，不进描述格式。
  const rotate = spec.categories.length > 8 && containerWidth < 480 ? 45 : 0;

  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0, type: 'scroll' },
    grid: { left: 8, right: 8, top: 16, bottom: rotate ? 56 : 40, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: spec.chartType === 'bar',
      data: spec.categories,
      axisLabel: { rotate, hideOverlap: true }
    },
    yAxis: units.map(unit => ({
      type: 'value',
      name: unit || '',
      nameTextStyle: { fontSize: 11 },
      splitLine: { lineStyle: { type: 'dashed' } }
    })),
    series: spec.series.map(item => ({
      name: item.name,
      type: item.type || spec.chartType,
      yAxisIndex: Math.max(0, units.indexOf(item.unit ?? '')),
      stack: spec.stacked ? 'total' : undefined,
      showSymbol: item.data.length <= 40,
      // 空值原样交给渲染库——它天然会断线。**绝不填 0**：那天没上报是断线，
      // 不是「跑了 0 公里」，填 0 会造出假象且没人会怀疑到是这里干的。
      connectNulls: false,
      data: item.data
    }))
  };
}
