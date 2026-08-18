package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerLogReaderTest {

  private static Frame frame(StreamType stream, String payload) {
    return new Frame(stream, payload.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> levelsOf(List<LogLineDto> lines) {
    return lines.stream().map(LogLineDto::level).toList();
  }

  /**
   * Agents retry MCP connections constantly and say so at INFO. Letting the word
   * "exception" in the prose outrank the stated level floods the log viewer's error
   * badge on a perfectly healthy container.
   */
  @Test
  void anExplicitInfoPrefixSurvivesTheWordExceptionInTheProse() {
    Frame frame = frame(StreamType.STDERR,
        "2026-08-14T10:00:00.000000000Z INFO tools.mcp: retrying after exception: connection reset\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(1, lines.size());
    assertEquals("info", lines.get(0).level());
    assertEquals("INFO tools.mcp: retrying after exception: connection reset", lines.get(0).msg());
  }

  /**
   * Delivered on stdout with an "exception:" in the prose, so neither the keyword scan
   * nor the stream-type default can produce "warn" by accident.
   */
  @Test
  void anExplicitWarningPrefixSurvivesTheWordErrorInTheProse() {
    Frame frame = frame(StreamType.STDOUT,
        "2026-08-14T10:00:01.000000000Z WARNING tools.mcp: connection failed with error, exception: broken pipe\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(1, lines.size());
    assertEquals("warn", lines.get(0).level());
  }

  @Test
  void anUnprefixedStderrLineDefaultsToWarnAndAnUnprefixedStdoutLineDefaultsToInfo() {
    String payload = "2026-08-14T10:00:02.000000000Z listening on 0.0.0.0:8080\n";

    List<LogLineDto> onStderr = ContainerLogReader.parseLogFrame(frame(StreamType.STDERR, payload));
    List<LogLineDto> onStdout = ContainerLogReader.parseLogFrame(frame(StreamType.STDOUT, payload));

    assertEquals(1, onStderr.size());
    assertEquals(1, onStdout.size());
    assertEquals("warn", onStderr.get(0).level());
    assertEquals("info", onStdout.get(0).level());
    assertEquals("listening on 0.0.0.0:8080", onStdout.get(0).msg());
    assertEquals(onStdout.get(0).msg(), onStderr.get(0).msg());
  }

  /** Python writes tracebacks to stdout under some runners, where the stream tells us nothing. */
  @Test
  void aLineThatNamesNoLevelStillGetsErrorFromAPythonTraceback() {
    Frame frame = frame(StreamType.STDOUT,
        "2026-08-14T10:00:03.000000000Z PermissionError: denied\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(1, lines.size());
    assertEquals("error", lines.get(0).level());
  }

  /** All on stdout, whose default is "info", so every level below is genuinely read off the line. */
  @Test
  void explicitDebugAndErrorPrefixesAreHonoured() {
    Frame frame = frame(StreamType.STDOUT, "2026-08-14T10:00:04.000000000Z DEBUG polling for work\n"
        + "2026-08-14T10:00:05.000000000Z ERROR could not reach registry\n"
        + "2026-08-14T10:00:06.000000000Z FATAL config volume is unreadable\n"
        + "2026-08-14T10:00:07.000000000Z [emerg] worker process exited\n"
        + "2026-08-14T10:00:08.000000000Z Traceback (most recent call last):\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(List.of("debug", "error", "error", "error", "error"), levelsOf(lines));
  }

  @Test
  void aMultilineFrameIsSplitIntoOneEntryPerLine() {
    Frame frame = frame(StreamType.STDOUT, "2026-08-14T10:00:09.000000000Z first\n"
        + "2026-08-14T10:00:10.000000000Z second\n"
        + "2026-08-14T10:00:11.000000000Z third\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(List.of("first", "second", "third"), lines.stream().map(LogLineDto::msg).toList());
  }

  /** Docker stamps even an empty application record, and "now" would misorder the viewer. */
  @Test
  void aRecordThatIsOnlyATimestampIsDroppedRatherThanStampedNow() {
    Frame frame = frame(StreamType.STDERR, "2026-07-10T17:14:39.902148126Z\n");

    assertTrue(ContainerLogReader.parseLogFrame(frame).isEmpty());
  }

  @Test
  void theDockerTimestampIsParsedIntoEpochMillisAndStrippedFromTheMessage() {
    Frame frame = frame(StreamType.STDOUT, "2026-08-14T10:17:03.128456000Z tools.mcp: ready\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(1, lines.size());
    LogLineDto line = lines.get(0);
    assertEquals(1786702623128L, line.ts());
    assertEquals("tools.mcp: ready", line.msg());
    assertFalse(line.msg().startsWith("2026-08-14T10:17:03"));
    assertEquals("container", line.source());
  }

  @Test
  void aLineWithoutADockerTimestampKeepsItsFullText() {
    long before = System.currentTimeMillis();
    Frame frame = frame(StreamType.STDOUT, "no timestamp here at all\n");

    List<LogLineDto> lines = ContainerLogReader.parseLogFrame(frame);

    assertEquals(1, lines.size());
    assertEquals("no timestamp here at all", lines.get(0).msg());
    assertTrue(lines.get(0).ts() >= before);
  }

  @Test
  void blankLinesProduceNoEntries() {
    Frame frame = frame(StreamType.STDOUT, "\n   \n\n");

    assertTrue(ContainerLogReader.parseLogFrame(frame).isEmpty());
  }

  @Test
  void everyLevelPrefixAContainerCanEmitIsHonoured() {
    // the log pane colours off this, and these are the prefixes a Hermes image actually writes:
    // bare words, bracketed nginx/postgres forms, and python tracebacks
    assertEquals("warn", levelOf("WARNING falling back"));
    assertEquals("warn", levelOf("warn retrying"));
    assertEquals("warn", levelOf("[warn] retrying"));
    assertEquals("debug", levelOf("DEBUG resolved"));
    assertEquals("debug", levelOf("[debug] cache hit"));
    assertEquals("error", levelOf("ERROR refused"));
    assertEquals("error", levelOf("FATAL cannot bind"));
    assertEquals("error", levelOf("[emerg] no upstream"));
    assertEquals("error", levelOf("Traceback (most recent call last):"));
    assertEquals("info", levelOf("INFO listening"));
    assertEquals("info", levelOf("[notice] started"));
    assertEquals("info", levelOf("[info] ready"));
  }

  @Test
  void aLineThatNamesNoLevelIsJudgedOnItsProse() {
    assertEquals("error", levelOf("could not open file PermissionError: denied"));
    assertEquals("error", levelOf("ValueError exception: bad model"));
    assertEquals("error", levelOf("gateway hit a fatal error and gave up"));
    assertEquals("info", levelOf("gateway: info: listening on 8080"));
  }

  @Test
  void aBlankOrTimestampOnlyRecordIsDropped() {
    // docker prefixes even an empty application record with its own timestamp
    assertEquals(0, ContainerLogReader.parseLogFrame(frame(StreamType.STDOUT, "   \n")).size());
    assertEquals(0, ContainerLogReader.parseLogFrame(
        frame(StreamType.STDOUT, "2026-08-13T10:25:55.000000000Z \n")).size());
  }

  @Test
  void aLineWithNoLeadingTimestampIsKeptWholeRatherThanTruncated() {
    var lines = ContainerLogReader.parseLogFrame(frame(StreamType.STDOUT, "no-timestamp-here\n"));

    assertEquals(1, lines.size());
    assertEquals("no-timestamp-here", lines.getFirst().msg());
  }

  @Test
  void aLineWhoseFirstWordIsNotATimestampKeepsThatWord() {
    // 'starting up' would otherwise lose 'starting' to a failed timestamp parse
    var lines = ContainerLogReader.parseLogFrame(frame(StreamType.STDOUT, "starting up\n"));

    assertEquals("starting up", lines.getFirst().msg());
  }

  /** The level assigned to a single stdout line carrying a docker timestamp. */
  private static String levelOf(String message) {
    return ContainerLogReader.parseLogFrame(
        frame(StreamType.STDOUT, "2026-08-13T10:25:55.000000000Z " + message + "\n"))
        .getFirst().level();
  }
}
