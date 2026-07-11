package io.hermes.missioncontrol.terminal;

import java.util.concurrent.Semaphore;

/** Atomic global admission gate for live terminal sessions. */
final class TerminalSessionLimiter {
  private final Semaphore slots;

  TerminalSessionLimiter(int maximum) {
    this.slots = new Semaphore(maximum);
  }

  boolean tryAcquire() {
    return slots.tryAcquire();
  }

  void release() {
    slots.release();
  }

  int available() {
    return slots.availablePermits();
  }
}
