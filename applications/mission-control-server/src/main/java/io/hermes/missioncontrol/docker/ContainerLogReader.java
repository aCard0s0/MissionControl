package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  public List<LogLineDto> logs(String url, String containerId, int tail) {
    DockerClient client = clients.forUrl(url);
    List<LogLineDto> lines = new ArrayList<>();
    boolean complete = false;
    try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        List<LogLineDto> parsed = parseLogFrame(frame);
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
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }

  static List<LogLineDto> parseLogFrame(Frame frame) {
    String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
    List<LogLineDto> parsed = new ArrayList<>();
    for (String raw : payload.split("\\R", -1)) {
      LogLineDto line = parseLogLine(frame, raw.stripTrailing());
      if (line != null) parsed.add(line);
    }
    return parsed;
  }

  private static LogLineDto parseLogLine(Frame frame, String raw) {
    if (raw.isBlank()) return null;

    long ts = System.currentTimeMillis();
    String msg = raw;
    int space = raw.indexOf(' ');
    if (space > 0) {
      try {
        ts = Instant.parse(raw.substring(0, space)).toEpochMilli();
        msg = raw.substring(space + 1);
      } catch (Exception ignored) {
        // line without a leading docker timestamp — keep it whole
      }
    } else {
      // Docker prefixes even an empty application record with its timestamp.
      // Treat that as an empty line instead of a new message stamped "now".
      try {
        Instant.parse(raw);
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
      level = frame.getStreamType() == StreamType.STDERR ? "warn" : "info";
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
