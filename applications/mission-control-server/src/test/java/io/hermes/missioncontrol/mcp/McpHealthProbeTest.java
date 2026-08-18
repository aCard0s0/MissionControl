package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a probe response becomes an operator-facing verdict.
 *
 * <p>The distinctions here are the point: a 421 is an image that rejects the Compose service
 * name as a Host header — no Agent can reach it — while a 404/405 is a path mistake and an
 * SSE entry answering JSON is a transport mismatch. Reporting all of those as "HTTP nnn"
 * would leave the operator nothing to act on.
 */
class McpHealthProbeTest {

  @Test
  void aRejectedHostHeaderIsReportedAsAnImageFaultRatherThanAnHttpCode() {
    String failure = McpHealthProbe.probeFailure(
        421, "text/plain", transport("sse"), "http://postgres-mcp:1103/sse");

    assertTrue(failure.contains("Host header"));
    assertTrue(failure.contains("http://postgres-mcp:1103/sse"));
  }

  @Test
  void aHealthyEndpointProducesNoFailureAndAWrongContentTypeDoes() {
    assertNull(McpHealthProbe.probeFailure(
        200, "text/event-stream; charset=utf-8", transport("sse"), "http://pg:1103/sse"));
    assertNull(McpHealthProbe.probeFailure(
        200, "text/event-stream", transport("http"), "http://c7:1101/mcp"));
    // A streamable-HTTP server answering an SSE entry means the entry is misconfigured.
    assertTrue(McpHealthProbe.probeFailure(
            200, "application/json", transport("sse"), "http://pg:1103/sse")
        .contains("rather than an SSE stream"));
  }

  @Test
  void aMissingEndpointIsDistinguishedFromAnUnhealthyOne() {
    assertTrue(McpHealthProbe.probeFailure(404, "", transport("http"), "http://x:1/mcp")
        .contains("no MCP endpoint"));
    assertTrue(McpHealthProbe.probeFailure(405, "", transport("http"), "http://x:1/mcp")
        .contains("no MCP endpoint"));
    assertTrue(McpHealthProbe.probeFailure(503, "", transport("http"), "http://x:1/mcp")
        .contains("HTTP 503"));
  }

  private static StoredConfig transport(String transport) {
    return new StoredConfig(transport, null, "example/mcp:latest", null, List.of(), List.of(),
        null, List.of(), 1103, null, "/sse", null, List.of(), List.of(), List.of(), null,
        List.of());
  }
}
