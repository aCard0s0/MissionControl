package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The per-profile s6 gateway log, including rotated files.
 *
 * <p>Split out of {@link HermesProfiles} because this is a log format of its own: it is
 * read from disk rather than from Docker's container-wide stdout/stderr stream, and its
 * timestamps and severities parse differently from a container log line.
 */
@Component
class HermesGatewayLogs {

  private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
  private static final Pattern LOG_LINE = Pattern.compile(
      "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{1,9})\\s{2}(.*)$");
  private static final DateTimeFormatter LOG_TIME = new DateTimeFormatterBuilder()
      .appendPattern("yyyy-MM-dd HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
      .toFormatter(Locale.ROOT);

  private final HermesContainerFiles files;

  HermesGatewayLogs(HermesContainerFiles files) {
    this.files = files;
  }

  List<LogLineDto> read(DockerHostRef host, String containerId, String profileName, int tail) {
    String logDir = ProfilePaths.gatewayLogDir(profileName);
    int limit = Math.min(Math.max(tail, 1), 500);
    String script = """
        dir="$1"; limit="$2"
        { for file in "$dir"/@*.u "$dir/current"; do
            [ -f "$file" ] && cat "$file"
          done
        } | tail -n "$limit"
        """;
    ExecResult result = files.exec(
        host, containerId, List.of("sh", "-c", script, "_", logDir, String.valueOf(limit)));
    return parse(profileName, result.stdout());
  }

  static List<LogLineDto> parse(String profileName, String output) {
    List<LogLineDto> lines = new ArrayList<>();
    for (String raw : (output == null ? "" : output).split("\\R")) {
      Matcher matcher = LOG_LINE.matcher(raw);
      if (!matcher.matches()) continue;
      String message = ANSI.matcher(matcher.group(2)).replaceAll("").stripTrailing();
      if (message.isBlank()) continue;
      try {
        long timestamp = LocalDateTime.parse(matcher.group(1), LOG_TIME)
            .toInstant(ZoneOffset.UTC).toEpochMilli();
        lines.add(new LogLineDto(timestamp, level(message), profileName, message));
      } catch (RuntimeException ignored) {
        // A malformed line must not poison the rest of the tail.
      }
    }
    return lines;
  }

  private static String level(String message) {
    String lower = message.stripLeading().toLowerCase(Locale.ROOT);
    if (lower.startsWith("warning") || lower.startsWith("warn") || lower.startsWith("[warn")) return "warn";
    if (lower.startsWith("debug") || lower.startsWith("[debug")) return "debug";
    if (lower.startsWith("error") || lower.startsWith("fatal") || lower.startsWith("traceback")
        || lower.contains("permissionerror:") || lower.contains("exception:")) return "error";
    return "info";
  }
}
