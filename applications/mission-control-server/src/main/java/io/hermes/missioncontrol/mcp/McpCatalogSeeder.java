package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default catalog entries a fresh install ships with, and the repairs for entries an
 * earlier version seeded with a definition that cannot work.
 *
 * <p>Split out of {@link McpRegistryService} because this is data, not behaviour: several
 * screens of image references, ports and boot commands whose only shared logic is "insert
 * once, keyed by seed key". Each repair is guarded on the exact broken shape, so an entry
 * the operator has since customized is left untouched rather than silently reverted.
 */
class McpCatalogSeeder {

  private static final Logger log = LoggerFactory.getLogger(McpCatalogSeeder.class);

  static final String SEED_META = "default-seed-version";
  static final String SEED_VERSION = "1";
  static final String SEED_REPAIR_META = "seed-repair-version";
  static final String SEED_REPAIR_VERSION = "1";

  private static final String POSTGRES_IMAGE = "openmcpserver/mcp-postgres:latest";

  /**
   * The Postgres MCP image hardcodes port 8080 and never passes TransportSecuritySettings, so
   * the MCP SDK's default loopback-only Host allow-list rejects every request addressed to the
   * Compose service name with 421. Neither is reachable through configuration — the SDK has no
   * environment override — so the entrypoint boots the server module itself instead.
   *
   * <p>Constraints: one line with no control characters (the validator rejects them), no {@code
   * $} (Compose interpolates the rendered YAML), and single quotes only, which the renderer
   * escapes as {@code ''}. {@code sse_app()} is called after the settings are relaxed so the
   * transport is built from them.
   */
  private static final String POSTGRES_BOOT = "import os,uvicorn,server;"
      + "from mcp.server.transport_security import TransportSecuritySettings as T;"
      + "server.mcp.settings.transport_security=T(enable_dns_rebinding_protection=False);"
      + "uvicorn.run(server.mcp.sse_app(),host='0.0.0.0',port=int(os.environ.get('PORT','1103')))";

  private final McpServerRepository repository;
  private final McpConfigStore configs;

  McpCatalogSeeder(McpServerRepository repository, McpConfigStore configs) {
    this.repository = repository;
    this.configs = configs;
  }

  void seedDefaults() {
    createSeed("playwright", "Playwright", "playwright", new McpServerRequest(
        "Playwright", "Browser automation through Playwright MCP", "managed", HostService.LOCAL_HOST_ID,
        "http", null, "mcp/playwright:latest", null, List.of(),
        List.of("--port", "1100", "--host", "0.0.0.0", "--allowed-hosts", "*"),
        null, List.of(), 1100, null, "/mcp", null,
        List.of(new ConfigValueInput("PLAYWRIGHT_MCP_SHARED_BROWSER_CONTEXT", "0", false, false)),
        List.of(), List.of(), null, List.of()));

    createSeed("context7", "Context7", "context7", new McpServerRequest(
        "Context7", "Up-to-date library documentation through Context7", "managed", HostService.LOCAL_HOST_ID,
        "http", null, "mcp/context7:latest", null, List.of(), List.of(), null, List.of(),
        1101, null, "/mcp", null,
        List.of(new ConfigValueInput("MCP_TRANSPORT", "http", false, false),
            new ConfigValueInput("PORT", "1101", false, false),
            new ConfigValueInput("NODE_ENV", "production", false, false)),
        List.of(), List.of(), null, List.of()));

    createSeed("sequential-thinking", "Sequential Thinking", "sequentialthinking", new McpServerRequest(
        "Sequential Thinking", "Structured reasoning MCP server", "managed", HostService.LOCAL_HOST_ID,
        "http", null, "mcp/sequentialthinking:latest", null,
        List.of("npx", "-y", "supergateway"),
        List.of("--stdio", "node dist/index.js", "--outputTransport", "streamableHttp",
            "--streamableHttpPath", "/mcp", "--stateful", "--sessionTimeout", "600000",
            "--port", "1102"),
        null, List.of(), 1102, null, "/mcp", null, List.of(), List.of(), List.of(), null, List.of()));

    String password = randomPassword();
    HealthcheckSpec pgHealth = new HealthcheckSpec(
        List.of("CMD-SHELL", "pg_isready -U mcp -d mcp"), "5s", "3s", 20, null);
    SupportServiceRequest postgres = new SupportServiceRequest(
        "database", "postgres:16-alpine", null, List.of(), List.of(),
        List.of(new ConfigValueInput("POSTGRES_USER", "mcp", false, false),
            new ConfigValueInput("POSTGRES_PASSWORD", password, true, false),
            new ConfigValueInput("POSTGRES_DB", "mcp", false, false)),
        List.of(new VolumeSpec("data", "/var/lib/postgresql/data")), pgHealth);
    createSeed("postgres", "Postgres MCP", "postgres-mcp", new McpServerRequest(
        "Postgres MCP", "Read-only PostgreSQL MCP server with a private database", "managed",
        HostService.LOCAL_HOST_ID, "sse", null, POSTGRES_IMAGE, null,
        List.of("python", "-c"), List.of(POSTGRES_BOOT), null, List.of(), 1103, null, "/sse", null,
        List.of(new ConfigValueInput("PORT", "1103", false, false),
            new ConfigValueInput("DATABASE_URL",
                "postgres://mcp:" + password + "@postgres-mcp-database:5432/mcp", true, false),
            new ConfigValueInput("POSTGRES_READ_ONLY", "true", false, false)),
        List.of(), List.of(), null, List.of(postgres)));
  }

