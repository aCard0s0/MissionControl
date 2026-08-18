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
}
