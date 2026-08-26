/**
 * 把「数据变了」与「界面重画」解耦的节流发布器。
 *
 * 实时位置是 1~30 秒一报，但成千上万台设备叠加起来每秒能来上千条。
 * 逐条重画既没必要也扛不住，这里把变化攒进一个固定窗口合并成一次发布。
 *
 * 为什么不用纯 requestAnimationFrame：那是 60 次/秒，而列表里一行车的
 * 速度和时间根本不需要按帧刷新，白白付出 60 倍的代价。
 * 为什么攒够窗口后仍要对齐一帧：setTimeout 的回调可能落在一帧的任意位置，
 * 在合成阶段改 DOM 会多触发一次布局。
 *
 * 定时器、帧调度与可见性都从外部注入，好让这套时序逻辑能脱离浏览器被测试。
 */

export interface PublishSchedulerDeps {
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
  scheduleFrame: (fn: () => void) => void;
  isHidden: () => boolean;
}

export interface PublishScheduler {
  /** 登记一次变化。窗口内的多次登记只会换来一次发布。 */
  request: () => void;
  /** 立即发布积压的变化（页面重新可见、或用户输入需要即时反馈时）。 */
  flush: () => void;
  dispose: () => void;
}

export function createPublishScheduler(
  publish: () => void,
  windowMs: number,
  deps: PublishSchedulerDeps
): PublishScheduler {
  let timer: unknown = null;
  let pending = false;
  let disposed = false;

  function onWindowElapsed() {
    timer = null;
    if (disposed || !pending) return;
    // 页面不可见：保留 pending，等回到前台由 flush 补发
    if (deps.isHidden()) return;

    deps.scheduleFrame(() => {
      if (disposed || !pending || deps.isHidden()) return;
      pending = false;
      publish();
    });
  }

  return {
    request() {
      if (disposed) return;
      pending = true;
      // 不可见时不占定时器，回到前台一次补齐
      if (timer !== null || deps.isHidden()) return;
      timer = deps.setTimer(onWindowElapsed, windowMs);
    },

    flush() {
      if (disposed || !pending || deps.isHidden()) return;
      if (timer !== null) {
        deps.clearTimer(timer);
        timer = null;
      }
      pending = false;
      publish();
    },

    dispose() {
      disposed = true;
      pending = false;
      if (timer !== null) {
        deps.clearTimer(timer);
        timer = null;
      }
    }
  };
}
