import assert from 'node:assert/strict';
import test from 'node:test';
import { createPublishScheduler } from './publish-scheduler';

/** 手动推进的假时钟：定时器与帧调度都攒着，由测试决定何时跑。 */
function createHarness(hidden = false) {
  let timers: { id: number; fn: () => void }[] = [];
  let frames: (() => void)[] = [];
  let nextId = 1;
  let isHidden = hidden;
  let published = 0;

  const scheduler = createPublishScheduler(
    () => {
      published += 1;
    },
    200,
    {
      setTimer(fn) {
        const id = nextId;
        nextId += 1;
        timers.push({ id, fn });
        return id;
      },
      clearTimer(handle) {
        timers = timers.filter(timer => timer.id !== handle);
      },
      scheduleFrame(fn) {
        frames.push(fn);
      },
      isHidden: () => isHidden
    }
  );

  return {
    scheduler,
    get published() {
      return published;
    },
    get armedTimers() {
      return timers.length;
    },
    hide() {
      isHidden = true;
    },
    show() {
      isHidden = false;
    },
    /** 让窗口到期并跑完随之排上的那一帧 */
    tick() {
      const due = timers;
      timers = [];
      due.forEach(timer => timer.fn());
      const pendingFrames = frames;
      frames = [];
      pendingFrames.forEach(frame => frame());
    }
  };
}

test('many requests inside one window collapse into a single publish', () => {
  const harness = createHarness();

  harness.scheduler.request();
  harness.scheduler.request();
  harness.scheduler.request();
  assert.equal(harness.published, 0, '窗口未到期前不应发布');
  assert.equal(harness.armedTimers, 1, '窗口内只占一个定时器');

  harness.tick();
  assert.equal(harness.published, 1);
});

test('a quiet window publishes nothing', () => {
  const harness = createHarness();

  harness.tick();

  assert.equal(harness.published, 0);
});

test('a hidden page publishes nothing and catches up once it is shown again', () => {
  const harness = createHarness(true);

  harness.scheduler.request();
  harness.scheduler.request();
  harness.tick();
  assert.equal(harness.published, 0, '后台标签页不应刷新');
  assert.equal(harness.armedTimers, 0, '不可见时不占定时器');

  harness.show();
  harness.scheduler.flush();
  assert.equal(harness.published, 1, '回到前台应补发一次');

  // 补发之后没有新变化，再 flush 不应重复发布
  harness.scheduler.flush();
  assert.equal(harness.published, 1);
});

test('going hidden mid-window holds the publish back until the page returns', () => {
  const harness = createHarness();

  harness.scheduler.request();
  harness.hide();
  harness.tick();
  assert.equal(harness.published, 0);

  harness.show();
  harness.scheduler.flush();
  assert.equal(harness.published, 1);
});

test('flush publishes immediately and cancels the pending window', () => {
  const harness = createHarness();

  harness.scheduler.request();
  harness.scheduler.flush();
  assert.equal(harness.published, 1);
  assert.equal(harness.armedTimers, 0, 'flush 应撤掉在途的窗口');

  harness.tick();
  assert.equal(harness.published, 1, '窗口到期不应再发一次');
});

test('flush without a pending change publishes nothing', () => {
  const harness = createHarness();

  harness.scheduler.flush();

  assert.equal(harness.published, 0);
});

test('a disposed scheduler goes quiet', () => {
  const harness = createHarness();

  harness.scheduler.request();
  harness.scheduler.dispose();
  harness.tick();
  harness.scheduler.request();
  harness.scheduler.flush();

  assert.equal(harness.published, 0);
  assert.equal(harness.armedTimers, 0);
});

test('a new window opens after the previous one published', () => {
  const harness = createHarness();

  harness.scheduler.request();
  harness.tick();
  assert.equal(harness.published, 1);

  harness.scheduler.request();
  harness.tick();
  assert.equal(harness.published, 2);
});
