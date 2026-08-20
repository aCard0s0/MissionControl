package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.core.DockerClientConfig;
import org.junit.jupiter.api.Test;

/**
 * The Engine's spelling of a Linux capability, as the inspect response carries it.
 *
 * <p>Docker Compose normalises {@code cap_add: [NET_ADMIN]} to {@code "CAP_NET_ADMIN"} when it
 * creates the container, and the Engine hands that back verbatim. docker-java's
 * {@link Capability} enum is named without the prefix, so a client that cannot map the prefixed
 * form fails the whole inspect — which took down the MCP reachability check, since the container
 * it attaches to the MCP network is the netns owner, and under the Tailscale flavor that owner
 * is the tailscale container with {@code CAP_NET_ADMIN}.
 *
 * <p>Pinned against the mapper the client actually decodes with rather than a fresh one: the
 * behaviour under test is a {@code @JsonCreator} that only exists in docker-java 3.7+, and a
 * dependency downgrade should break here rather than in the browser.
 */
class DockerInspectCapabilityWireTest {

  /** Trimmed to the field in question — the Engine sends far more, and unknown keys are ignored. */
  private static final String INSPECT_WITH_PREFIXED_CAP = """
      {"Id":"abc123","HostConfig":{"CapAdd":["CAP_NET_ADMIN"],"CapDrop":["CAP_MKNOD"]}}""";

  /** The pre-normalisation spelling, still what an operator writes and what `--cap-add` takes. */
  private static final String INSPECT_WITH_BARE_CAP = """
      {"Id":"abc123","HostConfig":{"CapAdd":["NET_ADMIN"]}}""";

  @Test
  void theCapPrefixedFormTheEngineReportsDeserializes() throws Exception {
    InspectContainerResponse inspected = DockerClientConfig.getDefaultObjectMapper()
        .readValue(INSPECT_WITH_PREFIXED_CAP, InspectContainerResponse.class);

    assertArrayEquals(
        new Capability[] {Capability.NET_ADMIN}, inspected.getHostConfig().getCapAdd());
    assertArrayEquals(
        new Capability[] {Capability.MKNOD}, inspected.getHostConfig().getCapDrop());
  }

  @Test
  void theBareFormStillDeserializes() throws Exception {
    InspectContainerResponse inspected = DockerClientConfig.getDefaultObjectMapper()
        .readValue(INSPECT_WITH_BARE_CAP, InspectContainerResponse.class);

    assertArrayEquals(
        new Capability[] {Capability.NET_ADMIN}, inspected.getHostConfig().getCapAdd());
  }
}
