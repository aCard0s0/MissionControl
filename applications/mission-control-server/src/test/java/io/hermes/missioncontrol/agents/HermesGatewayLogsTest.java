package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The per-profile s6 gateway log has its own line format, distinct from a Docker
 * container log: an explicit severity in the message wins over any keyword later in it,
 * and a line that does not match at all must not poison the rest of the tail.
 */
class HermesGatewayLogsTest {

  @Test
  void gatewayLogParserPreservesTimeIdentityAndSeverity() {
    String output = """
        2026-07-11 10:24:39.656561717  gateway started
        not a supervised gateway record
        2026-07-11 10:24:40.000000000  \u001B[33mWARNING provider error is recoverable\u001B[0m
        2026-07-11 10:24:41.123000000  PermissionError: denied
        """;

    var lines = HermesGatewayLogs.parse("trader-00", output);

    assertEquals(3, lines.size());
    assertEquals("trader-00", lines.get(0).source());
    assertEquals("info", lines.get(0).level());
    assertEquals(1783765479656L, lines.get(0).ts());
    assertEquals("warn", lines.get(1).level(), "an explicit warning wins over 'error' in its message");
    assertEquals("WARNING provider error is recoverable", lines.get(1).msg());
    assertEquals("error", lines.get(2).level());
  }

  @Test
  void everySeverityKeywordTheGatewayEmitsIsRecognised() {
    // the dashboard colours the log pane off this, and an s6 gateway writes all of these shapes:
    // bare words, bracketed prefixes, and python tracebacks
    assertEquals("warn", levelOf("WARNING model fell back"));
    assertEquals("warn", levelOf("warn: retrying"));
    assertEquals("warn", levelOf("[WARN] retrying"));
    assertEquals("debug", levelOf("DEBUG resolved provider"));
    assertEquals("debug", levelOf("[debug] cache hit"));
    assertEquals("error", levelOf("ERROR provider refused"));
    assertEquals("error", levelOf("FATAL cannot bind port"));
    assertEquals("error", levelOf("Traceback (most recent call last):"));
    assertEquals("info", levelOf("gateway started"));
  }

  @Test
  void aPythonFailureIsAnErrorEvenWhenTheKeywordIsNotAtTheStart() {
    // the gateway logs these mid-line, and they are the failures an operator is looking for
    assertEquals("error", levelOf("  File \"/x.py\", line 2, in <module> PermissionError: denied"));
    assertEquals("error", levelOf("during handling of the above ValueError exception: bad model"));
    // leading whitespace must not hide an explicit severity
    assertEquals("warn", levelOf("    WARNING indented"));
  }

  @Test
  void aLineWithAnUnparseableTimestampIsDroppedRatherThanPoisoningTheTail() {
    String output = """
        2026-13-45 99:99:99.000000000  impossible date
        2026-07-11 10:24:39.656561717  gateway started
        """;

    var lines = HermesGatewayLogs.parse("trader-00", output);

    assertEquals(1, lines.size());
    assertEquals("gateway started", lines.getFirst().msg());
  }

  @Test
  void aLineThatIsOnlyAnsiColourCodesIsDropped() {
    // the reset sequence arrives on its own line while a spinner redraws
    var lines = HermesGatewayLogs.parse("trader-00",
        "2026-07-11 10:24:39.656561717  [0m[33m[0m   \n");

    assertEquals(0, lines.size());
  }

  @Test
  void noOutputAtAllIsNoLines() {
    assertEquals(0, HermesGatewayLogs.parse("trader-00", null).size());
    assertEquals(0, HermesGatewayLogs.parse("trader-00", "").size());
  }

  /** The level the parser assigned to a single well-formed line. */
  private static String levelOf(String message) {
    return HermesGatewayLogs.parse("p", "2026-07-11 10:24:39.000000000  " + message)
        .getFirst().level();
  }
}
