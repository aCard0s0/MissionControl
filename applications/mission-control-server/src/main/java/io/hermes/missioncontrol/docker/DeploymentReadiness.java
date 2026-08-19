package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The checks a freshly created Hermes container must pass before its deploy or upgrade is
 * called a success.
 *
 * <p>Split out of {@link DockerGateway} because both {@link HermesDeployer} and
 * {@link ContainerUpgrader} gate their rollback on exactly this verdict — a second copy of
 * these checks would let one path accept a container the other would have rolled back.
 */
@Component
public class DeploymentReadiness {

  private final DockerExecService dockerExec;

  public DeploymentReadiness(DockerExecService dockerExec) {
    this.dockerExec = dockerExec;
  }

  void validate(DockerHostRef host, DockerClient client, String containerId, List<String> seedProfiles) {
    requireRunning(client, containerId,
        "Hermes container exited before readiness checks completed");

    List<String> profiles = new ArrayList<>();
    profiles.add("default");
    profiles.addAll(seedProfiles);
    String script = """
        set -eu
        for profile in "$@"; do
          if [ "$profile" = default ]; then
            dir=/opt/data
            test -r "$dir/config.yaml" || { echo "profile config is unreadable: $profile" >&2; exit 1; }
          else
            dir="/opt/data/profiles/$profile"
            test -d "$dir" || { echo "profile directory is missing: $profile" >&2; exit 1; }
            if [ -e "$dir/config.yaml" ]; then
              test -r "$dir/config.yaml" || { echo "profile config is unreadable: $profile" >&2; exit 1; }
            fi
          fi
          if [ -e "$dir/.env" ]; then
            test -r "$dir/.env" || { echo "profile environment is unreadable: $profile" >&2; exit 1; }
          fi
        done
        hermes profile list >/dev/null 2>&1 || { echo "hermes profile list failed" >&2; exit 1; }
        tries=0
        while true; do
          if detail="$(hermes gateway status 2>&1)" && printf '%s' "$detail" | grep -q 'Gateway is running'; then
            break
          fi
          tries=$((tries + 1))
          if [ "$tries" -ge 30 ]; then
            echo "default gateway not ready: $(printf '%s' "$detail" | tail -n 1)" >&2
            exit 1
          fi
          sleep 1
        done
        """;
    List<String> command = new ArrayList<>(List.of("sh", "-c", script, "_"));
    command.addAll(profiles);
    try {
      dockerExec.runAsUser(
          host, containerId, "hermes", command, "Hermes deployment readiness",
          true, false, Duration.ofSeconds(45));
    } catch (UpstreamUnavailableException already) {
      throw already;
    } catch (RuntimeException notReady) {
      // A non-zero readiness exit is a bare RuntimeException, which the advice answers as
      // 500 with a stack trace — for the same operational outcome the checks two lines
      // above and below report as 503. A gateway that is slow to come up is not a defect.
      throw new UpstreamUnavailableException(
          "Hermes readiness checks failed: " + notReady.getMessage(), notReady);
    }

    requireRunning(client, containerId, "Hermes container stopped during readiness checks");
  }

  private static void requireRunning(DockerClient client, String containerId, String message) {
    var state = client.inspectContainerCmd(containerId).exec().getState();
    if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
      throw new UpstreamUnavailableException(message);
    }
  }
}
