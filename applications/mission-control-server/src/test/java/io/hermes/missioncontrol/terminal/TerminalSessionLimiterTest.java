package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.hermes.missioncontrol.terminal.TerminalSessionLimiter.Admission;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The bound that keeps abandoned terminals from accumulating docker-exec threads.
 *
 * <p>Every reservation has to be released exactly once, on every path out. A leak here does not
 * fail anything visibly — it just walks the cap down until the terminal stops opening — so the
 * accounting is asserted directly rather than through the handler.
 */
class TerminalSessionLimiterTest {

  @Test
  void concurrentAdmissionsNeverExceedMaximum() throws Exception {
    TerminalSessionLimiter limiter = new TerminalSessionLimiter(3, 3);
    AtomicInteger admitted = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        int client = i;
        executor.submit(() -> {
          start.await();
          if (limiter.tryAcquire("client-" + client) == Admission.ADMITTED) {
            admitted.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
    assertEquals(3, admitted.get());
    assertEquals(0, limiter.available());
  }

  @Test
  void oneClientCannotTakeEveryGlobalSlot() {
    TerminalSessionLimiter limiter = new TerminalSessionLimiter(5, 2);

    assertEquals(Admission.ADMITTED, limiter.tryAcquire("10.0.0.1"));
    assertEquals(Admission.ADMITTED, limiter.tryAcquire("10.0.0.1"));
    assertEquals(Admission.PER_CLIENT_CAP, limiter.tryAcquire("10.0.0.1"));

    // and the refusal gave back the global slot it took on the way in, so the cap is not
    // silently walked down by a client that keeps retrying
    assertEquals(3, limiter.available());
    assertEquals(Admission.ADMITTED, limiter.tryAcquire("10.0.0.2"));
  }

  @Test
  void theGlobalCapIsReportedAheadOfThePerClientOne() {
    // the two produce different close reasons, so a client is told which limit it hit
    TerminalSessionLimiter limiter = new TerminalSessionLimiter(1, 5);
    limiter.tryAcquire("10.0.0.1");

    assertEquals(Admission.GLOBAL_CAP, limiter.tryAcquire("10.0.0.2"));
  }

  @Test
  void releasingReturnsBothHalvesOfTheReservation() {
    TerminalSessionLimiter limiter = new TerminalSessionLimiter(2, 1);
    limiter.tryAcquire("10.0.0.1");
    assertEquals(Admission.PER_CLIENT_CAP, limiter.tryAcquire("10.0.0.1"));

    limiter.release("10.0.0.1");

    assertEquals(2, limiter.available(), "the global slot came back");
    assertEquals(Admission.ADMITTED, limiter.tryAcquire("10.0.0.1"), "the per-client slot did not");
  }
}
