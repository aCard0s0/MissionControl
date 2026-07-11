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
  void emptyStackIsStillValidStructuredYaml() {
    String yaml = new ComposeStackRenderer().render(List.of()).yaml();
    assertTrue(yaml.startsWith("services: {}"));
    assertFalse(yaml.contains("project_name: mcp"));
  }
}
