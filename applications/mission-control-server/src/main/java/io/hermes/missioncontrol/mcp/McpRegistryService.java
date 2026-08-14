package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.AppProperties;
import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.hermes.SecretCipher;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.ComposeStackRenderer.Deployment;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.web.ResourceConflictException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Global MCP catalog and managed Compose lifecycle. */
@Service
public class McpRegistryService {

  private static final Logger log = LoggerFactory.getLogger(McpRegistryService.class);
  private static final String SEED_META = "default-seed-version";
  private static final String SEED_VERSION = "1";
  private static final String SEED_REPAIR_META = "seed-repair-version";
  private static final String SEED_REPAIR_VERSION = "1";
  private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
  private static final Pattern CONTAINER_ID = Pattern.compile("/containers/([0-9a-f]{64})");
  private static final String MCP_INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,"
      + "\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
      + "\"capabilities\":{},\"clientInfo\":{\"name\":\"mission-control\",\"version\":\"1\"}}}";

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
  private final RetainedResourceRepository retained;
  private final AgentMcpLinkRepository links;
  private final HostService hosts;
  private final DockerGateway docker;
  private final SecretCipher cipher;
  private final ObjectMapper json;
  private final ComposeStackRenderer renderer;
  private final ComposeStackManager compose;
  private final ExecutorService operations;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  private final String dataMode;

