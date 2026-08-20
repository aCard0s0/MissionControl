package io.hermes.missioncontrol.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.hermes.missioncontrol.docker.LogLineDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The dashboard's own log tail, kept in memory so it can be read back over HTTP.
 *
 * <p>Mission Control shows a tail for every container it manages and none for itself, so the
 * one process whose logs explain a failed deploy or a stuck MCP operation was the one process
 * an operator had to leave the dashboard and run {@code docker logs} to read.
 *
 * <p>A ring buffer rather than a file: the container writes its log to stdout for the daemon
 * to own, and adding a second on-disk copy would mean a rotation policy, a volume and a
 * growth risk for something only ever read as "the last few hundred lines". What falls out of
 * the ring is still in {@code docker logs}.
 *
 * <p>Attached to the root logger, so it sees third-party output at whatever level
 * {@code application.yml} admits — the same lines that reach the console, no more.
 */
@Component
public class ServerLogBuffer extends AppenderBase<ILoggingEvent> {

  /** Roughly a screen of history per level, and a hard bound on the memory this holds. */
  static final int CAPACITY = 1_000;

  /** Long enough for a stack-trace-bearing message to stay useful, short enough to bound a read. */
  private static final int MAX_MESSAGE_CHARS = 2_000;

  private final Deque<LogLineDto> ring = new ArrayDeque<>(CAPACITY);

  @PostConstruct
  void attach() {
    org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    // Logback is what Spring Boot starts with, but a deployment that swapped the binding
    // should lose this endpoint rather than fail to start.
    if (!(factory instanceof ch.qos.logback.classic.LoggerContext context)) return;
    setContext(context);
    setName("mission-control-buffer");
    start();
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this);
  }

  @PreDestroy
  void detach() {
    org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof ch.qos.logback.classic.LoggerContext context) {
      context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
    }
    stop();
  }

  /**
   * Newest first, optionally filtered by level, capped at {@code tail} lines.
   *
   * <p>Newest first because that is the order the dashboard renders — the container tail it
   * sits beside is sorted the same way, so the two panels read alike.
   */
  public List<LogLineDto> tail(int tail, String level) {
    int limit = Math.min(Math.max(tail, 1), CAPACITY);
    String wanted = level == null || level.isBlank() || "all".equalsIgnoreCase(level)
        ? null : level.toLowerCase(Locale.ROOT);
    List<LogLineDto> out = new ArrayList<>(Math.min(limit, CAPACITY));
    synchronized (ring) {
      // descendingIterator walks newest to oldest, so the cap keeps the newest lines
      var it = ring.descendingIterator();
      while (it.hasNext() && out.size() < limit) {
        LogLineDto line = it.next();
        if (wanted == null || wanted.equals(line.level())) out.add(line);
      }
    }
    return out;
  }

  @Override
  protected void append(ILoggingEvent event) {
    LogLineDto line = new LogLineDto(
        event.getTimeStamp(),
        levelOf(event.getLevel()),
        simpleLoggerName(event.getLoggerName()),
        message(event));
    synchronized (ring) {
      if (ring.size() >= CAPACITY) ring.removeFirst();
      ring.addLast(line);
    }
  }

  /**
   * Logback's levels mapped onto the four the dashboard renders. TRACE joins DEBUG rather
   * than adding a fifth chip nothing in this application emits.
   */
  private static String levelOf(Level level) {
    if (level == null) return "info";
    if (level.isGreaterOrEqual(Level.ERROR)) return "error";
    if (level.isGreaterOrEqual(Level.WARN)) return "warn";
    if (level.isGreaterOrEqual(Level.INFO)) return "info";
    return "debug";
  }

  /** The same simple name the console pattern prints, so a line reads identically in both. */
  static String simpleLoggerName(String loggerName) {
    if (loggerName == null || loggerName.isBlank()) return "root";
    int lastDot = loggerName.lastIndexOf('.');
    return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
  }

  /**
   * The formatted message, with the throwable's own first line appended.
   *
   * <p>Only the first line: a full trace would push every other entry off a screen-sized tail,
   * and the console and {@code docker logs} both still carry it. Without any of it, an ERROR
   * logged as {@code log.error("failed", e)} reads as "failed" with the cause missing.
   */
  private static String message(ILoggingEvent event) {
    StringBuilder text = new StringBuilder(event.getFormattedMessage() == null
        ? "" : event.getFormattedMessage());
    IThrowableProxy thrown = event.getThrowableProxy();
    if (thrown != null) {
      text.append(" — ").append(thrown.getClassName());
      if (thrown.getMessage() != null) text.append(": ").append(thrown.getMessage());
    }
    String out = text.toString().strip();
    return out.length() > MAX_MESSAGE_CHARS ? out.substring(0, MAX_MESSAGE_CHARS) + "…" : out;
  }
}
