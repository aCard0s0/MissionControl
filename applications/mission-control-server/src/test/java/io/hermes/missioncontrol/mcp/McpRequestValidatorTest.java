package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpRequestValidatorTest {

  @Test
  void validatesAndNormalizesManagedInput() {
    var value = McpRequestValidator.validate(new McpServerRequest(
        " Demo ", " description ", "MANAGED", "dh-local", "HTTP", null,
        "example/server:latest", "linux/arm64", List.of("node"), List.of("server.js"),
        null, List.of(), 1100, null, null, null,
        List.of(new ConfigValueInput("TOKEN", "value", true, false)), List.of(),
        List.of(new VolumeSpec("data", "/data")), null, List.of()));

    assertEquals("Demo", value.name());
    assertEquals("managed", value.kind());
    assertEquals("http", value.transport());
    assertEquals("/mcp", value.path());
  }

  @Test
  void rejectsUnsafeDeploymentShapes() {
    McpServerRequest bindLikeVolume = managed(List.of(new VolumeSpec("data", "/var/run/docker.sock")));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(bindLikeVolume));

    McpServerRequest newlineCommand = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of("hello\nworld"), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(newlineCommand));
  }

  @Test
  void externalAllowsHeadersButRejectsEnvironmentAndRedirectSchemes() {
    McpServerRequest withEnvironment = new McpServerRequest(
        "Remote", null, "external", null, "sse", "https://example.test/sse", null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(new ConfigValueInput("TOKEN", "x", true, false)), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(withEnvironment));

    McpServerRequest fileUrl = new McpServerRequest(
        "Remote", null, "external", null, "http", "file:///etc/passwd", null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(fileUrl));
  }

  @Test
  void stdioUsesExecutableAndArgsNotManagedCommand() {
    var value = McpRequestValidator.validate(new McpServerRequest(
        "Local tool", null, "stdio", null, null, null, null, null,
        List.of(), List.of(), "npx", List.of("-y", "@example/mcp"), null, null,
        null, null, List.of(new ConfigValueInput("TOKEN", "x", true, false)),
        List.of(), List.of(), null, List.of()));
    assertEquals("stdio", value.transport());
    assertEquals("npx", value.stdioCommand());
    assertEquals(List.of("-y", "@example/mcp"), value.args());
  }

  private static McpServerRequest managed(List<VolumeSpec> volumes) {
    return new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), volumes, null, List.of());
  }
}
