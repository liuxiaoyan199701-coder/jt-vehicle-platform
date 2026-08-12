import assert from 'node:assert/strict';
import test from 'node:test';
import { createSingleFlight } from './single-flight';

test('concurrent callers share one execution and the next settled call starts another', async () => {
  let executions = 0;
  let release: (() => void) | undefined;
  const gate = new Promise<void>(resolve => {
    release = resolve;
  });
  const run = createSingleFlight(async () => {
    executions += 1;
    await gate;
  });

  const first = run();
  const second = run();
  const third = run();
  assert.equal(first, second);
  assert.equal(second, third);
  await Promise.resolve();
  assert.equal(executions, 1);

  release?.();
  await Promise.all([first, second, third]);
  await run();
  assert.equal(executions, 2);
});
