package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import io.hermes.missioncontrol.AppProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class DockerGatewayNetworkAndImageTest {

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final DockerGateway gateway = new DockerGateway(
      clients, new AppProperties("live", "", "unix:///sock", "hermes/image", "hermes", "test"), dockerExec);

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
  }

  // ── network attachment ───────────────────────────────────────────────────

  @Test
  void aBlankNetworkNameIsRejectedBeforeTheDaemonIsCalled() {
    assertThrows(IllegalArgumentException.class,
        () -> gateway.connectNetwork("unix:///sock", "agent-id", null));
    assertThrows(IllegalArgumentException.class,
        () -> gateway.connectNetwork("unix:///sock", "agent-id", "  "));

    // a blank name would list every network on the host and attach to an arbitrary one
    verifyNoInteractions(client);
  }

  @Test
  void connectingANetworkTheContainerAlreadyHasIsANoOp() {
    stubInspect("agent-id", inspectedOn("mission-control-mcp-net"));

    gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net");

    verify(client, never()).connectToNetworkCmd();
    verify(client, never()).listNetworksCmd();
  }

  @Test
  void aLostConnectRaceIsSwallowedWhenTheNetworkEndsUpAttachedAnyway() {
    // two dashboards linking the same catalog server at once: the loser is told the
    // endpoint already exists, but the container is on the network — which is the
    // outcome the caller asked for, so the request must still succeed
    stubInspect("agent-id", inspectedOn(), inspectedOn("mission-control-mcp-net"));
    ConnectToNetworkCmd connect = stubResolvedNetwork("mission-control-mcp-net", "network-id");
    when(connect.exec()).thenThrow(new RuntimeException("endpoint already exists in network"));

    assertDoesNotThrow(
        () -> gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net"));

    verify(connect).exec();
  }

  @Test
  void aGenuineConnectFailureStillPropagates() {
    stubInspect("agent-id", inspectedOn(), inspectedOn());
    ConnectToNetworkCmd connect = stubResolvedNetwork("mission-control-mcp-net", "network-id");
    RuntimeException refused = new RuntimeException("daemon out of address space");
    when(connect.exec()).thenThrow(refused);

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net"));

    assertSame(refused, thrown);
  }

  @Test
  void anUnknownNetworkNameIsReportedAsNotFound() {
    stubInspect("agent-id", inspectedOn());
    ListNetworksCmd listNetworks = mock(ListNetworksCmd.class, Answers.RETURNS_SELF);
    Network sibling = mock(Network.class);
    // the Engine's name filter matches substrings, so a longer sibling comes back for a
    // network that does not exist — attaching to it would join the wrong network
    when(sibling.getName()).thenReturn("mission-control-mcp-net-staging");
    when(client.listNetworksCmd()).thenReturn(listNetworks);
    when(listNetworks.exec()).thenReturn(List.of(sibling));

    assertThrows(NotFoundException.class,
        () -> gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net"));

    verify(client, never()).connectToNetworkCmd();
  }

  @Test
  void requestedAliasesArePreservedOnTheNewAttachment() {
    stubInspect("agent-id", inspectedOn());
    ConnectToNetworkCmd connect = stubResolvedNetwork("mission-control-mcp-net", "network-id");

    gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net", List.of("demo"));

    // catalog-linked MCP servers dial the agent by its alias; losing it on a
    // reattachment leaves a container that is on the network but unreachable
    ArgumentCaptor<ContainerNetwork> endpoint = ArgumentCaptor.forClass(ContainerNetwork.class);
    verify(connect).withContainerNetwork(endpoint.capture());
    assertEquals(List.of("demo"), endpoint.getValue().getAliases());
    verify(connect).exec();
  }

  // ── local image inventory ────────────────────────────────────────────────

  @Test
  void localTagsCountOnlyTheConfiguredHermesRepositoryAndSkipDanglingImages() {
    stubImages(
        imageTagged("hermes/image:v1"),
        imageTagged("<none>:<none>"),
        imageTagged("other/image:v9"),
        imageTagged("docker.io/hermes/image:v2"));

    // v2 is the same repository written in its registry-qualified form, and offering
    // 'other/image' tags as Hermes upgrades would deploy an unrelated image
    assertEquals(Set.of("v1", "v2"), gateway.localImageTags("unix:///sock"));
  }

  @Test
  void anImageWithNoRepoTagsIsSkipped() {
    Image untagged = mock(Image.class);
    when(untagged.getRepoTags()).thenReturn((String[]) null);
    stubImages(untagged, imageTagged("hermes/image:v1"));

    // an intermediate build layer carries no tags; it must not cut the scan short
    assertEquals(Set.of("v1"), gateway.localImageTags("unix:///sock"));
  }

  @Test
  void noHermesImageConfiguredYieldsNoLocalTagsAndNeverAsksTheDaemon() {
    DockerGateway unconfigured = new DockerGateway(
        clients, new AppProperties("live", "", "unix:///sock", "", "hermes", "test"), dockerExec);

    assertEquals(Set.of(), unconfigured.localImageTags("unix:///sock"));
    verifyNoInteractions(client);
  }

  /** Successive daemon answers for inspecting a container, in call order. */
  private void stubInspect(
      String containerId, InspectContainerResponse first, InspectContainerResponse... rest) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    when(client.inspectContainerCmd(containerId)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(first, rest);
  }

  private static InspectContainerResponse inspectedOn(String... networkNames) {
    Map<String, ContainerNetwork> networks = new HashMap<>();
    for (String name : networkNames) {
      networks.put(name, new ContainerNetwork());
    }
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    NetworkSettings settings = mock(NetworkSettings.class);
    when(inspected.getNetworkSettings()).thenReturn(settings);
    when(settings.getNetworks()).thenReturn(networks);
    return inspected;
  }

  private ConnectToNetworkCmd stubResolvedNetwork(String networkName, String networkId) {
    Network network = mock(Network.class);
    when(network.getName()).thenReturn(networkName);
    when(network.getId()).thenReturn(networkId);
    ListNetworksCmd listNetworks = mock(ListNetworksCmd.class, Answers.RETURNS_SELF);
    when(client.listNetworksCmd()).thenReturn(listNetworks);
    when(listNetworks.exec()).thenReturn(List.of(network));
    ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, Answers.RETURNS_SELF);
    when(client.connectToNetworkCmd()).thenReturn(connect);
    return connect;
  }

  private void stubImages(Image... images) {
    ListImagesCmd listImages = mock(ListImagesCmd.class, Answers.RETURNS_SELF);
    when(client.listImagesCmd()).thenReturn(listImages);
    when(listImages.exec()).thenReturn(List.of(images));
  }

  private static Image imageTagged(String... repoTags) {
    Image image = mock(Image.class);
    when(image.getRepoTags()).thenReturn(repoTags);
    return image;
  }
}
