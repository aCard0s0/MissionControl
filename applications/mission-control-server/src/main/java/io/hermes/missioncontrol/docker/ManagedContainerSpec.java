package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.RestartPolicy;
import java.util.List;
import java.util.Map;

/**
 * A managed container's identity, captured before it is replaced. Copied from
 * the daemon rather than rebuilt from configuration: an Agent may have been
 * attached to the managed MCP network after it was deployed, and the
 * {@code mc.profiles} label records what was seeded, not what exists now.
 */
public record ManagedContainerSpec(
    String id,
    String name,
    String tag,
    String imageId,
    Map<String, String> labels,
    List<Bind> binds,
    RestartPolicy restartPolicy,
    List<String> cmd,
    List<String> entrypoint,
    List<String> env,
    String user,
    String workingDir,
    String primaryNetwork,
    Map<String, List<String>> extraNetworks,
    boolean wasRunning,
    String dataVolume) {
}
