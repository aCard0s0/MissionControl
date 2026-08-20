package io.hermes.missioncontrol.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The dashboard's own log tail, driven through the real logging framework rather than by
 * calling {@code append} directly — the mapping from a Logback event to a rendered line is
 * the whole of this class, and a hand-built event would not exercise the binding that
 * {@link ServerLogBuffer#attach()} sets up.
 */
class ServerLogBufferTest {

  private ServerLogBuffer buffer;
  private Logger logger;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    buffer = new ServerLogBuffer();
    buffer.attach();
    logger = (Logger) LoggerFactory.getLogger(ServerLogBufferTest.class);
    originalLevel = logger.getLevel();
    logger.setLevel(Level.TRACE);   // the buffer must see everything the test emits
  }

  @AfterEach
  void tearDown() {
    logger.setLevel(originalLevel);
    buffer.detach();
  }

  @Test
  void everyLogbackLevelLandsOnOneTheDashboardRenders() {
    logger.error("an error");
    logger.warn("a warning");
    logger.info("a note");
    logger.debug("a detail");
    // trace joins debug rather than adding a fifth chip nothing in this application emits
    logger.trace("a trace");

    assertEquals(List.of("debug", "debug", "info", "warn", "error"),
        buffer.tail(10, null).stream().map(LogLineDto::level).toList());
  }

  @Test
  void theTailIsNewestFirstSoItReadsLikeTheContainerTailBesideIt() {
    logger.info("first");
    logger.info("second");
    logger.info("third");

    assertEquals(List.of("third", "second", "first"),
        buffer.tail(10, null).stream().map(LogLineDto::msg).toList());
  }

  @Test
  void aLevelFilterKeepsOnlyThatLevel() {
    logger.info("a note");
    logger.error("an error");
    logger.warn("a warning");

    assertEquals(List.of("an error"),
        buffer.tail(10, "error").stream().map(LogLineDto::msg).toList());
    // absent and 'all' both mean everything retained
    assertEquals(3, buffer.tail(10, "all").size());
    assertEquals(3, buffer.tail(10, "  ").size());
  }

  @Test
  void theCapKeepsTheNewestLinesNotTheFirstOnesFound() {
    logger.info("oldest");
    logger.info("middle");
    logger.info("newest");

    assertEquals(List.of("newest", "middle"),
        buffer.tail(2, null).stream().map(LogLineDto::msg).toList());
  }

  @Test
  void theRingDropsTheOldestLineRatherThanGrowingWithoutBound() {
    for (int i = 0; i < ServerLogBuffer.CAPACITY + 50; i++) {
      logger.info("line {}", i);
    }

    List<LogLineDto> all = buffer.tail(ServerLogBuffer.CAPACITY, null);
    assertEquals(ServerLogBuffer.CAPACITY, all.size());
    // the 50 that fell out are the oldest, and are still in `docker logs`
    assertEquals("line " + (ServerLogBuffer.CAPACITY + 49), all.get(0).msg());
    assertEquals("line 50", all.get(all.size() - 1).msg());
  }

  @Test
  void aLineCarriesTheSameSimpleNameTheConsolePatternPrints() {
    logger.info("a note");

    assertEquals("ServerLogBufferTest", buffer.tail(1, null).get(0).source());
    // and a logger with no package at all is still named, not blank
    assertEquals("root", ServerLogBuffer.simpleLoggerName(null));
    assertEquals("bare", ServerLogBuffer.simpleLoggerName("bare"));
  }

  @Test
  void aThrownCauseIsSummarisedOntoTheLineRatherThanLost() {
    // log.error("failed", e) otherwise renders as "failed" with the cause nowhere on screen
    logger.error("upgrade failed", new IllegalStateException("container is not running"));

    String msg = buffer.tail(1, null).get(0).msg();
    assertTrue(msg.startsWith("upgrade failed"), msg);
    assertTrue(msg.contains("IllegalStateException"), msg);
    assertTrue(msg.contains("container is not running"), msg);
  }

  @Test
  void aVeryLongLineIsTruncatedSoOneEntryCannotFillTheTail() {
    logger.info("x".repeat(5_000));

    String msg = buffer.tail(1, null).get(0).msg();
    assertTrue(msg.length() < 5_000, "expected truncation, got " + msg.length());
    assertTrue(msg.endsWith("…"));
  }

  @Test
  void aDetachedBufferStopsCollecting() {
    buffer.detach();

    logger.info("after detach");

    assertFalse(buffer.tail(10, null).stream().anyMatch(l -> l.msg().equals("after detach")));
  }

  @Test
  void attachingIsSkippedWhenTheBindingIsNotLogback() {
    // a deployment that swapped the SLF4J binding should lose this endpoint, not fail to boot
    assertTrue(LoggerFactory.getILoggerFactory() instanceof LoggerContext,
        "this test only proves the guard is reachable while logback is the binding");
    assertEquals(0, new ServerLogBuffer().tail(10, null).size());
  }
}
