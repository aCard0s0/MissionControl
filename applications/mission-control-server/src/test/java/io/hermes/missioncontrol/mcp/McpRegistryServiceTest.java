package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class McpRegistryServiceTest {

  private static final AppProperties PROPS =
      new AppProperties("", "unix:///var/run/docker.sock", "hermes/agent", "hermes", "test", true);

  private SqliteTestDatabase database;
  private McpServerRepository repository;
  private AgentMcpLinkRepository links;
  private McpRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    JdbcTemplate jdbc = database.jdbc();
    repository = new McpServerRepository(jdbc);
    links = new AgentMcpLinkRepository(jdbc);
    service = new McpRegistryService(repository, new RetainedResourceRepository(jdbc), links,
        mock(HostService.class), mock(DockerGateway.class),
        new SecretCipher("test-secret", "", false), new ObjectMapper(), mock(ComposeStackManager.class),
        PROPS);
  }

  // one @AfterEach, not two: JUnit 5 does not order sibling teardown methods, and the
  // service has to release its executor before the database goes away
  @AfterEach
  void tearDown() throws Exception {
    service.close();
    database.close();
  }

  @Test
  void startupTouchesNothingWhenReconciliationIsOff() throws Exception {
    // a context test boots the whole application with no daemon behind it, so this
    // switch has to keep startup from seeding rows or creating real resources
    HostService hosts = mock(HostService.class);
    DockerGateway docker = mock(DockerGateway.class);
    AppProperties noReconcile =
        new AppProperties("", "unix:///var/run/docker.sock", "hermes/agent", "hermes", "test", false);

    McpRegistryService quiet = new McpRegistryService(repository,
        new RetainedResourceRepository(database.jdbc()), links, hosts, docker,
        new SecretCipher("test-secret", "", false), new ObjectMapper(),
        mock(ComposeStackManager.class), noReconcile);
    try {
      quiet.initialize();

      verifyNoInteractions(hosts);
      verifyNoInteractions(docker);
      assertTrue(repository.findAll().isEmpty());
    } finally {
      quiet.close();
    }
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

  @Test
  void theSeededPostgresServerBootsThroughAnExplicitEntrypoint() {
    service.seedDefaults();

    StoredConfig config = configOf(postgresRow());
    assertEquals(List.of("python", "-c"), config.entrypoint());
    assertEquals(1, config.command().size());
    String boot = config.command().getFirst();
    assertTrue(boot.contains("enable_dns_rebinding_protection=False"));
    assertTrue(boot.contains("PORT"));
    // Compose interpolates the rendered YAML and the validator rejects control characters.
    assertFalse(boot.contains("$"));
    assertFalse(boot.contains("\n"));
    assertEquals(1103, config.internalPort());
    assertEquals("/sse", config.path());
  }

  @Test
  void repairRewritesTheBrokenPostgresSeedAndKeepsItsSecrets() {
    service.seedDefaults();
    String id = postgresRow().id();
    Map<String, String> environmentBefore = service.materializedEnvironment(id);
    breakPostgresSeed(config -> config.image());

    service.repairSeeds();

    ServerRow repaired = postgresRow();
    StoredConfig config = configOf(repaired);
    assertEquals(List.of("python", "-c"), config.entrypoint());
    assertEquals(1, config.command().size());
    assertEquals(2, repaired.revision());
    assertEquals(environmentBefore, service.materializedEnvironment(id));
    assertFalse(repaired.configJson().contains("@postgres-mcp-database"));
    assertEquals(List.of("database"), config.supportServices().stream()
        .map(StoredSupportService::name).toList());
  }

  @Test
  void repairLeavesACustomizedPostgresSeedAlone() {
    service.seedDefaults();
    breakPostgresSeed(config -> "example/my-own-postgres-mcp:latest");

    service.repairSeeds();

    assertTrue(configOf(postgresRow()).entrypoint().isEmpty());
  }

  @Test
  void repairDoesNothingToAnAlreadyCorrectSeed() {
    service.seedDefaults();
    long revision = postgresRow().revision();

    service.repairSeeds();
    service.repairSeeds();

    assertEquals(revision, postgresRow().revision());
  }


  private ServerRow postgresRow() {
    return repository.findBySeedKey("postgres").orElseThrow();
  }

  private StoredConfig configOf(ServerRow row) {
    try {
      return new ObjectMapper().readValue(row.configJson(), StoredConfig.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Restores the pre-fix shape, keeping the encrypted environment a real seed produced. */
  private void breakPostgresSeed(Function<StoredConfig, String> image) {
    ServerRow row = postgresRow();
    StoredConfig config = configOf(row);
    StoredConfig broken = new StoredConfig(
        config.transport(), config.url(), image.apply(config), config.platform(),
        List.of(), List.of(), config.stdioCommand(), config.args(), config.internalPort(),
        config.publishedPort(), config.path(), config.crossHostUrl(), config.environment(),
        config.headers(), config.volumes(), config.healthcheck(), config.supportServices());
    try {
      repository.updateDefinition(row.id(), row.name(), row.description(),
          new ObjectMapper().writeValueAsString(broken), row.revision(), row.appliedRevision(),
          row.operationState());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static McpServerRequest external(String name, String secret) {
    return new McpServerRequest(name, "desc", "external", null, "http",
        "https://example.test/mcp", null, null, List.of(), List.of(), null, List.of(),
        null, null, null, null, List.of(),
        List.of(new ConfigValueInput("Authorization", secret, true, false)),
        List.of(), null, List.of());
  }
}
