package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalSessionLimiterTest {

  @Test
  void concurrentAdmissionsNeverExceedMaximum() throws Exception {
    TerminalSessionLimiter limiter = new TerminalSessionLimiter(3);
    AtomicInteger admitted = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        executor.submit(() -> {
          start.await();
          if (limiter.tryAcquire()) admitted.incrementAndGet();
          return null;
        });
      }
      start.countDown();
      executor.shutdown();
      executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(3, admitted.get());
    assertEquals(0, limiter.available());
  }
}
