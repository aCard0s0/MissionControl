package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hermes.SecretCipher;
import io.hermes.missioncontrol.hosts.HostService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class McpRegistryServiceTest {

  private Connection connection;
  private McpServerRepository repository;
  private AgentMcpLinkRepository links;
  private McpRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    repository = new McpServerRepository(jdbc);
    links = new AgentMcpLinkRepository(jdbc);
    service = new McpRegistryService(repository, new RetainedResourceRepository(jdbc), links,
        mock(HostService.class), mock(DockerGateway.class),
        new SecretCipher("test-secret", "", false), new ObjectMapper(), mock(ComposeStackManager.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    service.close();
    connection.close();
  }

  @Test
  void externalSecretsAreEncryptedRedactedAndPreservedOnBlankUpdate() {
    McpServerDto created = service.create(external("Remote docs", "super-secret"));

    assertNull(created.headers().getFirst().value());
    assertTrue(created.headers().getFirst().set());
    assertTrue(repository.findById(created.id()).orElseThrow().configJson().contains("enc:v1:"));
    assertFalse(repository.findById(created.id()).orElseThrow().configJson().contains("super-secret"));
    assertEquals(Map.of("Authorization", "super-secret"), service.materializedHeaders(created.id()));

    McpServerDto updated = service.update(created.id(), external("Renamed docs", ""));
    assertEquals("Renamed docs", updated.name());
    assertEquals(2, updated.revision());
    assertEquals(Map.of("Authorization", "super-secret"), service.materializedHeaders(created.id()));
  }

  @Test
  void namesAreUniqueIgnoringCase() {
    service.create(external("Remote docs", "one"));
    assertThrows(RuntimeException.class, () -> service.create(external("remote DOCS", "two")));
  }

  @Test
  void aNewSecretCannotBeSavedWithoutAValue() {
    assertThrows(IllegalArgumentException.class, () -> service.create(external("Remote docs", "")));
  }

  @Test
  void agentLinksRoundTripAndCanBeFoundByCatalogServer() {
    String serverId = service.create(external("Remote docs", "secret")).id();
    links.upsert(new AgentMcpLink("dh-local", "container", "default", "docs", serverId, 1, 0, 0));

    assertEquals(serverId, links.find("dh-local", "container", "default", "docs").orElseThrow().serverId());
    assertEquals(1, links.findByServer(serverId).size());
    assertThrows(RuntimeException.class, () -> service.delete(serverId));

    links.delete("dh-local", "container", "default", "docs");
    service.delete(serverId);
    assertTrue(links.findByServer(serverId).isEmpty());
  }

  private static McpServerRequest external(String name, String secret) {
    return new McpServerRequest(name, "desc", "external", null, "http",
        "https://example.test/mcp", null, null, List.of(), List.of(), null, List.of(),
        null, null, null, null, List.of(),
        List.of(new ConfigValueInput("Authorization", secret, true, false)),
        List.of(), null, List.of());
  }
}
