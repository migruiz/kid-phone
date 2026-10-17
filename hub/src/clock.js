// All time-based behaviour goes through a clock so tests can fast-forward without sleeping.
// A clock is { now(), setTimer(fn, ms) -> handle, clearTimer(handle) }.

export const realClock = {
  now: () => Date.now(),
  setTimer(fn, ms) {
    const timer = setTimeout(fn, ms);
    timer.unref(); // the HTTP servers keep the process alive, not these timers
    return timer;
  },
  clearTimer: (timer) => clearTimeout(timer),
};