  /**
   * Rewrites default catalog entries that an earlier version seeded with a definition that
   * cannot work. Each repair is guarded on the exact broken shape, so an entry the operator has
   * since customized is left untouched rather than silently reverted.
   */
  void repairSeeds() {
    repository.findBySeedKey("postgres").ifPresent(row -> {
      StoredConfig config = configs.read(row);
      boolean untouched = config.entrypoint().isEmpty() && config.command().isEmpty()
          && POSTGRES_IMAGE.equals(config.image())
          && Integer.valueOf(1103).equals(config.internalPort());
      if (!untouched) {
        log.debug("leaving the seeded Postgres MCP entry alone: already correct or customized");
        return;
      }
      // Everything but the boot command is carried over verbatim — in particular the already
      // encrypted DATABASE_URL envelope, which cannot be rebuilt from here.
      StoredConfig repaired = new StoredConfig(
          config.transport(), config.url(), config.image(), config.platform(),
          List.of("python", "-c"), List.of(POSTGRES_BOOT), config.stdioCommand(), config.args(),
          config.internalPort(), config.publishedPort(), config.path(), config.crossHostUrl(),
          config.environment(), config.headers(), config.volumes(), config.healthcheck(),
          config.supportServices());
      repository.updateDefinition(row.id(), row.name(), row.description(), configs.write(repaired),
          row.revision() + 1, row.appliedRevision(), row.operationState());
      log.info("repaired the seeded Postgres MCP entry: the image ignores PORT and rejects the "
          + "Compose service name as a Host header, so it is now booted through an explicit "
          + "entrypoint");
    });
  }

  private void createSeed(
      String seedKey, String expectedName, String serviceKey, McpServerRequest request) {
    if (repository.findBySeedKey(seedKey).isPresent()) return;
    Validated validated = McpRequestValidator.validate(request);
    if (repository.nameExists(expectedName, null)) {
      log.warn("not seeding default MCP {} because that display name is already in use", expectedName);
      return;
    }
    String id = "mcp-seed-" + seedKey;
    long now = System.currentTimeMillis();
    repository.insert(new ServerRow(id, validated.name(), validated.description(), "managed",
        HostService.LOCAL_HOST_ID, serviceKey, configs.write(configs.store(validated, null)),
        "stopped", "missing", "provisioning", null, 1, 0, seedKey, null, null, null, null, now, now));
  }

  private static String randomPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