  @Autowired
  public McpRegistryService(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      SecretCipher cipher,
      ObjectMapper json,
      ComposeStackManager compose,
      AppProperties props) {
    this(repository, retained, links, hosts, docker, cipher, json, compose, props,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Test seam: a caller-supplied executor. Passing a same-thread executor makes the
   * compose lifecycle — desired state, {@code operation_state}, {@code applied_revision},
   * the recorded failure — observable, which an async task offers no way to await.
   */
  McpRegistryService(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      SecretCipher cipher,
      ObjectMapper json,
      ComposeStackManager compose,
      AppProperties props,
      ExecutorService operations) {
    this.operations = operations;
    this.repository = repository;
    this.retained = retained;
    this.links = links;
    this.hosts = hosts;
    this.docker = docker;
    this.cipher = cipher;
    this.json = json;
    this.compose = compose;
    this.dataMode = props.dataMode();
    this.renderer = new ComposeStackRenderer();
  }

  public List<McpServerDto> list() {
    return repository.findAll().stream().map(this::refreshRuntime).map(this::toDto).toList();
  }

  /** Public integration point used by Agent and template services. */
  public McpServerDto require(String id) {
    return toDto(refreshRuntime(requireRow(id)));
  }

  /** Decrypts a catalog environment only inside the backend trust boundary. */
  public Map<String, String> materializedEnvironment(String id) {
    return materialize(read(requireRow(id)).environment());
  }

  /** Decrypts connection headers only inside the backend trust boundary. */
  public Map<String, String> materializedHeaders(String id) {
    return materialize(read(requireRow(id)).headers());
  }

  public String sameHostConnectionUrl(String id) {
    ServerRow row = requireRow(id);
    StoredConfig config = read(row);
    if ("managed".equals(row.kind())) return connectionUrl(row, config);
    return "external".equals(row.kind()) ? config.url() : null;
  }

  public McpServerDto create(McpServerRequest request) {
    Validated validated = McpRequestValidator.validate(request);
    hostsForManaged(validated);
    if (repository.nameExists(validated.name(), null)) {
      throw new ResourceConflictException("an MCP server named '" + validated.name() + "' already exists");
    }
    String id = "mcp-" + UUID.randomUUID().toString().substring(0, 12);
    String serviceKey = "managed".equals(validated.kind()) ? "mcp-" + id.substring(4, 12) : null;
    StoredConfig config = store(validated, null);
    long now = System.currentTimeMillis();
    boolean managed = "managed".equals(validated.kind());
    ServerRow row = new ServerRow(id, validated.name(), validated.description(), validated.kind(),
        validated.hostId(), serviceKey, write(config), "stopped", managed ? "missing" : "unavailable",
        managed ? "provisioning" : "idle", null, 1, managed ? 0 : 1, null,
        null, null, null, null, now, now);
    repository.insert(row);
    if (managed) submit(id, () -> provisionStopped(id));
    return require(id);
  }

  public McpServerDto update(String id, McpServerRequest request) {
    ServerRow existing = requireRow(id);
    ensureIdle(existing);
    Validated validated = McpRequestValidator.validate(request);
    if (!existing.kind().equals(validated.kind())) {
      throw new IllegalArgumentException("kind is immutable; create a new catalog record instead");
    }
    if (!Objects.equals(existing.hostId(), validated.hostId())) {
      throw new IllegalArgumentException("hostId is immutable; duplicate the server onto another host instead");
    }
    hostsForManaged(validated);
    if (repository.nameExists(validated.name(), id)) {
      throw new ResourceConflictException("an MCP server named '" + validated.name() + "' already exists");
    }
    StoredConfig config = store(validated, read(existing));
    long revision = existing.revision() + 1;
    boolean recreateStopped = "managed".equals(existing.kind())
        && "stopped".equals(existing.desiredState());
    long applied = "managed".equals(existing.kind()) ? existing.appliedRevision() : revision;
    repository.updateDefinition(id, validated.name(), validated.description(), write(config), revision,
        applied, recreateStopped ? "applying" : "idle");
    if (recreateStopped) submit(id, () -> provisionStopped(id));
    return require(id);
  }

  public McpServerDto start(String id) {
    ServerRow row = requireManagedIdle(id);
    repository.beginOperation(id, "running", "starting");
    submit(id, () -> runStart(id, false));
    return require(id);
  }

  public McpServerDto stop(String id) {
    ServerRow row = requireManagedIdle(id);
    repository.beginOperation(id, "stopped", "stopping");
    submit(id, () -> runStop(id));
    return require(id);
  }

  public McpServerDto apply(String id) {
    ServerRow row = requireManagedIdle(id);
    repository.beginOperation(id, row.desiredState(), "applying");
    submit(id, () -> {
      if ("running".equals(requireRow(id).desiredState())) runStart(id, true);
      else provisionStopped(id);
    });
    return require(id);
  }

  /**
   * Starts managed deletion asynchronously. Linked Agent entries must first be
   * disabled and unlinked by the Agent integration layer, preventing silent loss.
   */
  public McpServerDto delete(String id) {
    ServerRow row = requireRow(id);
    ensureIdle(row);
    if (!links.findByServer(id).isEmpty()) {
      throw new ResourceConflictException(
          "the MCP server is still linked to one or more Agents; disable and unlink them, then retry");
    }
    if (!"managed".equals(row.kind())) {
      repository.delete(id);
      return toDto(row);
    }
    repository.beginOperation(id, "stopped", "deleting");
    submit(id, () -> runDelete(id));
    return require(id);
  }

  public McpServerDto check(String id) {
    ServerRow row = requireRow(id);
    if ("stdio".equals(row.kind())) {
      throw new IllegalArgumentException("reachability checks do not apply to stdio MCP servers");
    }
    if ("managed".equals(row.kind())) return checkManaged(row);
    StoredConfig config = read(row);
    long started = System.nanoTime();
    long checkedAt = System.currentTimeMillis();
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.url()))
          .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody());
      for (Map.Entry<String, String> header : materialize(config.headers()).entrySet()) {
        request.header(header.getKey(), header.getValue());
      }
      HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
      long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
      repository.updateCheck(id, "connected", null, checkedAt, latency);
      log.debug("external MCP {} responded with HTTP {}", id, response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      repository.updateCheck(id, "error", "reachability check interrupted", checkedAt, null);
    } catch (Exception e) {
      repository.updateCheck(id, "error", e.getMessage(), checkedAt, null);
    }
    return require(id);
  }

  /**
   * Probes a managed server exactly the way an Agent reaches it — by Compose service name, over
   * the MCP network. Addressing it any other way (its own loopback, a published port) would
   * accept images whose MCP transport rejects the service name as a Host header, which is the
   * failure this check exists to surface.
   */
  private McpServerDto checkManaged(ServerRow row) {
    String id = row.id();
    StoredConfig config = read(row);
    long checkedAt = System.currentTimeMillis();
    if (!"running".equals(row.runtimeState())) {
      repository.updateCheck(id, "error", "server is not running", checkedAt, null);
      return require(id);
    }
    String target;
    try {
      target = probeTarget(row, config);
    } catch (RuntimeException unreachable) {
      repository.updateCheck(id, "error", unreachable.getMessage(), checkedAt, null);
      return require(id);
    }

    long started = System.nanoTime();
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
          .timeout(PROBE_TIMEOUT)
          .header("Accept", "application/json, text/event-stream");
      if ("sse".equals(config.transport())) {
        // The legacy SSE transport opens its stream on GET and answers POST with 405.
        request.GET();
      } else {
        request.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MCP_INITIALIZE));
      }
      for (Map.Entry<String, String> header : materialize(config.headers()).entrySet()) {
        request.header(header.getKey(), header.getValue());
      }
      HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = http
          .sendAsync(request.build(), HttpResponse.BodyHandlers.ofPublisher())
          .get(PROBE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
      // Both transports answer with an event stream that stays open, so the probe reads the
      // response head and drops the subscription instead of waiting for a body that never ends.
      response.body().subscribe(new CancellingSubscriber());

      long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
      String contentType = response.headers().firstValue("content-type").orElse("");
      String failure = probeFailure(response.statusCode(), contentType, config, target);
      if (failure == null) repository.updateCheck(id, "connected", null, checkedAt, latency);
      else repository.updateCheck(id, "error", failure, checkedAt, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      repository.updateCheck(id, "error", "reachability check interrupted", checkedAt, null);
    } catch (Exception e) {
      repository.updateCheck(id, "error", target + " — " + rootMessage(e), checkedAt, null);
    }
    return require(id);
  }

  /** Null when the response is a healthy MCP endpoint, otherwise the operator-facing reason. */
  static String probeFailure(
      int status, String contentType, StoredConfig config, String target) {
    if (status == 421) {
      return "HTTP 421 from " + target + " — the image rejects the Compose service name as a Host "
          + "header. Its MCP transport accepts loopback hosts only, so no Agent can reach it.";
    }
    if (status == 404 || status == 405) {
      return "HTTP " + status + " from " + target + " — no MCP endpoint on that path.";
    }
    if (status / 100 != 2) return "HTTP " + status + " from " + target;
    if ("sse".equals(config.transport()) && !contentType.startsWith("text/event-stream")) {
      return target + " answered with '" + contentType + "' rather than an SSE stream.";
    }
    return null;
  }

  private String probeTarget(ServerRow row, StoredConfig config) {
    if (HostService.LOCAL_HOST_ID.equals(row.hostId())) {
      attachMcpNetwork(row.hostId());
      return connectionUrl(row, config);
    }
    String crossHost = config.crossHostUrl();
    if (crossHost == null || crossHost.isBlank()) {
      throw new IllegalStateException(
          "a managed server on a remote Docker host can only be checked through a cross-host URL");
    }
    return crossHost;
  }

  /** Joins the MCP network so service names resolve. Idempotent, and cheap once attached. */
  private void attachMcpNetwork(String hostId) {
    String container = ownNetworkContainerId();
    if (container == null) {
      throw new IllegalStateException(
          "cannot reach the MCP network: Mission Control is not running inside a container");
    }
    docker.connectNetwork(hosts.urlOf(hostId), container, ComposeStackRenderer.NETWORK);
  }

  /**
   * The container owning this process' network namespace. Docker bind-mounts the network files
   * (/etc/hosts, /etc/resolv.conf) out of that container's directory, so its id is the one that
   * shows up in mountinfo — this container normally, and the namespace owner when the deployment
   * shares one, as the Tailscale compose file does with {@code network_mode: service:tailscale}.
   * That is exactly the container a network has to be attached to for this process to use it.
   */
  private static String ownNetworkContainerId() {
    try {
      return containerIdFrom(Files.readAllLines(Path.of("/proc/self/mountinfo")));
    } catch (Exception notContainerized) {
      return null;
    }
  }

  /**
   * The container id in the first mountinfo line that carries one, or null when this
   * process is not in a container. Split out from the file read so the rule that decides
   * <em>which</em> line wins is reachable without a container to run inside.
   */
  static String containerIdFrom(List<String> mountinfoLines) {
    for (String line : mountinfoLines) {
      Matcher matcher = CONTAINER_ID.matcher(line);
      if (matcher.find()) return matcher.group(1);
    }
    return null;
  }

  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  /** Reads the response head and releases the connection without draining the stream. */
  private static final class CancellingSubscriber implements Flow.Subscriber<List<ByteBuffer>> {
    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
    @Override public void onNext(List<ByteBuffer> item) { }
    @Override public void onError(Throwable throwable) { }
    @Override public void onComplete() { }
  }

  public List<LogLineDto> logs(String id, int tail) {
    ServerRow row = requireRow(id);
    if (!"managed".equals(row.kind())) {
      throw new IllegalArgumentException("logs are available only for managed MCP servers");
    }
    StoredConfig config = read(row);
    List<String> serviceNames = new ArrayList<>();
    serviceNames.add(row.serviceKey());
    for (StoredSupportService support : config.supportServices()) {
      serviceNames.add(ComposeStackRenderer.supportKey(row.serviceKey(), support.name()));
    }
    List<LogLineDto> result = new ArrayList<>();
    String url = hosts.urlOf(row.hostId());
    for (String serviceName : serviceNames) {
      String containerId = compose.serviceContainerId(row.hostId(), serviceName);
      if (containerId == null) continue;
      for (LogLineDto line : docker.logs(url, containerId, Math.min(Math.max(tail, 1), 500))) {
        result.add(new LogLineDto(line.ts(), line.level(), serviceName, line.msg()));
      }
    }
    result.sort(Comparator.comparingLong(LogLineDto::ts));
    return result;
  }

  public List<RetainedResourceDto> retainedResources() {
    return retained.findAll();
  }

  public RetainedResourceDto retainedResource(String id) {
    return retained.require(id);
  }

  public void purgeRetainedResource(String id) {
    RetainedResourceDto resource = retained.require(id);
    compose.purgeVolume(resource.hostId(), resource.name());
    retained.delete(id);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    // Mock mode is intentionally side-effect free; its catalog/lifecycle is
    // simulated in the Angular store and must never pull or create real images.
    if ("mock".equalsIgnoreCase(dataMode)) return;
    hosts.seedLocalHost();
    boolean seeded = repository.meta(SEED_META).map(SEED_VERSION::equals).orElse(false);
    if (!seeded) {
      seedDefaults();
      repository.putMeta(SEED_META, SEED_VERSION);
    }
    // Seeding only ever inserts, so a corrected default never reaches a catalog that was
    // seeded by an earlier version. Repair runs before the reconcile loop below, which then
    // applies the rewritten definition as part of its normal startup pass.
    if (!repository.meta(SEED_REPAIR_META).map(SEED_REPAIR_VERSION::equals).orElse(false)) {
      repairSeeds();
      repository.putMeta(SEED_REPAIR_META, SEED_REPAIR_VERSION);
    }
    // Reconcile persisted desired state after a dashboard restart. Per-host locks
    // serialize this with any seed provisioning already queued above.
    for (ServerRow row : repository.findAll()) {
      if (!"managed".equals(row.kind())) continue;
      if ("deleting".equals(row.operationState())) {
        submit(row.id(), () -> runDelete(row.id()));
      } else {
        repository.beginOperation(row.id(), row.desiredState(), "reconciling");
        submit(row.id(), () -> {
          if ("running".equals(requireRow(row.id()).desiredState())) runStart(row.id(), false);
          else provisionStopped(row.id());
        });
      }
    }
  }

  @PreDestroy
  void close() {
    operations.shutdownNow();
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
      StoredConfig config = read(row);
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
      repository.updateDefinition(row.id(), row.name(), row.description(), write(repaired),
          row.revision() + 1, row.appliedRevision(), row.operationState());
      log.info("repaired the seeded Postgres MCP entry: the image ignores PORT and rejects the "
          + "Compose service name as a Host header, so it is now booted through an explicit "
          + "entrypoint");
    });
  }

  private void createSeed(String seedKey, String expectedName, String serviceKey, McpServerRequest request) {
    if (repository.findBySeedKey(seedKey).isPresent()) return;
    Validated validated = McpRequestValidator.validate(request);
    if (repository.nameExists(expectedName, null)) {
      log.warn("not seeding default MCP {} because that display name is already in use", expectedName);
      return;
    }
    String id = "mcp-seed-" + seedKey;
    long now = System.currentTimeMillis();
    repository.insert(new ServerRow(id, validated.name(), validated.description(), "managed",
        HostService.LOCAL_HOST_ID, serviceKey, write(store(validated, null)), "stopped", "missing",
        "provisioning", null, 1, 0, seedKey, null, null, null, null, now, now));
  }

  private void provisionStopped(String id) {
    ServerRow row = requireRow(id);
    try {
      assertRecoverable(read(row));
      ComposeStackRenderer.Rendered stack = renderHost(row.hostId());
      List<String> targets = stack.serviceNames().get(row.id());
      compose.execute(row.hostId(), stack, arguments(
          "up", "--no-start", "--pull", "always", "--force-recreate", targets), COMPOSE_TIMEOUT);
      ServerRow fresh = requireRow(id);
      repository.finishOperation(id, "stopped", fresh.revision());
    } catch (Exception e) {
      fail(id, e);
    }
  }

  private void runStart(String id, boolean forceRecreate) {
    ServerRow row = requireRow(id);
    try {
      assertRecoverable(read(row));
      ComposeStackRenderer.Rendered stack = renderHost(row.hostId());
      List<String> args = new ArrayList<>(List.of("up", "--detach", "--pull", "always"));
      if (forceRecreate) args.add("--force-recreate");
      args.addAll(stack.serviceNames().get(row.id()));
      compose.execute(row.hostId(), stack, args, COMPOSE_TIMEOUT);
      ServerRow fresh = requireRow(id);
      repository.finishOperation(id, "running", fresh.revision());
    } catch (Exception e) {
      fail(id, e);
    }
  }

  private void runStop(String id) {
    ServerRow row = requireRow(id);
    try {
      ComposeStackRenderer.Rendered stack = renderHost(row.hostId());
      compose.execute(row.hostId(), stack,
          arguments("stop", "--timeout", "10", stack.serviceNames().get(row.id())),
          Duration.ofMinutes(2));
      ServerRow fresh = requireRow(id);
      repository.finishOperation(id, "stopped", fresh.appliedRevision());
    } catch (Exception e) {
      fail(id, e);
    }
  }

  private void runDelete(String id) {
    ServerRow row = requireRow(id);
    try {
      ComposeStackRenderer.Rendered stack = renderHost(row.hostId());
      List<String> volumes = stack.volumeNames().getOrDefault(row.id(), List.of());
      compose.execute(row.hostId(), stack,
          arguments("rm", "--stop", "--force", stack.serviceNames().get(row.id())),
          Duration.ofMinutes(3));
      for (String volume : volumes) retained.retain(row.id(), row.name(), row.hostId(), volume);
      repository.delete(id);
      compose.writeOnly(row.hostId(), renderHost(row.hostId()));
    } catch (Exception e) {
      fail(id, e);
    }
  }

  private ComposeStackRenderer.Rendered renderHost(String hostId) {
    List<Deployment> deployments = new ArrayList<>();
    for (ServerRow row : repository.findByHost(hostId)) {
      if (!"managed".equals(row.kind())) continue;
      StoredConfig config = read(row);
      Map<String, Map<String, String>> supportEnvironment = new LinkedHashMap<>();
      for (StoredSupportService support : config.supportServices()) {
        supportEnvironment.put(support.name(), materializeForRender(support.environment()));
      }
      deployments.add(new Deployment(row.id(), row.serviceKey(), config,
          materializeForRender(config.environment()), supportEnvironment));
    }
    return renderer.render(deployments);
  }

  private StoredConfig store(Validated value, StoredConfig existing) {
    List<StoredValue> environment = storeValues(value.environment(), existing == null ? List.of() : existing.environment());
    List<StoredValue> headers = storeValues(value.headers(), existing == null ? List.of() : existing.headers());
    Map<String, StoredSupportService> previousSupports = new LinkedHashMap<>();
    if (existing != null) for (StoredSupportService support : existing.supportServices()) previousSupports.put(support.name(), support);
    List<StoredSupportService> supports = new ArrayList<>();
    for (SupportServiceRequest support : value.supportServices()) {
      StoredSupportService previous = previousSupports.get(support.name());
      supports.add(new StoredSupportService(support.name(), support.image(), support.platform(),
          support.entrypoint(), support.command(),
          storeValues(support.environment(), previous == null ? List.of() : previous.environment()),
          support.volumes(), support.healthcheck()));
    }
    return new StoredConfig(value.transport(), value.url(), value.image(), value.platform(),
        value.entrypoint(), value.command(), value.stdioCommand(), value.args(), value.internalPort(),
        value.publishedPort(), value.path(), value.crossHostUrl(), environment, headers, value.volumes(),
        value.healthcheck(), List.copyOf(supports));
  }

  private List<StoredValue> storeValues(List<ConfigValueInput> input, List<StoredValue> existing) {
    Map<String, StoredValue> previous = new LinkedHashMap<>();
    for (StoredValue value : existing) previous.put(value.key(), value);
    List<StoredValue> result = new ArrayList<>();
    for (ConfigValueInput item : input) {
      if (item.shouldClear()) continue;
      String stored;
      if (!item.secret()) {
        stored = item.value() == null ? "" : item.value();
      } else if (item.value() != null && !item.value().isBlank()) {
        stored = cipher.encrypt(item.value());
      } else {
        StoredValue old = previous.get(item.key());
        if (old == null || !old.secret()) {
          throw new IllegalArgumentException("secret value is required: " + item.key());
        }
        stored = rotateIfRecoverable(old.value());
      }
      result.add(new StoredValue(item.key(), stored, item.secret()));
    }
    return List.copyOf(result);
  }

  private String rotateIfRecoverable(String stored) {
    if (stored == null) return null;
    try {
      return cipher.encrypt(cipher.decrypt(stored));
    } catch (RuntimeException unrecoverable) {
      // Preserve the opaque envelope so editing unrelated fields never destroys
      // it. DTO recoverable=false tells the operator it must be replaced before
      // the definition can be applied or connected.
      return stored;
    }
  }

  private Map<String, String> materialize(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      result.put(value.key(), value.value() == null ? ""
          : value.secret() ? cipher.decrypt(value.value()) : value.value());
    }
    return Map.copyOf(result);
  }

  /** Rendering one host must not let an unrelated server's stale encryption
   * key block lifecycle operations. The target is checked strictly before an
   * apply/start; non-target unrecoverable substitutions remain blank. */
  private Map<String, String> materializeForRender(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      try {
        result.put(value.key(), value.value() == null ? ""
            : value.secret() ? cipher.decrypt(value.value()) : value.value());
      } catch (RuntimeException unrecoverable) {
        result.put(value.key(), "");
      }
    }
    return Map.copyOf(result);
  }

  private void assertRecoverable(StoredConfig config) {
    assertRecoverable(config.environment());
    for (StoredSupportService support : config.supportServices()) {
      assertRecoverable(support.environment());
    }
  }

  private void assertRecoverable(List<StoredValue> values) {
    for (StoredValue value : values) {
      if (!value.secret()) continue;
      if (value.value() == null) {
        throw new IllegalStateException("secret value is not set: " + value.key());
      }
      try {
        cipher.decrypt(value.value());
      } catch (RuntimeException error) {
        throw new IllegalStateException("secret value is unrecoverable: " + value.key(), error);
      }
    }
  }

  private McpServerDto toDto(ServerRow row) {
    StoredConfig config = read(row);
    List<SupportServiceDto> supports = config.supportServices().stream()
        .map(value -> new SupportServiceDto(value.name(), value.image(), value.platform(),
            value.entrypoint(), value.command(), redact(value.environment()), value.volumes(), value.healthcheck()))
        .toList();
    return new McpServerDto(row.id(), row.name(), row.description(), row.kind(), row.hostId(), row.serviceKey(),
        config.transport(), config.url(), connectionUrl(row, config), config.image(), config.platform(),
        config.entrypoint(), config.command(), config.stdioCommand(), config.args(), config.internalPort(),
        config.publishedPort(), config.path(), config.crossHostUrl(), redact(config.environment()),
        redact(config.headers()), config.volumes(), config.healthcheck(), supports,
        row.desiredState(), row.runtimeState(), row.operationState(), row.operationError(),
        row.revision(), row.appliedRevision(), row.revision() > row.appliedRevision(),
        row.checkStatus(), row.checkError(), row.checkedAt(), row.latencyMs(), row.createdAt(), row.updatedAt());
  }

  private List<ConfigValueDto> redact(List<StoredValue> values) {
    return values.stream().map(value -> {
      if (!value.secret()) return new ConfigValueDto(value.key(), value.value(), false, true, true);
      boolean set = value.value() != null;
      boolean recoverable = false;
      if (set) {
        try {
          cipher.decrypt(value.value());
          recoverable = true;
        } catch (RuntimeException ignored) { }
      }
      return new ConfigValueDto(value.key(), null, true, set, recoverable);
    }).toList();
  }

  private String connectionUrl(ServerRow row, StoredConfig config) {
    if (!"managed".equals(row.kind())) return "external".equals(row.kind()) ? config.url() : null;
    return "http://" + row.serviceKey() + ":" + config.internalPort() + config.path();
  }

  private StoredConfig read(ServerRow row) {
    try {
      StoredConfig value = json.readValue(row.configJson(), StoredConfig.class);
      // All collections are written non-null. Defend against early/development rows.
      return new StoredConfig(value.transport(), value.url(), value.image(), value.platform(),
          list(value.entrypoint()), list(value.command()), value.stdioCommand(), list(value.args()),
          value.internalPort(), value.publishedPort(), value.path(), value.crossHostUrl(),
          list(value.environment()), list(value.headers()), list(value.volumes()), value.healthcheck(),
          list(value.supportServices()));
    } catch (Exception e) {
      throw new IllegalStateException("stored MCP configuration is unreadable", e);
    }
  }

  private String write(StoredConfig config) {
    try {
      return json.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not store MCP configuration", e);
    }
  }

  private ServerRow requireRow(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server: " + id));
  }

  private ServerRow refreshRuntime(ServerRow row) {
    if (!"managed".equals(row.kind()) || !List.of("idle", "error").contains(row.operationState())) {
      return row;
    }
    try {
      String containerId = compose.serviceContainerId(row.hostId(), row.serviceKey());
      String runtime = "missing";
      if (containerId != null) {
        runtime = docker.listContainers(hosts.urlOf(row.hostId()), row.hostId(), true).stream()
            .filter(container -> container.id().equals(containerId))
            .map(ContainerDto::status)
            .findFirst().orElse("unknown");
        if ("unhealthy".equals(runtime)) runtime = "error";
      }
      if (!runtime.equals(row.runtimeState())) {
        repository.updateRuntime(row.id(), runtime);
        return requireRow(row.id());
      }
    } catch (RuntimeException e) {
      // Inventory is best-effort; lifecycle failures are already recorded by the
      // asynchronous operation and should not make a catalog GET fail.
      log.debug("could not refresh managed MCP runtime state for {}: {}", row.id(), e.toString());
    }
    return row;
  }

  private ServerRow requireManagedIdle(String id) {
    ServerRow row = requireRow(id);
    if (!"managed".equals(row.kind())) {
      throw new IllegalArgumentException("container lifecycle applies only to managed MCP servers");
    }
    ensureIdle(row);
    return row;
  }

  private static void ensureIdle(ServerRow row) {
    if (!List.of("idle", "error").contains(row.operationState())) {
      throw new ResourceConflictException("an MCP server operation is already in progress");
    }
  }

  private void hostsForManaged(Validated value) {
    if ("managed".equals(value.kind())) hosts.urlOf(value.hostId());
  }

  private void submit(String id, Runnable operation) {
    operations.submit(() -> {
      try {
        operation.run();
      } catch (Exception e) {
        fail(id, e);
      }
    });
  }

  private void fail(String id, Exception error) {
    log.warn("managed MCP operation failed for {}: {}", id, error.toString());
    if (repository.findById(id).isPresent()) repository.failOperation(id, error.getMessage());
  }

  private static List<String> arguments(String... prefix) {
    return new ArrayList<>(List.of(prefix));
  }

  private static List<String> arguments(String first, String second, String third, List<String> suffix) {
    List<String> result = new ArrayList<>(List.of(first, second, third));
    result.addAll(suffix);
    return result;
  }

  private static List<String> arguments(
      String first, String second, String third, String fourth, String fifth, List<String> suffix) {
    List<String> result = new ArrayList<>(List.of(first, second, third, fourth, fifth));
    result.addAll(suffix);
    return result;
  }

  private static String randomPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static <T> List<T> list(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
