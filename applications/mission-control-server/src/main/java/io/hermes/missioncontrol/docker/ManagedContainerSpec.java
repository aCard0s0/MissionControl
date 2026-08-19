package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import java.util.List;
import java.util.Map;

/**
 * A managed container's identity, captured before it is replaced. Copied from
 * the daemon rather than rebuilt from configuration: an Agent may have been
 * attached to the managed MCP network after it was deployed, and the
 * {@code mc.profiles} label records what was seeded, not what exists now.
 *
 * <p>The port fields are here for the same reason. Mission Control publishes no port for an
 * Agent, so any mapping on one was added by an operator — most likely to reach a profile's
 * webhook listener, which is the documented way to expose it. Docker cannot add a mapping to a
 * running container, so dropping it here would mean the operator's exposure vanished on an
 * image update with nothing said and no way back except recreating the container by hand.
 */
public record ManagedContainerSpec(
    String id,
    String name,
    String tag,
    String imageId,
    Map<String, String> labels,
    List<Bind> binds,
    Ports portBindings,
    List<ExposedPort> exposedPorts,
    boolean publishAllPorts,
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
