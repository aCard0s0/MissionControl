package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an operator asks the daemon to open between a Hermes container and its host: published
 * ports, environment variables and bind mounts. All three are create-time — Docker cannot add
 * any of them to a running container — so they are set on the deploy, or on the recreate an
 * image update already is: {@link ContainerUpgrader} carries the original's onto the
 * replacement and lays any asked for on the update over them.
 *
 * <p>Nothing here is guessed. A Hermes feature that needs one of these — its own dashboard on
 * 9119, the API server on 8642, a webhook listener on 8644, a repository to work in — stays
 * off until the operator says otherwise; the deploy form's presets are how they say it.
 *
 * <p>Two kinds of mount are refused. The Docker socket, or a directory that carries it, is
 * root-equivalent control of the host and an agent holding it would have exactly that — the
 * same rule the MCP catalog applies. And nothing may land on {@code /opt/data} or
 * {@code /opt/hermes}: the first is the managed data volume, the second the image's read-only
 * install tree.
 */
public record HostAccess(List<PortMapping> ports, List<EnvVar> env, List<Mount> mounts) {

  public static final HostAccess NONE = new HostAccess(List.of(), List.of(), List.of());

  /** How the container reaches services on its host — a local inference server, typically.
   *  Docker Desktop resolves the name on its own; a Linux daemon only with this mapping. */
  public static final String HOST_GATEWAY = "host.docker.internal:host-gateway";

  /** Hermes' name for the directories its file tools may write into; several join with {@code :}. */
  public static final String WRITE_SAFE_ROOT = "HERMES_WRITE_SAFE_ROOT";

  /** Sources that are, or contain, the Docker socket. */
  private static final List<String> SOCKET_PARENTS = List.of("/", "/var", "/var/run", "/run");

  public HostAccess {
    ports = ports == null ? List.of() : List.copyOf(ports);
    env = env == null ? List.of() : List.copyOf(env);
    mounts = mounts == null ? List.of() : List.copyOf(mounts);
    for (Mount mount : mounts) mount.check();
  }

  public boolean isEmpty() {
    return ports.isEmpty() && env.isEmpty() && mounts.isEmpty();
  }

  /**
   * A container's environment with this access laid over it: the operator's variables replace
   * any line carrying the same key, and the write-safe root is widened over the writable mounts
   * — unless the operator set it themselves. The image confines hermes' file tools to
   * {@code /opt/data}, so a repository mounted for the agent to work in would be the first thing
   * it could not write to, and hermes reports that only as a denied tool call.
   *
   * <p>{@code current} is empty on a deploy and the original container's {@code Config.Env} on
   * an update, which is how a root a previous deploy already widened keeps what it had.
   */
  public List<String> environment(List<String> current) {
    Map<String, String> env = new LinkedHashMap<>();
    for (String line : current) {
      int eq = line.indexOf('=');
      env.put(eq < 0 ? line : line.substring(0, eq), eq < 0 ? "" : line.substring(eq + 1));
    }
    for (EnvVar var : this.env) env.put(var.key(), var.value());
    List<String> writable = mounts.stream().filter(m -> !m.readOnly()).map(Mount::target).toList();
    if (!writable.isEmpty() && this.env.stream().noneMatch(v -> WRITE_SAFE_ROOT.equals(v.key()))) {
      Set<String> roots = new LinkedHashSet<>();
      for (String root : env.getOrDefault(WRITE_SAFE_ROOT, ManagedContainer.DATA_MOUNT).split(":")) {
        if (!root.isBlank()) roots.add(root);
      }
      roots.addAll(writable);
      env.put(WRITE_SAFE_ROOT, String.join(":", roots));
    }
    return env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
  }

  /** A container port and the host port it is published on. A blank {@code hostIp} binds
   *  loopback — the same default {@code ./mc} uses for the dashboard itself. */
  public record PortMapping(
      @Min(1) @Max(65535) int containerPort,
      @Min(1) @Max(65535) int hostPort,
      @Pattern(regexp = "|\\d{1,3}(\\.\\d{1,3}){3}", message = "invalid bind address") String hostIp) {

    public String bindIp() {
      return hostIp == null || hostIp.isBlank() ? "127.0.0.1" : hostIp;
    }
  }

  public record EnvVar(
      @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*", message = "invalid variable name")
      String key,
      @NotNull String value) {
  }

  /** A host directory bound into the container. */
  public record Mount(@NotBlank String source, @NotBlank String target, boolean readOnly) {

    void check() {
      if (!source.startsWith("/") || !target.startsWith("/")) {
        throw new IllegalArgumentException(
            "mount paths must be absolute: " + source + " -> " + target);
      }
      String src = stripSlash(source);
      if (src.endsWith("docker.sock") || SOCKET_PARENTS.contains(src)) {
        throw new IllegalArgumentException(
            "refused: " + source + " carries the Docker socket, which is root-equivalent control of the host");
      }
      String dst = stripSlash(target);
      for (String reserved : List.of(ManagedContainer.DATA_MOUNT, "/opt/hermes")) {
        if (dst.equals(reserved) || dst.startsWith(reserved + "/")) {
          throw new IllegalArgumentException(
              "a mount may not shadow " + reserved + ": " + target);
        }
      }
    }

    private static String stripSlash(String path) {
      return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
  }
}
