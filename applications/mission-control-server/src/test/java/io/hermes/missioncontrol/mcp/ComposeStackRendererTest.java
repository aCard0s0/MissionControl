package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposeStackRendererTest {

  @Test
  void rendersOwnedNonSecretComposeStackWithPrivateServicesAndVolumes() {
    StoredSupportService database = new StoredSupportService(
        "database", "postgres:16-alpine", null, List.of(), List.of(), List.of(),
        List.of(new VolumeSpec("data", "/var/lib/postgresql/data")),
        new HealthcheckSpec(List.of("CMD-SHELL", "pg_isready"), "5s", "3s", 10, null));
    StoredSupportService cache = new StoredSupportService(
        "cache", "redis:7-alpine", null, List.of(), List.of(), List.of(), List.of(), null);
    StoredConfig config = new StoredConfig(
        "sse", null, "example/mcp:latest", null, List.of(), List.of(), null, List.of(),
        1103, null, "/sse", null, List.of(), List.of(), List.of(), null,
        List.of(database, cache));
    ComposeStackRenderer renderer = new ComposeStackRenderer();

    ComposeStackRenderer.Rendered rendered = renderer.render(List.of(new ComposeStackRenderer.Deployment(
        "mcp-1", "postgres-mcp", config,
        Map.of("DATABASE_URL", "postgres://mcp:very-secret@database/mcp"),
        Map.of("database", Map.of("POSTGRES_PASSWORD", "very-secret"), "cache", Map.of()))));

    assertTrue(rendered.yaml().contains("io.hermes.mission-control.owner: 'mission-control-mcp'"));
    assertTrue(rendered.yaml().contains("name: 'mission-control-mcp-net'"));
    assertTrue(rendered.yaml().contains("condition: service_healthy"));
    assertTrue(rendered.yaml().contains("condition: service_started"));
    assertTrue(rendered.yaml().contains("'postgres-mcp-database-data:/var/lib/postgresql/data'"));
    assertTrue(rendered.yaml().contains("${MC_MCP_"));
    assertTrue(rendered.yaml().contains(":-}"));
    assertFalse(rendered.yaml().contains("very-secret"));
    assertTrue(rendered.processEnvironment().containsValue("very-secret"));
    assertEquals(List.of("postgres-mcp", "postgres-mcp-database", "postgres-mcp-cache"),
        rendered.serviceNames().get("mcp-1"));
    assertEquals(List.of("mission-control-mcp-postgres-mcp-database-data"),
        rendered.volumeNames().get("mcp-1"));
  }

  @Test
  void entrypointAndCommandRenderAsListsWithQuotesEscaped() {
    StoredConfig config = new StoredConfig(
        "sse", null, "example/mcp:latest", null,
        List.of("python", "-c"), List.of("uvicorn.run(host='0.0.0.0')"), null, List.of(),
        1103, null, "/sse", null, List.of(), List.of(), List.of(), null, List.of());

    String yaml = new ComposeStackRenderer().render(List.of(new ComposeStackRenderer.Deployment(
        "mcp-1", "postgres-mcp", config, Map.of(), Map.of()))).yaml();

    assertTrue(yaml.contains("    entrypoint:\n      - 'python'\n      - '-c'\n"));
    // Single quotes inside the boot command must survive as the YAML '' escape.
    assertTrue(yaml.contains("    command:\n      - 'uvicorn.run(host=''0.0.0.0'')'\n"));
  }

  @Test
  void emptyStackIsStillValidStructuredYaml() {
    String yaml = new ComposeStackRenderer().render(List.of()).yaml();
    assertTrue(yaml.startsWith("services: {}"));
    assertFalse(yaml.contains("project_name: mcp"));
  }

  // ── optional blocks ─────────────────────────────────────────────────────

  @Test
  void anEmptyHostRendersAValidButEmptyComposeFile() {
    // Compose refuses a file with a bare 'services:' key and nothing under it
    ComposeStackRenderer.Rendered rendered = new ComposeStackRenderer().render(List.of());

    // the empty services map plus the network declaration: Compose refuses a bare 'services:' key
    assertTrue(rendered.yaml().startsWith("services: {}\n"), rendered.yaml());
    assertTrue(rendered.processEnvironment().isEmpty());
    assertTrue(rendered.serviceNames().isEmpty());
    assertTrue(rendered.volumeNames().isEmpty());
  }

  @Test
  void aHealthcheckIsRenderedFieldByFieldAndOmitsWhatItDoesNotCarry() {
    String yaml = render(config(c -> c.healthcheck =
        new HealthcheckSpec(List.of("CMD", "true"), "5s", "3s", 3, "1s"))).yaml();

    assertTrue(yaml.contains("      interval: '5s'"), yaml);
    assertTrue(yaml.contains("      timeout: '3s'"));
    assertTrue(yaml.contains("      retries: 3"));
    assertTrue(yaml.contains("      start_period: '1s'"));

    String sparse = render(config(c -> c.healthcheck =
        new HealthcheckSpec(List.of("CMD-SHELL", "curl -f http://localhost:1100/mcp"),
            null, null, null, null))).yaml();

    assertTrue(sparse.contains("      test:\n"));
    assertTrue(!sparse.contains("interval"), sparse);
    assertTrue(!sparse.contains("retries"));
    assertTrue(!sparse.contains("start_period"));
  }

  @Test
  void aServiceWithNoOptionalFieldsRendersNoneOfThoseKeys() {
    String yaml = render(config(c -> { })).yaml();

    assertTrue(!yaml.contains("platform:"), yaml);
    assertTrue(!yaml.contains("entrypoint:"));
    assertTrue(!yaml.contains("command:"));
    assertTrue(!yaml.contains("environment:"));
    assertTrue(!yaml.contains("healthcheck:"));
  }

  @Test
  void aPlatformAndAnEntrypointAreRenderedWhenPresent() {
    String yaml = render(config(c -> {
      c.platform = "linux/arm64";
      c.entrypoint = List.of("python", "-c");
      c.command = List.of("print('hi')");
    })).yaml();

    assertTrue(yaml.contains("    platform: 'linux/arm64'"), yaml);
    assertTrue(yaml.contains("    entrypoint:\n      - 'python'\n      - '-c'\n"));
    assertTrue(yaml.contains("    command:\n      - 'print(''hi'')'\n"), "single quotes are doubled");
  }

  @Test
  void aVolumeKeyLongerThanDockerAllowsIsTruncated() {
    // Compose/Docker cap a volume name at 63 characters; an over-long logical name would
    // otherwise be rejected by the daemon at create time
    String logicalName = "d".repeat(80);
    ComposeStackRenderer.Rendered rendered =
        render(config(c -> c.volumes = List.of(new VolumeSpec(logicalName, "/data"))));

    String volume = rendered.volumeNames().values().iterator().next().getFirst();
    assertTrue(volume.startsWith(ComposeStackRenderer.PROJECT + "-"), volume);
    assertTrue(volume.length() <= ComposeStackRenderer.PROJECT.length() + 1 + 63, volume);
  }

  /** One managed deployment, so each test above sets only the field it is about. */
  private static ComposeStackRenderer.Rendered render(StoredConfig config) {
    return new ComposeStackRenderer().render(List.of(new ComposeStackRenderer.Deployment(
        "mcp-1", "postgres-mcp", config, Map.of(), Map.of())));
  }

  private static StoredConfig config(java.util.function.Consumer<Fields> tweak) {
    Fields fields = new Fields();
    tweak.accept(fields);
    return new StoredConfig("http", null, "example/mcp:latest", fields.platform, fields.entrypoint,
        fields.command, null, List.of(), 1103, null, "/mcp", null, List.of(), List.of(),
        fields.volumes, fields.healthcheck, List.of());
  }

  private static final class Fields {
    String platform;
    List<String> entrypoint = List.of();
    List<String> command = List.of();
    List<VolumeSpec> volumes = List.of();
    HealthcheckSpec healthcheck;
  }
}
