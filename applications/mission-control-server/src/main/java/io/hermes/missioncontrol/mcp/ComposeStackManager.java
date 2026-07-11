package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.hosts.HostService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes Docker Compose without a shell and serializes all mutations per daemon. */
@Component
class ComposeStackManager {

  private final HostService hosts;
  private final Path baseDirectory;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  ComposeStackManager(
      HostService hosts,
      @Value("${mc.mcp-stack-dir:./data/mcp-stacks}") String baseDirectory) {
    this.hosts = hosts;
    this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
  }

  String execute(
      String hostId,
      ComposeStackRenderer.Rendered rendered,
      List<String> composeArguments,
      Duration timeout) {
    ReentrantLock lock = locks.computeIfAbsent(hostId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      verifyOwnedOrMissing(hostId, "network", ComposeStackRenderer.NETWORK);
      for (List<String> volumes : rendered.volumeNames().values()) {
        for (String volume : volumes) verifyOwnedOrMissing(hostId, "volume", volume);
      }
      for (List<String> services : rendered.serviceNames().values()) {
        for (String service : services) verifyComposeServiceOwnership(hostId, service);
      }
      Path composeFile = write(hostId, rendered.yaml());
      List<String> command = composeBase(hostId, composeFile);
      command.addAll(composeArguments);
      return run(command, rendered.processEnvironment(), timeout);
    } finally {
      lock.unlock();
    }
  }

  void writeOnly(String hostId, ComposeStackRenderer.Rendered rendered) {
    ReentrantLock lock = locks.computeIfAbsent(hostId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      write(hostId, rendered.yaml());
    } finally {
      lock.unlock();
    }
  }

  String serviceContainerId(String hostId, String serviceKey) {
    ReentrantLock lock = locks.computeIfAbsent(hostId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      Path composeFile = stackPath(hostId);
      if (!Files.exists(composeFile)) return null;
      List<String> command = composeBase(hostId, composeFile);
      command.addAll(List.of("ps", "--all", "-q", serviceKey));
      String output = run(command, Map.of(), Duration.ofSeconds(20)).trim();
      return output.isBlank() ? null : output.lines().findFirst().orElse(null);
    } finally {
      lock.unlock();
    }
  }

  void purgeVolume(String hostId, String volumeName) {
    if (volumeName == null || !volumeName.startsWith(ComposeStackRenderer.PROJECT + "-")) {
      throw new IllegalArgumentException("refusing to purge a volume not owned by Mission Control MCP");
    }
    ReentrantLock lock = locks.computeIfAbsent(hostId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      String owner = inspectOwner(hostId, "volume", volumeName);
      if (!ComposeStackRenderer.PROJECT.equals(owner)) {
        throw new IllegalArgumentException("refusing to purge a volume not labeled as Mission Control MCP-owned");
      }
      run(List.of("docker", "--host", hosts.urlOf(hostId), "volume", "rm", volumeName),
          Map.of(), Duration.ofMinutes(2));
    } finally {
      lock.unlock();
    }
  }

  Path stackPath(String hostId) {
    if (hostId == null || !hostId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) {
      throw new IllegalArgumentException("invalid docker host id");
    }
    Path path = baseDirectory.resolve(hostId).resolve("compose.yaml").normalize();
    if (!path.startsWith(baseDirectory)) throw new IllegalArgumentException("invalid docker host id");
    return path;
  }

  private Path write(String hostId, String yaml) {
    Path target = stackPath(hostId);
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
      throw new RuntimeException("could not write managed MCP Compose file: " + e.getMessage(), e);
    }
  }

  private List<String> composeBase(String hostId, Path composeFile) {
    return new ArrayList<>(List.of(
        "docker", "--host", hosts.urlOf(hostId), "compose",
        "--project-name", ComposeStackRenderer.PROJECT,
        "--file", composeFile.toString()));
  }

  private void verifyOwnedOrMissing(String hostId, String type, String name) {
    try {
      String owner = inspectOwner(hostId, type, name);
      if (!ComposeStackRenderer.PROJECT.equals(owner)) {
        throw new IllegalArgumentException(
            "a " + type + " named '" + name + "' already exists but is not owned by Mission Control MCP");
      }
    } catch (MissingDockerResource ignored) {
      // Compose may safely create it.
    }
  }

  private void verifyComposeServiceOwnership(String hostId, String service) {
    String ids = run(List.of(
        "docker", "--host", hosts.urlOf(hostId), "ps", "--all",
        "--filter", "label=com.docker.compose.project=" + ComposeStackRenderer.PROJECT,
        "--filter", "label=com.docker.compose.service=" + service,
        "--format", "{{.ID}}"), Map.of(), Duration.ofSeconds(20));
    for (String id : ids.lines().map(String::trim).filter(value -> !value.isEmpty()).toList()) {
      String owner = inspectOwner(hostId, "container", id);
      if (!ComposeStackRenderer.PROJECT.equals(owner)) {
        throw new IllegalArgumentException(
            "a Compose container for service '" + service + "' exists but is not owned by Mission Control MCP");
      }
    }
  }

  private String inspectOwner(String hostId, String type, String name) {
    List<String> command = List.of(
        "docker", "--host", hosts.urlOf(hostId), type, "inspect",
        "--format", "{{ index .Labels \"io.hermes.mission-control.owner\" }}", name);
    try {
      return run(command, Map.of(), Duration.ofSeconds(20)).trim();
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.contains("No such " + type) || message.contains("no such " + type)
          || message.contains("not found")) {
        throw new MissingDockerResource();
      }
      throw e;
    }
  }

  private static final class MissingDockerResource extends RuntimeException { }

  private static String run(List<String> command, Map<String, String> environment, Duration timeout) {
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(environment);
      process = builder.start();
    } catch (IOException e) {
      throw new RuntimeException("could not start Docker CLI: " + e.getMessage(), e);
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
        throw new RuntimeException("Docker Compose operation timed out");
      }
      reader.join(Duration.ofSeconds(2));
      if (process.exitValue() != 0) {
        String detail = output.toString().strip();
        throw new RuntimeException("Docker Compose operation failed"
            + (detail.isBlank() ? "" : ": " + detail));
      }
      return output.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new RuntimeException("Docker Compose operation interrupted", e);
    }
  }
}
