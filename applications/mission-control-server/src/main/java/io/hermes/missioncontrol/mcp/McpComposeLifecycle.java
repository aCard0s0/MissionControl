package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.ComposeStackRenderer.Deployment;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The managed half of the catalog: rendering a host's Compose stack, running
 * up/stop/rm against it, and recording what happened.
 *
 * <p>Split out of {@link McpRegistryService} because every operation here is asynchronous
 * and single-flighted per record. The desired state, the {@code operation_state} and the
 * {@code applied_revision} are the only evidence a caller has that a Compose run finished,
 * so each of these methods owns writing all three.
 */
class McpComposeLifecycle {

  private static final Logger log = LoggerFactory.getLogger(McpComposeLifecycle.class);
  private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(10);

  private final McpServerRepository repository;
  private final RetainedResourceRepository retained;
  private final HostService hosts;
  private final DockerGateway docker;
  private final ComposeStackManager compose;
  private final ComposeStackRenderer renderer;
  private final McpConfigStore configs;
  private final ExecutorService operations;

  McpComposeLifecycle(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      HostService hosts,
      DockerGateway docker,
      ComposeStackManager compose,
      ComposeStackRenderer renderer,
      McpConfigStore configs,
      ExecutorService operations) {
    this.repository = repository;
    this.retained = retained;
    this.hosts = hosts;
    this.docker = docker;
    this.compose = compose;
    this.renderer = renderer;
    this.configs = configs;
    this.operations = operations;
  }

  void shutdown() {
    operations.shutdownNow();
  }

  /** Queues an operation, so a caller returns as soon as the desired state is recorded.
   *  A failure inside the task is recorded against the record rather than lost. */
  void submit(String id, Runnable operation) {
    operations.submit(() -> {
      try {
        operation.run();
      } catch (Exception e) {
        fail(id, e);
      }
    });
  }

  void provisionStopped(String id) {
    ServerRow row = requireRow(id);
    try {
      configs.assertRecoverable(configs.read(row));
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

  void runStart(String id, boolean forceRecreate) {
    ServerRow row = requireRow(id);
    try {
      configs.assertRecoverable(configs.read(row));
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

  void runStop(String id) {
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

  void runDelete(String id) {
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

  /** Drops a volume kept behind when its server was deleted. */
  void purgeVolume(String hostId, String volumeName) {
    compose.purgeVolume(hostId, volumeName);
  }

  /** Re-renders every managed record on a host: Compose owns whole files, so one record's
   *  change is written as part of the host's complete stack. */
  ComposeStackRenderer.Rendered renderHost(String hostId) {
    List<Deployment> deployments = new ArrayList<>();
    for (ServerRow row : repository.findByHost(hostId)) {
      if (!"managed".equals(row.kind())) continue;
      StoredConfig config = configs.read(row);
      Map<String, Map<String, String>> supportEnvironment = new LinkedHashMap<>();
      for (StoredSupportService support : config.supportServices()) {
        supportEnvironment.put(support.name(), configs.materializeForRender(support.environment()));
      }
      deployments.add(new Deployment(row.id(), row.serviceKey(), config,
          configs.materializeForRender(config.environment()), supportEnvironment));
    }
    return renderer.render(deployments);
  }

  /**
   * Reconciles the recorded runtime state with what the daemon actually reports.
   *
   * <p>Only while no operation is in flight: a record mid-{@code starting} has a Compose run
   * writing its state, and a read must not race it.
   */
  ServerRow refreshRuntime(ServerRow row) {
    if (!"managed".equals(row.kind()) || !List.of("idle", "error").contains(row.operationState())) {
      return row;
    }
    try {
      String containerId = compose.serviceContainerId(row.hostId(), row.serviceKey());
      String runtime = "missing";
      if (containerId != null) {
        runtime = docker.listContainers(hosts.ref(row.hostId()), true).stream()
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

  private void fail(String id, Exception error) {
    log.warn("managed MCP operation failed for {}: {}", id, error.toString());
    if (repository.findById(id).isPresent()) repository.failOperation(id, error.getMessage());
  }

  private ServerRow requireRow(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server: " + id));
  }

  /** A fixed Compose sub-command followed by the target service names. */
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
}
