/** Share one in-flight execution and allow a new execution after it settles. */
export function createSingleFlight<T>(task: () => T | Promise<T>) {
  let inFlight: Promise<T> | null = null;

  return function run() {
    if (inFlight) {
      return inFlight;
    }

    const pending = Promise.resolve().then(task);
    const guarded = pending.finally(() => {
      if (inFlight === guarded) {
        inFlight = null;
      }
    });
    inFlight = guarded;
    return guarded;
  };
}
