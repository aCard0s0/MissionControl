package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A container's stdout/stderr tail, parsed into levelled lines.
 *
 * <p>Split out of {@link DockerGateway} because the severity rules are a policy of their
 * own: an explicit level a line states about itself always beats a keyword found later in
 * its prose, so "WARNING … connection failed … error" stays a warning.
 */
@Component
public class ContainerLogReader {

  private static final Logger log = LoggerFactory.getLogger(ContainerLogReader.class);

  private final DockerClients clients;

  public ContainerLogReader(DockerClients clients) {
    this.clients = clients;
  }

  public List<LogLineDto> logs(DockerHostRef host, String containerId, int tail) {
    DockerClient client = clients.forUrl(host.url());
    List<LogLineDto> lines = new ArrayList<>();
    // one assembler for the whole read: a line the daemon cut in half arrives as two frames,
    // and only something spanning them can put it back together
    LineAssembler assembler = new LineAssembler();
    boolean complete = false;
    try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        List<LogLineDto> parsed = assembler.accept(frame);
        if (!parsed.isEmpty()) {
          synchronized (lines) {
            lines.addAll(parsed);
          }
        }
      }
    }) {
      client.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withTimestamps(true)
          .withTail(Math.min(Math.max(tail, 1), 500))
          .exec(callback);
      complete = callback.awaitCompletion(8, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw e instanceof RuntimeException runtime ? runtime
          : new RuntimeException("logs failed: " + e.getMessage(), e);
    }
    if (!complete) {
      // close() does not join the reader thread, so it may still append after the wait
      // expires. Returning the live list would hand a mutating ArrayList to Jackson.
      log.warn("log tail for {} was cut short after 8s — returning a partial read", containerId);
    }
    // the final line often has no terminator — without this it is dropped from every tail
    List<LogLineDto> trailing = assembler.flush();
    synchronized (lines) {
      lines.addAll(trailing);
      return List.copyOf(lines);
    }
  }

  /**
   * Reassembles frames into whole lines.
   *
   * <p>A frame is a slice of the stream, not a record: the daemon cuts wherever its buffer
   * ends, which lands mid-line and — as seen in production — mid-timestamp. Parsing each
   * frame on its own turned
   * {@code 2026-08-20T01:19:51.227450970Z → gateway is now running…} into two entries, a bare
   * {@code 2026-08-20T01:19:51.227} and an orphaned {@code 450970Z → gateway is now running…},
   * and stamped both with the wall clock — so a torn line sorted above every real line in the
   * tail and the fleet view looked like it had just received a burst of nonsense.
   *
   * <p>One assembler spans a whole read. stdout and stderr are separate streams that interleave
   * at frame granularity, so each carries its own remainder.
   */
  static final class LineAssembler {

    /** A stream that never emits a newline must not grow the buffer without bound. */
    private static final int MAX_CARRY = 64 * 1024;

    private final Map<StreamType, StringBuilder> carry = new HashMap<>();

    /**
     * The last timestamp actually read off the stream.
     *
     * <p>What an unparseable line inherits, instead of the wall clock. A line that cannot
     * state its own time is a continuation of the one above it far more often than it is the
     * newest thing in the tail, and stamping it "now" is what floated the fragments to the top.
     */
    private long lastTs = 0;

    List<LogLineDto> accept(Frame frame) {
      StreamType stream = frame.getStreamType();
      StringBuilder buffer = carry.computeIfAbsent(stream, s -> new StringBuilder());
      buffer.append(new String(frame.getPayload(), StandardCharsets.UTF_8));

      List<LogLineDto> parsed = new ArrayList<>();
      // -1 keeps the trailing element, which is the part after the last terminator: the
      // incomplete remainder, or empty when the payload ended on a line boundary
      String[] parts = buffer.toString().split("\\R", -1);
      for (int i = 0; i < parts.length - 1; i++) {
        LogLineDto line = parseLogLine(stream, parts[i].stripTrailing(), this);
        if (line != null) parsed.add(line);
      }
      String remainder = parts[parts.length - 1];
      if (remainder.length() > MAX_CARRY) {
        // a pathological stream: emit what we have rather than buffer forever
        LogLineDto line = parseLogLine(stream, remainder, this);
        if (line != null) parsed.add(line);
        remainder = "";
      }
      buffer.setLength(0);
      buffer.append(remainder);
      return parsed;
    }

    /** Whatever never got its terminator, once the stream is done. */
    List<LogLineDto> flush() {
      List<LogLineDto> parsed = new ArrayList<>();
      for (Map.Entry<StreamType, StringBuilder> entry : carry.entrySet()) {
        LogLineDto line = parseLogLine(entry.getKey(), entry.getValue().toString().stripTrailing(), this);
        if (line != null) parsed.add(line);
      }
      carry.clear();
      return parsed;
    }
  }

  /** One frame on its own — a complete payload, which is what the parsing tests hand it. */
  static List<LogLineDto> parseLogFrame(Frame frame) {
    LineAssembler assembler = new LineAssembler();
    List<LogLineDto> parsed = new ArrayList<>(assembler.accept(frame));
    parsed.addAll(assembler.flush());
    return parsed;
  }

  private static LogLineDto parseLogLine(StreamType stream, String raw, LineAssembler assembler) {
    if (raw.isBlank()) return null;

    long ts = assembler.lastTs > 0 ? assembler.lastTs : System.currentTimeMillis();
    String msg = raw;
    int space = raw.indexOf(' ');
    if (space > 0) {
      try {
        ts = Instant.parse(raw.substring(0, space)).toEpochMilli();
        assembler.lastTs = ts;
        msg = raw.substring(space + 1);
      } catch (Exception ignored) {
        // line without a leading docker timestamp — keep it whole, at the time of its neighbour
      }
    } else {
      // Docker prefixes even an empty application record with its timestamp.
      // Treat that as an empty line instead of a new message stamped "now".
      try {
        assembler.lastTs = Instant.parse(raw).toEpochMilli();
        return null;
      } catch (Exception ignored) { }
    }

    if (msg.isBlank()) return null;

    // Explicit severity wins over keywords inside the prose. In particular,
    // "WARNING ... connection failed ... error" is still a warning, and
    // "INFO ... retrying after exception: ..." is still info — so every explicit
    // prefix is tested before any substring heuristic runs.
    String lower = msg.stripLeading().toLowerCase(Locale.ROOT);
    String level = explicitLevel(lower);
    if (level == null) level = keywordLevel(lower);
    if (level == null) {
      level = stream == StreamType.STDERR ? "warn" : "info";
    }
    return new LogLineDto(ts, level, "container", msg);
  }

  /** Severity the line states about itself, or null when it carries no level prefix. */
  private static String explicitLevel(String lower) {
    if (lower.startsWith("warning") || lower.startsWith("warn") || lower.startsWith("[warn")) return "warn";
    if (lower.startsWith("debug") || lower.startsWith("[debug")) return "debug";
    if (lower.startsWith("error") || lower.startsWith("fatal") || lower.startsWith("[emerg]")
        || lower.startsWith("traceback")) {
      return "error";
    }
    if (lower.startsWith("info") || lower.startsWith("[notice]") || lower.startsWith("[info")) return "info";
    return null;
  }

  /** Severity inferred from the prose, for lines that never named one. */
  private static String keywordLevel(String lower) {
    if (lower.contains("permissionerror:") || lower.contains("exception:")
        || lower.contains("fatal error")) {
      return "error";
    }
    if (lower.contains(": info:")) return "info";
    return null;
  }
}
