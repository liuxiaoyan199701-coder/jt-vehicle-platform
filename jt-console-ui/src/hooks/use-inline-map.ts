import { nextTick, onScopeDispose, shallowRef, watch } from 'vue';
import { useElementSize } from '@vueuse/core';
import { useAMap } from './use-amap';
import type { UseAMapOptions } from './use-amap';

/**
 * 对话内嵌的小尺寸地图。
 *
 * 在 `useAMap` 之上补两件全页地图不需要、而小容器必须有的事：
 *
 * 1. **关掉控件、收紧留白**。工具条固定在右下角，在 200px 高的容器里会盖住相当一部分画面；
 *    60px 的默认留白上下就吃掉 120px，车永远是正中间一个点，看不出周围环境。
 * 2. **跟随容器尺寸重算**。整个代码库此前没有任何一处调用过地图的 resize——全页地图不需要，
 *    因为容器尺寸只随窗口变，而地图自己会听窗口事件。内嵌地图不一样：气泡会因为文字流式追加
 *    而变宽、面板会从关到开，容器变了而地图不知道，表现就是一块灰白。这里照抄图表 hook 的
 *    `useElementSize` + watch 范式。
 *
 * 不把这两件事塞回 `useAMap`：那会给实时监控、轨迹回放、围栏三个全页调用方加上它们并不需要的
 * 尺寸监听。
 */
export function useInlineMap(options: UseAMapOptions = {}) {
  const containerRef = shallowRef<HTMLElement | null>(null);
  const { width, height } = useElementSize(containerRef, { width: 0, height: 0 });

  const amap = useAMap({ controls: false, fitPadding: 16, ...options });

  let initialized = false;

  /**
   * 挂载后初始化。
   *
   * 必须等到容器真的有尺寸再建地图：气泡是流式渲染出来的，过早初始化会拿到 0×0 的容器，
   * 地图会以为自己只有一个像素宽。
   */
  async function mount() {
    if (initialized || !containerRef.value) return;
    initialized = true;
    await nextTick();
    await amap.init(containerRef.value);
  }

  // 容器尺寸变化时让地图重算。地图未就绪时什么都不用做——init 本身会按当时的尺寸来。
  watch([width, height], ([nextWidth, nextHeight]) => {
    if (!amap.ready.value || !amap.map.value) return;
    if (nextWidth === 0 || nextHeight === 0) return;
    amap.map.value.resize();
  });

  onScopeDispose(() => {
    amap.destroy();
  });

  return { containerRef, mount, ...amap };
}
