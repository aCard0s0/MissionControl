package io.hermes.missioncontrol.terminal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission for live terminal sessions: how many may exist at once, and how many one client may
 * hold.
 *
 * <p>Both halves are here because they are reserved and released together. They used to be
 * split across this class and {@link TerminalSocketHandler}, which also kept a third counter of
 * its own — commented as "the source of truth" while the semaphore here was what actually
 * admitted anyone, and read nowhere but a debug line. Three representations of one number,
 * released from two places.
 *
 * <p>A reservation must be released on every exit path, including the failed ones, or the cap
 * decays towards zero and the terminal stops opening. That is the whole reason the handler's
 * teardown is idempotent.
 */
final class TerminalSessionLimiter {

  /** Why a session was refused, so the client is told which limit it hit. */
  enum Admission {
    ADMITTED,
    GLOBAL_CAP,
    PER_CLIENT_CAP
  }

  private final Semaphore global;
  private final int perClientMaximum;
  private final Map<String, AtomicInteger> perClient = new ConcurrentHashMap<>();

  TerminalSessionLimiter(int maximum, int perClientMaximum) {
    this.global = new Semaphore(maximum);
    this.perClientMaximum = perClientMaximum;
  }

  /**
   * Reserves a slot for {@code clientKey}. The caller must {@link #release} it on every path out,
   * and only when this returned {@link Admission#ADMITTED} — a refusal has already backed out
   * whatever it took.
   */
  Admission tryAcquire(String clientKey) {
    if (!global.tryAcquire()) return Admission.GLOBAL_CAP;
    if (perClientCount(clientKey).incrementAndGet() > perClientMaximum) {
      decrementPerClient(clientKey);
      global.release();
      return Admission.PER_CLIENT_CAP;
    }
    return Admission.ADMITTED;
  }

  void release(String clientKey) {
    decrementPerClient(clientKey);
    global.release();
  }

  /** How many slots are still free — the bound a test can observe. */
  int available() {
    return global.availablePermits();
  }

  private AtomicInteger perClientCount(String clientKey) {
    return perClient.computeIfAbsent(clientKey, key -> new AtomicInteger());
  }

  /** Drops the entry at zero, so the map cannot grow one key per client address seen. */
  private void decrementPerClient(String clientKey) {
    perClient.computeIfPresent(clientKey, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
  }
}
