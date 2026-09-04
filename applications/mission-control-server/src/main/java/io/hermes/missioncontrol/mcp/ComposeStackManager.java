package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Executes Docker Compose without a shell and serializes all mutations per daemon.
 *
 * <p>Takes the daemon it is to talk to as a {@link DockerHostRef}. It used to take a
 * {@code hostId} and resolve it through {@code HostService} at each of the nine places it needed
 * a {@code --host} argument, so a single operation resolved the same host up to four times —
 * exactly the scattering {@link DockerHostRef} was introduced to end, still in place in this one
 * package. Nothing here decides which daemon to use, so nothing here needs the registry.
 */
@Component
class ComposeStackManager {

  private final Path baseDirectory;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  ComposeStackManager(@Value("${mc.mcp-stack-dir:./data/mcp-stacks}") String baseDirectory) {
    this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
  }

  String execute(
      DockerHostRef host,
      ComposeStackRenderer.Rendered rendered,
      List<String> composeArguments,
      Duration timeout) {
    ReentrantLock lock = locks.computeIfAbsent(host.id(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      verifyOwnedOrMissing(host, "network", ManagedMcpStack.NETWORK);
      for (List<String> volumes : rendered.volumeNames().values()) {
        for (String volume : volumes) verifyOwnedOrMissing(host, "volume", volume);
      }
      for (List<String> services : rendered.serviceNames().values()) {
        for (String service : services) verifyComposeServiceOwnership(host, service);
      }
      Path composeFile = write(host, rendered.yaml());
      List<String> command = composeBase(host, composeFile);
      command.addAll(composeArguments);
      return run(command, rendered.processEnvironment(), timeout);
    } finally {
      lock.unlock();
    }
  }

  void writeOnly(DockerHostRef host, ComposeStackRenderer.Rendered rendered) {
    ReentrantLock lock = locks.computeIfAbsent(host.id(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      write(host, rendered.yaml());
    } finally {
      lock.unlock();
    }
  }

  /** Lock-free on purpose, like every read here: {@code compose ps} reads the daemon, and the
   *  compose file it loads is only ever replaced atomically by {@link #write}. Taking the
   *  mutation lock made this read wait out whatever image pull {@link #execute} was holding
   *  the lock for. */
  String serviceContainerId(DockerHostRef host, String serviceKey) {
    Path composeFile = stackPath(host);
    if (!Files.exists(composeFile)) return null;
    List<String> command = composeBase(host, composeFile);
    command.addAll(List.of("ps", "--all", "-q", serviceKey));
    String output = run(command, Map.of(), Duration.ofSeconds(20)).trim();
    return output.isBlank() ? null : output.lines().findFirst().orElse(null);
  }

  /**
   * The Compose service names this catalog record currently has containers for, running or not.
   *
   * <p>Read back from the label {@link ComposeStackRenderer} writes rather than from the file,
   * because the reason for asking is to find what the file no longer declares.
   */
  List<String> servicesOf(DockerHostRef host, String serverId) {
    return labelled(List.of(
        "docker", "--host", host.url(), "ps", "--all",
        "--filter", "label=" + ManagedMcpStack.SERVER_ID_LABEL + "=" + serverId,
        "--format", "{{.Label \"com.docker.compose.service\"}}"));
  }

  /** The managed volume names this catalog record currently has on the daemon. */
  List<String> volumesOf(DockerHostRef host, String serverId) {
    return labelled(List.of(
        "docker", "--host", host.url(), "volume", "ls",
        "--filter", "label=" + ManagedMcpStack.SERVER_ID_LABEL + "=" + serverId,
        "--format", "{{.Name}}"));
  }

  /**
   * Every managed container on this host, keyed by its Compose service name.
   *
   * <p>{@link #serviceContainerId} answers the same question for one service by forking
   * {@code docker compose ps}. The catalog listing asked it once per row, so a page load with
   * eight managed servers forked Compose eight times; this covers every record in one fork.
   *
   * <p>{@code --no-trunc} is load-bearing: the caller joins these ids against the Engine API's
   * 64-character container ids, and the CLI's default 12-character truncation matches nothing
   * there — every managed row then read as {@code unknown}.
   *
   * <p>Keyed by the Compose service and not by the server id, because a record's support
   * services carry the same {@code SERVER_ID_LABEL}: their state is not the record's, and the
   * one the catalog reports is the service the row's {@code service_key} names.
   *
   * <p>Read from the labels rather than through Compose, which is what lets one call cover every
   * record — and is how {@link #servicesOf} and {@link #volumesOf} already work.
   */
  Map<String, String> containerIdsByService(DockerHostRef host) {
    List<String> rows = labelled(List.of(
        "docker", "--host", host.url(), "ps", "--all", "--no-trunc",
        "--filter", "label=com.docker.compose.project=" + ManagedMcpStack.PROJECT,
        "--format", "{{.Label \"com.docker.compose.service\"}}\t{{.ID}}"));
    Map<String, String> byService = new LinkedHashMap<>();
    for (String row : rows) {
      int tab = row.indexOf('\t');
      if (tab <= 0) continue;   // a container the daemon reported no service name for
      String service = row.substring(0, tab).trim();
      String containerId = row.substring(tab + 1).trim();
      if (!service.isEmpty() && !containerId.isEmpty()) byService.putIfAbsent(service, containerId);
    }
    return byService;
  }

  /**
   * Stops and removes the containers of services the rendered file no longer declares.
   *
   * <p>Not {@code compose rm}: that addresses a service by the name it has <em>in the file</em>,
   * and a departed one is by definition no longer there. The containers are found by the label
   * the renderer wrote and removed directly, after the same ownership check every other mutation
   * here makes.
   *
   * <p>{@code --volumes} drops only the anonymous volumes an image declared for itself. The
   * named ones this stack creates are untouched by it — they outlive the container on purpose,
   * and the caller records them as retained instead.
   */
  void removeServices(DockerHostRef host, String serverId, List<String> services, Duration timeout) {
    if (services.isEmpty()) return;
    ReentrantLock lock = locks.computeIfAbsent(host.id(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      String url = host.url();
      for (String service : services) {
        for (String id : containerIds(url, serverId, service)) {
          Optional<String> owner = inspectOwner(host, "container", id);
          // listed a moment ago and gone by the time it was inspected: nothing left to remove
          if (owner.isEmpty()) continue;
          if (!ManagedMcpStack.PROJECT.equals(owner.get())) {
            throw new IllegalArgumentException("a container for the departed service '" + service
                + "' exists but is not owned by Mission Control MCP");
          }
          run(List.of("docker", "--host", url, "rm", "--force", "--volumes", id), Map.of(), timeout);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private List<String> containerIds(String url, String serverId, String service) {
    return lines(run(List.of(
        "docker", "--host", url, "ps", "--all",
        "--filter", "label=" + ManagedMcpStack.SERVER_ID_LABEL + "=" + serverId,
        "--filter", "label=com.docker.compose.service=" + service,
        "--format", "{{.ID}}"), Map.of(), Duration.ofSeconds(20)));
  }

  /** Lock-free on purpose: a label-filtered listing reads the daemon, not the compose file,
   *  and taking the mutation lock made every catalog listing wait out whatever image pull
   *  {@link #execute} was holding it for. */
  private List<String> labelled(List<String> command) {
    return lines(run(command, Map.of(), Duration.ofSeconds(20)));
  }

  private static List<String> lines(String output) {
    return output.lines().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
  }

  /**
   * Drops a managed volume.
   *
   * @return false when the daemon has no such volume — someone removed it by hand, and the
   *     caller's record of it is simply out of date. Both guards below still run first: this is
   *     "already gone", not "skip the checks"
   */
  boolean purgeVolume(DockerHostRef host, String volumeName) {
    if (volumeName == null || !volumeName.startsWith(ManagedMcpStack.PROJECT + "-")) {
      throw new IllegalArgumentException("refusing to purge a volume not owned by Mission Control MCP");
    }
    ReentrantLock lock = locks.computeIfAbsent(host.id(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      Optional<String> owner = inspectOwner(host, "volume", volumeName);
      if (owner.isEmpty()) return false;
      if (!ManagedMcpStack.PROJECT.equals(owner.get())) {
        throw new IllegalArgumentException("refusing to purge a volume not labeled as Mission Control MCP-owned");
      }
      run(List.of("docker", "--host", host.url(), "volume", "rm", volumeName),
          Map.of(), Duration.ofMinutes(2));
      return true;
    } finally {
      lock.unlock();
    }
  }

  Path stackPath(DockerHostRef host) {
    String hostId = host.id();
    if (!hostId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) {
      throw new IllegalArgumentException("invalid docker host id");
    }
    Path path = baseDirectory.resolve(hostId).resolve("compose.yaml").normalize();
    if (!path.startsWith(baseDirectory)) throw new IllegalArgumentException("invalid docker host id");
    return path;
  }

  private Path write(DockerHostRef host, String yaml) {
    Path target = stackPath(host);
    try {
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), "compose-", ".yaml.tmp");
      Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException unsupportedAtomicMove) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return target;
    } catch (IOException e) {
      throw new UpstreamUnavailableException("could not write managed MCP Compose file: " + e.getMessage(), e);
    }
  }

  private List<String> composeBase(DockerHostRef host, Path composeFile) {
    return new ArrayList<>(List.of(
        "docker", "--host", host.url(), "compose",
        "--project-name", ManagedMcpStack.PROJECT,
        "--file", composeFile.toString()));
  }

  /** Refuses a name that exists and belongs to someone else. Absent is fine: Compose may
   *  safely create it. */
  private void verifyOwnedOrMissing(DockerHostRef host, String type, String name) {
    Optional<String> owner = inspectOwner(host, type, name);
    if (owner.isPresent() && !ManagedMcpStack.PROJECT.equals(owner.get())) {
      throw new IllegalArgumentException(
          "a " + type + " named '" + name + "' already exists but is not owned by Mission Control MCP");
    }
  }

  private void verifyComposeServiceOwnership(DockerHostRef host, String service) {
    String ids = run(List.of(
        "docker", "--host", host.url(), "ps", "--all",
        "--filter", "label=com.docker.compose.project=" + ManagedMcpStack.PROJECT,
        "--filter", "label=com.docker.compose.service=" + service,
        "--format", "{{.ID}}"), Map.of(), Duration.ofSeconds(20));
    for (String id : ids.lines().map(String::trim).filter(value -> !value.isEmpty()).toList()) {
      Optional<String> owner = inspectOwner(host, "container", id);
      // gone between the listing and the inspect: there is nothing left to refuse to touch
      if (owner.isPresent() && !ManagedMcpStack.PROJECT.equals(owner.get())) {
        throw new IllegalArgumentException(
            "a Compose container for service '" + service + "' exists but is not owned by Mission Control MCP");
      }
    }
  }

  /**
   * The owner label on a Docker resource, or empty when the daemon has no such resource.
   *
   * <p>Absence is a result and not an exception. It used to be signalled by throwing a private
   * {@code MissingDockerResource} with no message, which only {@link #verifyOwnedOrMissing}
   * caught: from {@link #purgeVolume} it escaped as an untyped {@code RuntimeException} that no
   * handler matched, so purging a volume an operator had already removed by hand answered 500
   * with no detail and left the retained row in place — un-purgeable, because every retry took
   * the same path.
   */
  private Optional<String> inspectOwner(DockerHostRef host, String type, String name) {
    // containers expose their labels under .Config.Labels; networks and volumes at the top level
    String labels = "container".equals(type) ? ".Config.Labels" : ".Labels";
    List<String> command = List.of(
        "docker", "--host", host.url(), type, "inspect",
        "--format", "{{ index " + labels + " \"" + ManagedMcpStack.OWNER_LABEL + "\" }}", name);
    try {
      return Optional.of(run(command, Map.of(), Duration.ofSeconds(20)).trim());
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.contains("No such " + type) || message.contains("no such " + type)
          || message.contains("not found")) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * Runs the Docker CLI. Package-private and non-static so a test can substitute it: every
   * ownership guard in this class sits above this call, and none of them is reachable
   * otherwise without a real daemon and real foreign resources to refuse to destroy.
   */
  String run(List<String> command, Map<String, String> environment, Duration timeout) {
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(environment);
      process = builder.start();
    } catch (IOException e) {
      throw new UpstreamUnavailableException("could not start Docker CLI: " + e.getMessage(), e);
    }

    StringBuilder output = new StringBuilder();
    Thread reader = Thread.ofVirtual().start(() -> {
      try (var stream = process.getInputStream()) {
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
          if (output.length() < 32_768) {
            int remaining = 32_768 - output.length();
            output.append(new String(buffer, 0, Math.min(read, remaining), StandardCharsets.UTF_8));
          }
        }
      } catch (IOException ignored) { }
    });

    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new UpstreamUnavailableException("Docker Compose operation timed out");
      }
      reader.join(Duration.ofSeconds(2));
      if (process.exitValue() != 0) {
        String detail = output.toString().strip();
        throw new UpstreamUnavailableException("Docker Compose operation failed"
            + (detail.isBlank() ? "" : ": " + detail));
      }
      return output.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new UpstreamUnavailableException("Docker Compose operation interrupted", e);
    }
  }
}
