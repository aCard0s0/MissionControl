package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The global MCP catalog: what a record may be, and what may be done to one.
 *
 * <p>This class owns the catalog's rules — naming, immutability, single-flight operations,
 * and what may be deleted — and delegates everything those rules guard to an injected
 * collaborator. It used to build all six itself, which meant its constructor collected their
 * transitive dependencies too: a cipher, an object mapper, a Docker gateway and a compose
 * manager it never touched, present only to be handed on. Changing what one collaborator
 * needed changed this signature.
 *
 * <ul>
 *   <li>{@link McpConfigStore} — the stored envelope, its encryption and its redaction
 *   <li>{@link McpServerDtoMapper} — a row rendered for the API
 *   <li>{@link McpComposeLifecycle} — rendering and running the host's Compose stack
 *   <li>{@link McpHealthProbe} — whether a server actually answers
 *   <li>{@link McpLogReader} — the log tail across a record's services
 * </ul>
 *
 * <p>What happens once at boot — seeding, repairing a bad default, reconciling every record
 * back to its desired state — is {@link McpStartupReconciler}'s. It was here, which made
 * ordering against the rest of the application's startup this class's problem as well.
 */
@Service
public class McpRegistryService {

  private final McpServerRepository repository;
  private final RetainedResourceRepository retained;
  private final AgentMcpLinkRepository links;
  private final HostService hosts;
  private final McpConfigStore configs;
  private final McpServerDtoMapper mapper;
  private final McpComposeLifecycle lifecycle;
  private final McpHealthProbe health;
  private final McpLogReader logReader;

  public McpRegistryService(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      AgentMcpLinkRepository links,
      HostService hosts,
      McpConfigStore configs,
      McpServerDtoMapper mapper,
      McpComposeLifecycle lifecycle,
      McpHealthProbe health,
      McpLogReader logReader) {
    this.repository = repository;
    this.retained = retained;
    this.links = links;
    this.hosts = hosts;
    this.configs = configs;
    this.mapper = mapper;
    this.lifecycle = lifecycle;
    this.health = health;
    this.logReader = logReader;
  }

  // ── reads ──────────────────────────────────────────────────────────────────

  public List<McpServerDto> list() {
    return repository.findAll().stream()
        .map(lifecycle::refreshRuntime)
        .map(mapper::toDto)
        .toList();
  }

  /** Public integration point used by Agent and template services. */
  public McpServerDto require(String id) {
    return mapper.toDto(lifecycle.refreshRuntime(requireRow(id)));
  }

  /** Decrypts a catalog environment only inside the backend trust boundary. */
  public Map<String, String> materializedEnvironment(String id) {
    return configs.materialize(configs.read(requireRow(id)).environment());
  }

  /** Decrypts connection headers only inside the backend trust boundary. */
  public Map<String, String> materializedHeaders(String id) {
    return configs.materialize(configs.read(requireRow(id)).headers());
  }

  public String sameHostConnectionUrl(String id) {
    ServerRow row = requireRow(id);
    StoredConfig config = configs.read(row);
    if ("managed".equals(row.kind())) return McpServerDtoMapper.connectionUrl(row, config);
    return "external".equals(row.kind()) ? config.url() : null;
  }

  public List<LogLineDto> logs(String id, int tail) {
    return logReader.logs(requireRow(id), tail);
  }

  // ── definitions ────────────────────────────────────────────────────────────

  public McpServerDto create(McpServerRequest request) {
    Validated validated = McpRequestValidator.validate(request);
    hostsForManaged(validated);
    if (repository.nameExists(validated.name(), null)) {
      throw new ResourceConflictException("an MCP server named '" + validated.name() + "' already exists");
    }
    String id = "mcp-" + UUID.randomUUID().toString().substring(0, 12);
    boolean managed = "managed".equals(validated.kind());
    String serviceKey = managed ? "mcp-" + id.substring(4, 12) : null;
    StoredConfig config = configs.store(validated, null);
    long now = System.currentTimeMillis();
    ServerRow row = new ServerRow(id, validated.name(), validated.description(), validated.kind(),
        validated.hostId(), serviceKey, configs.write(config), "stopped",
        managed ? "missing" : "unavailable", managed ? "provisioning" : "idle", null, 1,
        managed ? 0 : 1, null, null, null, null, null, now, now);
    repository.insert(row);
    if (managed) lifecycle.submit(id, () -> lifecycle.provisionStopped(id));
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
    StoredConfig config = configs.store(validated, configs.read(existing));
    long revision = existing.revision() + 1;
    boolean recreateStopped = "managed".equals(existing.kind())
        && "stopped".equals(existing.desiredState());
    long applied = "managed".equals(existing.kind()) ? existing.appliedRevision() : revision;
    repository.updateDefinition(id, validated.name(), validated.description(), configs.write(config),
        revision, applied, recreateStopped ? "applying" : "idle");
    if (recreateStopped) lifecycle.submit(id, () -> lifecycle.provisionStopped(id));
    return require(id);
  }

  /**
   * Every reason this record could refuse to be deleted, except for the links that the
   * caller is about to remove itself.
   *
   * <p>Exists so the Agent integration layer can be asked for permission before it starts
   * disabling and unlinking Agent copies. That step rewrites {@code config.yaml} on every
   * Agent holding the server and drops the link rows, none of which is undone if
   * {@link #delete} then refuses — so it must not run before the refusal is ruled out.
   */
  public void assertDeletable(String id) {
    ensureIdle(requireRow(id));
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
      return mapper.toDto(row);
    }
    repository.beginOperation(id, "stopped", "deleting");
    lifecycle.submit(id, () -> lifecycle.runDelete(id));
    return require(id);
  }

  // ── container lifecycle ────────────────────────────────────────────────────

  public McpServerDto start(String id) {
    requireManagedIdle(id);
    repository.beginOperation(id, "running", "starting");
    lifecycle.submit(id, () -> lifecycle.runStart(id, false));
    return require(id);
  }

  public McpServerDto stop(String id) {
    requireManagedIdle(id);
    repository.beginOperation(id, "stopped", "stopping");
    lifecycle.submit(id, () -> lifecycle.runStop(id));
    return require(id);
  }

  public McpServerDto apply(String id) {
    ServerRow row = requireManagedIdle(id);
    repository.beginOperation(id, row.desiredState(), "applying");
    lifecycle.submit(id, () -> lifecycle.reconcile(id));
    return require(id);
  }

  public McpServerDto check(String id) {
    health.check(requireRow(id));
    return require(id);
  }

  // ── retained resources ─────────────────────────────────────────────────────

  public List<RetainedResourceDto> retainedResources() {
    return retained.findAll();
  }

  public RetainedResourceDto retainedResource(String id) {
    return retained.require(id);
  }

  public void purgeRetainedResource(String id) {
    RetainedResourceDto resource = retained.require(id);
    lifecycle.purgeVolume(resource.hostId(), resource.name());
    retained.delete(id);
  }

  // ── catalog rules ──────────────────────────────────────────────────────────

  private ServerRow requireRow(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server: " + id));
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

  /** A managed record must name a host this dashboard knows; resolving the URL proves it. */
  private void hostsForManaged(Validated value) {
    if ("managed".equals(value.kind())) hosts.ref(value.hostId());
  }
}
