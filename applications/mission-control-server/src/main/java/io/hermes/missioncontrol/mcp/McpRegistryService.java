package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(McpRegistryService.class);

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

  /**
   * The whole catalog, with managed runtime state refreshed first.
   *
   * <p>The refresh is handed the rows together rather than mapped over one at a time: per row it
   * costs a {@code docker compose ps} fork under the host's compose lock plus a full container
   * listing, and per host it costs one of each.
   */
  public List<McpServerDto> list() {
    return lifecycle.refreshRuntime(repository.findAll()).stream()
        .map(mapper::toDto)
        .toList();
  }

  /**
   * The stored record, exactly as the catalog holds it. No daemon is contacted and nothing is
   * written, so this is what a caller that only needs the definition — a name, a transport, a
   * revision, an endpoint — should ask for.
   *
   * <p>Split from {@link #live} because there used to be one method and it was the refreshing
   * one. The Agent read path calls this per linked entry per profile on a 12-second poll, and
   * each of those calls was forking {@code docker compose ps} under the host's compose lock and
   * listing every container on the daemon, to reach a {@code revision} column.
   */
  public McpServerDto definition(String id) {
    return mapper.toDto(requireRow(id));
  }

  /**
   * The record with its managed runtime state refreshed against the daemon first — for the
   * callers that are about to act on whether the server is actually up.
   *
   * <p>Costs a Compose query and a container listing, and persists the refreshed state, so it
   * is deliberately not what a listing or an enrichment uses.
   */
  public McpServerDto live(String id) {
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
        McpRuntimeState.initial(managed).wire(),
        (managed ? McpOperationState.PROVISIONING : McpOperationState.IDLE).wire(), null, 1,
        managed ? 0 : 1, null, null, null, null, null, now, now);
    repository.insert(row);
    if (managed) lifecycle.submit(id, () -> lifecycle.provisionStopped(id));
    return live(id);
  }

  public McpServerDto update(String id, McpServerRequest request) {
    ServerRow existing = requireRow(id);
    ensureIdle(existing);
    Validated validated = McpRequestValidator.validate(request);
    // the definition write below is guarded on `existing.revision()`, so this check is only
    // here to answer a mid-operation record with the message it expects rather than the
    // stale-revision one
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
    StoredConfig previous = configs.read(existing);
    ensureSupportServicesNotRenamed(previous, validated);
    StoredConfig config = configs.store(validated, previous);
    long revision = existing.revision() + 1;
    boolean recreateStopped = "managed".equals(existing.kind())
        && "stopped".equals(existing.desiredState());
    long applied = "managed".equals(existing.kind()) ? existing.appliedRevision() : revision;
    boolean written = repository.updateDefinition(
        id, validated.name(), validated.description(), configs.write(config), revision, applied,
        (recreateStopped ? McpOperationState.APPLYING : McpOperationState.IDLE).wire(),
        existing.revision());
    if (!written) {
      throw new ResourceConflictException(
          "the MCP server changed while this edit was open; reload it and apply the change again");
    }
    if (recreateStopped) lifecycle.submit(id, () -> lifecycle.provisionStopped(id));
    return live(id);
  }

  /**
   * Takes the record out of circulation for a deletion that is about to start.
   *
   * <p>Exists so the Agent integration layer can be given permission before it starts disabling
   * and unlinking Agent copies. That step rewrites {@code config.yaml} on every Agent holding the
   * server and drops the link rows, none of which is undone if {@link #completeDeletion} then
   * refuses — so it must not run before the refusal is ruled out.
   *
   * <p>A claim rather than the question this used to ask. Answering "yes, currently" left the
   * record free for the whole time the listeners took, and a {@code start} arriving in that
   * window made the deletion refuse after the Agents had already been severed — the exact
   * outcome the ordering exists to prevent, which ordering alone cannot reach. Every caller that
   * takes one owes a {@link #releaseClaim} on any path that does not go through to
   * {@link #completeDeletion}.
   */
  public void claimForDeletion(String id) {
    ServerRow row = requireRow(id);
    if (!repository.claimOperation(id, row.desiredState(), McpOperationState.DELETING.wire())) {
      throw new ResourceConflictException("an MCP server operation is already in progress");
    }
  }

  /** Hands back a claim whose deletion did not go ahead, so the record is usable again. */
  public void releaseClaim(String id) {
    repository.releaseOperation(id);
  }

  /**
   * Finishes a deletion the caller already holds the claim for: managed records asynchronously,
   * everything else by dropping the row. Linked Agent entries must first be disabled and unlinked
   * by the Agent integration layer, preventing silent loss.
   *
   * <p>Does not re-check that the record is settled — it is {@code deleting}, because this
   * caller put it there. The link guard is what can still refuse, and it releases the claim
   * before it does.
   */
  public McpServerDto completeDeletion(String id) {
    ServerRow row = requireRow(id);
    if (!links.findByServer(id).isEmpty()) {
      releaseClaim(id);
      throw new ResourceConflictException(
          "the MCP server is still linked to one or more Agents; disable and unlink them, then retry");
    }
    if (!"managed".equals(row.kind())) {
      repository.delete(id);
      return mapper.toDto(row);
    }
    lifecycle.submit(id, () -> lifecycle.runDelete(id));
    return live(id);
  }

  // ── container lifecycle ────────────────────────────────────────────────────

  public McpServerDto start(String id) {
    requireManaged(id);
    claim(id, "running", McpOperationState.STARTING);
    lifecycle.submit(id, () -> lifecycle.runStart(id, false));
    return live(id);
  }

  public McpServerDto stop(String id) {
    requireManaged(id);
    claim(id, "stopped", McpOperationState.STOPPING);
    lifecycle.submit(id, () -> lifecycle.runStop(id));
    return live(id);
  }

  public McpServerDto apply(String id) {
    ServerRow row = requireManaged(id);
    claim(id, row.desiredState(), McpOperationState.APPLYING);
    lifecycle.submit(id, () -> lifecycle.reconcile(id));
    return live(id);
  }

  public McpServerDto check(String id) {
    health.check(requireRow(id));
    return live(id);
  }

  // ── retained resources ─────────────────────────────────────────────────────

  public List<RetainedResourceDto> retainedResources() {
    return retained.findAll();
  }

  public RetainedResourceDto retainedResource(String id) {
    return retained.require(id);
  }

  /**
   * Drops a volume kept behind by a deletion, and the row that remembered it.
   *
   * <p>The row goes whether or not the daemon still had the volume. An operator who removed it
   * by hand used to be stuck: the purge answered 500 with no detail, the row survived, and every
   * retry took the same path — so the inventory went on listing a volume that did not exist,
   * with no way to clear it.
   */
  public void purgeRetainedResource(String id) {
    RetainedResourceDto resource = retained.require(id);
    if (!lifecycle.purgeVolume(resource.hostId(), resource.name())) {
      log.info("retained volume {} on {} was already gone; dropping the record of it",
          resource.name(), resource.hostId());
    }
    retained.delete(id);
  }

  // ── catalog rules ──────────────────────────────────────────────────────────

  private ServerRow requireRow(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server: " + id));
  }

  private ServerRow requireManaged(String id) {
    ServerRow row = requireRow(id);
    if (!"managed".equals(row.kind())) {
      throw new IllegalArgumentException("container lifecycle applies only to managed MCP servers");
    }
    return row;
  }

  /**
   * Records what is about to be done, or refuses because something already is.
   *
   * <p>The refusal comes from the write itself, not from a read taken before it: two requests
   * arriving together both saw an idle record, and both used to be admitted.
   */
  private void claim(String id, String desiredState, McpOperationState operation) {
    if (!repository.claimOperation(id, desiredState, operation.wire())) {
      throw new ResourceConflictException("an MCP server operation is already in progress");
    }
  }

  /**
   * A stored support service's name is its Compose identity, so an update may add support
   * services and it may remove them, but it may not rename one.
   *
   * <p>{@link ComposeStackRenderer#supportKey} derives the Compose service name from it. That
   * name is the hostname the main container reaches the dependency by, the prefix of every
   * volume the dependency declares, the key its {@code depends_on} entry is written under, and
   * what {@link McpLogReader} looks its container up by. A renamed support service is therefore
   * a different service with empty volumes, reachable at an address the main container's own
   * configuration does not mention.
   *
   * <p>Refused here rather than accommodated in {@link McpConfigStore}, which carries stored
   * secrets forward under this same name and so fails the save today. Giving support services a
   * stable id and keying the secrets by that would let the save through and make every
   * consequence above silent, which is worse than refusing it.
   *
   * <p>A rename is only detectable as one because it arrives together: a stored name has left
   * and an unknown name has appeared in its place. Doing the two halves as separate saves is
   * allowed, and is what the message asks for — it makes replacing the dependency the operator's
   * decision rather than a side effect of editing a text field. The removal half is reclaimed
   * properly by {@code McpComposeLifecycle}, which stops the departed container and moves its
   * volumes to the retained inventory.
   */
  private static void ensureSupportServicesNotRenamed(StoredConfig previous, Validated validated) {
    Set<String> stored = previous.supportServices().stream()
        .map(StoredSupportService::name).collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> submitted = validated.supportServices().stream()
        .map(SupportServiceRequest::name).collect(Collectors.toCollection(LinkedHashSet::new));
    List<String> dropped = stored.stream().filter(name -> !submitted.contains(name)).toList();
    List<String> added = submitted.stream().filter(name -> !stored.contains(name)).toList();
    if (dropped.isEmpty() || added.isEmpty()) return;
    throw new IllegalArgumentException(
        "a support service cannot be renamed: its name is the Compose service name, the hostname"
            + " the server reaches it by, and the prefix of its volumes. This update drops "
            + dropped + " and adds " + added + ". Remove and re-add in separate saves if the"
            + " dependency really is being replaced — a removed one's volumes are kept as"
            + " retained resources for you to purge, and the new one starts with empty ones.");
  }

  private static void ensureIdle(ServerRow row) {
    if (!McpOperationState.settled(row.operationState())) {
      throw new ResourceConflictException("an MCP server operation is already in progress");
    }
  }

  /** A managed record must name a host this dashboard knows; resolving the URL proves it. */
  private void hostsForManaged(Validated value) {
    if ("managed".equals(value.kind())) hosts.ref(value.hostId());
  }
}
