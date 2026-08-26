package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The rendered stack is checked by parsing it back, not by matching the whitespace it happens
 * to emit. A generated Compose file is only ever read by Compose, so what has to hold is that
 * the document parses and says what it was asked to — not that a key sits at a given column.
 */
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

    ComposeStackRenderer.Rendered rendered = new ComposeStackRenderer().render(
        List.of(new ComposeStackRenderer.Deployment(
            "mcp-1", "postgres-mcp", config,
            Map.of("DATABASE_URL", "postgres://mcp:very-secret@database/mcp"),
            Map.of("database", Map.of("POSTGRES_PASSWORD", "very-secret"), "cache", Map.of()))));

    Map<String, Object> main = service(rendered, "postgres-mcp");
    assertEquals(Map.of(ManagedMcpStack.OWNER_LABEL, ManagedMcpStack.PROJECT,
        ManagedMcpStack.SERVER_ID_LABEL, "mcp-1"), main.get("labels"));
    assertEquals(ManagedMcpStack.NETWORK, network(rendered).get("name"));
    assertEquals(Map.of("condition", "service_healthy"),
        dependsOn(main).get("postgres-mcp-database"));
    assertEquals(Map.of("condition", "service_started"), dependsOn(main).get("postgres-mcp-cache"));
    assertEquals(List.of("postgres-mcp-database-data:/var/lib/postgresql/data"),
        service(rendered, "postgres-mcp-database").get("volumes"));

    // no secret reaches the file: every value is a Compose variable resolved from the process
    assertFalse(rendered.yaml().contains("very-secret"), rendered.yaml());
    assertTrue(environment(main).get("DATABASE_URL").toString().startsWith("${MC_MCP_"));
    assertTrue(environment(main).get("DATABASE_URL").toString().endsWith(":-}"));
    assertTrue(rendered.processEnvironment().containsValue("very-secret"));

    assertEquals(List.of("postgres-mcp", "postgres-mcp-database", "postgres-mcp-cache"),
        rendered.serviceNames().get("mcp-1"));
    assertEquals(List.of("mission-control-mcp-postgres-mcp-database-data"),
        rendered.volumeNames().get("mcp-1"));
  }

  @Test
  void entrypointAndCommandSurviveTheQuotesInsideThem() {
    StoredConfig config = new StoredConfig(
        "sse", null, "example/mcp:latest", null,
        List.of("python", "-c"), List.of("uvicorn.run(host='0.0.0.0')"), null, List.of(),
        1103, null, "/sse", null, List.of(), List.of(), List.of(), null, List.of());

    ComposeStackRenderer.Rendered rendered = new ComposeStackRenderer().render(
        List.of(new ComposeStackRenderer.Deployment(
            "mcp-1", "postgres-mcp", config, Map.of(), Map.of())));

    Map<String, Object> main = service(rendered, "postgres-mcp");
    assertEquals(List.of("python", "-c"), main.get("entrypoint"));
    assertEquals(List.of("uvicorn.run(host='0.0.0.0')"), main.get("command"));
  }

  @Test
  void anEmptyHostRendersAValidButEmptyComposeFile() {
    // Compose refuses a file with a bare 'services:' key and nothing under it
    ComposeStackRenderer.Rendered rendered = new ComposeStackRenderer().render(List.of());

    assertEquals(Map.of(), parse(rendered).get("services"));
    assertFalse(parse(rendered).containsKey("volumes"), "no volume block until one is declared");
    assertTrue(rendered.processEnvironment().isEmpty());
    assertTrue(rendered.serviceNames().isEmpty());
    assertTrue(rendered.volumeNames().isEmpty());
  }

  // ── optional blocks ─────────────────────────────────────────────────────

  @Test
  void aHealthcheckIsRenderedFieldByFieldAndOmitsWhatItDoesNotCarry() {
    Map<String, Object> full = healthcheck(render(config(c -> c.healthcheck =
        new HealthcheckSpec(List.of("CMD", "true"), "5s", "3s", 3, "1s"))));

    assertEquals("5s", full.get("interval"));
    assertEquals("3s", full.get("timeout"));
    assertEquals(3, full.get("retries"));
    assertEquals("1s", full.get("start_period"));

    Map<String, Object> sparse = healthcheck(render(config(c -> c.healthcheck =
        new HealthcheckSpec(List.of("CMD-SHELL", "curl -f http://localhost:1100/mcp"),
            null, null, null, null))));

    assertEquals(List.of("CMD-SHELL", "curl -f http://localhost:1100/mcp"), sparse.get("test"));
    assertEquals(List.of("test"), List.copyOf(sparse.keySet()));
  }

  @Test
  void aServiceWithNoOptionalFieldsRendersNoneOfThoseKeys() {
    Map<String, Object> main = service(render(config(c -> { })), "postgres-mcp");

    assertEquals(List.of("image", "restart", "labels", "networks", "expose"),
        List.copyOf(main.keySet()));
  }

  @Test
  void aPlatformAndAnEntrypointAreRenderedWhenPresent() {
    Map<String, Object> main = service(render(config(c -> {
      c.platform = "linux/arm64";
      c.entrypoint = List.of("python", "-c");
      c.command = List.of("print('hi')");
    })), "postgres-mcp");

    assertEquals("linux/arm64", main.get("platform"));
    assertEquals(List.of("python", "-c"), main.get("entrypoint"));
    assertEquals(List.of("print('hi')"), main.get("command"));
  }

  @Test
  void aVolumeKeyLongerThanDockerAllowsIsTruncated() {
    // Compose/Docker cap a volume name at 63 characters; an over-long logical name would
    // otherwise be rejected by the daemon at create time
    String logicalName = "d".repeat(80);
    ComposeStackRenderer.Rendered rendered =
        render(config(c -> c.volumes = List.of(new VolumeSpec(logicalName, "/data"))));

    String volume = rendered.volumeNames().values().iterator().next().getFirst();
    assertTrue(volume.startsWith(ManagedMcpStack.PROJECT + "-"), volume);
    assertTrue(volume.length() <= ManagedMcpStack.PROJECT.length() + 1 + 63, volume);
  }

  // ── reading the document back ───────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(ComposeStackRenderer.Rendered rendered) {
    return (Map<String, Object>) new Yaml().load(rendered.yaml());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> service(ComposeStackRenderer.Rendered rendered, String key) {
    return (Map<String, Object>) ((Map<String, Object>) parse(rendered).get("services")).get(key);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> network(ComposeStackRenderer.Rendered rendered) {
    return (Map<String, Object>) ((Map<String, Object>) parse(rendered).get("networks")).get("mcp");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> dependsOn(Map<String, Object> service) {
    return (Map<String, Object>) service.get("depends_on");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> environment(Map<String, Object> service) {
    return (Map<String, Object>) service.get("environment");
  }

  private static Map<String, Object> healthcheck(ComposeStackRenderer.Rendered rendered) {
    return environmentless(service(rendered, "postgres-mcp").get("healthcheck"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> environmentless(Object node) {
    return (Map<String, Object>) node;
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
