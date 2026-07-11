package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.StreamType;
import io.hermes.missioncontrol.AppProperties;
import io.hermes.missioncontrol.web.ResourceConflictException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class DockerGatewayTest {

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final DockerGateway gateway = new DockerGateway(
      clients, new AppProperties("live", "", "unix:///sock", "hermes/image", "hermes", "test"), dockerExec);

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
  }

  @Test
  void profileNormalizationDropsDefaultAndDuplicates() {
    assertEquals(List.of("ops", "research"),
        DockerGateway.normalizeProfiles(List.of("default", "ops", "ops", "research")));
  }

  @Test
  void logParserSplitsLinesSkipsTimestampOnlyAndPreservesWarningSeverity() {
    Frame frame = new Frame(StreamType.STDERR, ("2026-07-10T17:14:39.902148126Z\n"
        + "2026-07-10T17:14:40.303717876Z WARNING tools.mcp: connection failed with error\n"
        + "2026-07-10T17:14:41.303717876Z PermissionError: denied\n"
        + "2026-07-10T17:14:42.303717876Z s6-rc: info: service started\n")
        .getBytes(StandardCharsets.UTF_8));

    List<LogLineDto> lines = DockerGateway.parseLogFrame(frame);

    assertEquals(3, lines.size());
    assertEquals("warn", lines.get(0).level());
    assertEquals("error", lines.get(1).level());
    assertEquals("info", lines.get(2).level());
    assertEquals(1783703680303L, lines.get(0).ts());
  }

  @Test
  void existingManagedVolumeIsAConflict() {
    InspectVolumeCmd inspect = mock(InspectVolumeCmd.class);
    when(client.inspectVolumeCmd("mc-hermes-demo")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(mock(InspectVolumeResponse.class));

    assertThrows(ResourceConflictException.class,
        () -> gateway.deploy("unix:///sock", "dh-local", "demo", "latest", List.of()));
  }

  @Test
  void failedSeedRollsBackContainerAndVolume() {
    stubMissingVolume("mc-hermes-demo");
    CreateVolumeCmd createVolume = mock(CreateVolumeCmd.class, Answers.RETURNS_SELF);
    when(client.createVolumeCmd()).thenReturn(createVolume);
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd profile = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, profile);
    CreateContainerResponse initCreated = mock(CreateContainerResponse.class);
    CreateContainerResponse profileCreated = mock(CreateContainerResponse.class);
    when(initCreated.getId()).thenReturn("init-id");
    when(profileCreated.getId()).thenReturn("profile-id");
    when(init.exec()).thenReturn(initCreated);
    when(profile.exec()).thenReturn(profileCreated);
    stubOneShot("init-id", 0);
    stubOneShot("profile-id", 1);
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);

    assertThrows(RuntimeException.class,
        () -> gateway.deploy("unix:///sock", "dh-local", "demo", "latest", List.of("ops")));
    verify(removeVolume).exec();
  }

  @Test
  void oneShotInitializationTimeoutIsBoundedAndCleanedUp() {
    CreateContainerCmd helper = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(helper);
    CreateContainerResponse created = mock(CreateContainerResponse.class);
    when(created.getId()).thenReturn("helper-id");
    when(helper.exec()).thenReturn(created);
    stubOneShot("helper-id", null);

    RuntimeException failure = assertThrows(RuntimeException.class,
        () -> gateway.runOneShot(client, "hermes/image:latest", HostConfig.newHostConfig(),
            List.of("true"), "initialize Hermes data volume"));

    assertEquals("initialize Hermes data volume timed out", failure.getMessage());
    verify(client).removeContainerCmd("helper-id");
  }

  @Test
  void failedReadinessRemovesNewContainerAndManagedVolume() {
    stubMissingVolume("mc-hermes-demo");
    CreateVolumeCmd createVolume = mock(CreateVolumeCmd.class, Answers.RETURNS_SELF);
    when(client.createVolumeCmd()).thenReturn(createVolume);
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, main);
    CreateContainerResponse initCreated = mock(CreateContainerResponse.class);
    CreateContainerResponse mainCreated = mock(CreateContainerResponse.class);
    when(initCreated.getId()).thenReturn("init-id");
    when(mainCreated.getId()).thenReturn("main-id");
    when(init.exec()).thenReturn(initCreated);
    when(main.exec()).thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    StartContainerCmd startMain = mock(StartContainerCmd.class);
    when(client.startContainerCmd("main-id")).thenReturn(startMain);
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd("main-id")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    when(state.getRunning()).thenReturn(true);
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(new RuntimeException("unreadable profile"));
    RemoveContainerCmd removeMain = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeContainerCmd("main-id")).thenReturn(removeMain);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);

    assertThrows(RuntimeException.class,
        () -> gateway.deploy("unix:///sock", "dh-local", "demo", "latest", List.of()));

    verify(removeMain).exec();
    verify(removeVolume).exec();
  }

  @Test
  void permanentRemovalDeletesRecordedManagedVolume() {
    InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    when(client.inspectContainerCmd("cid")).thenReturn(inspectCmd);
    when(inspectCmd.exec()).thenReturn(inspected);
    when(inspected.getConfig()).thenReturn(config);
    when(config.getLabels()).thenReturn(Map.of(
        "mc.managed", "true", "mc.dataVolume", "mc-hermes-demo"));
    RemoveContainerCmd removeContainer = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeContainerCmd("cid")).thenReturn(removeContainer);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);

    gateway.remove("unix:///sock", "cid");

    verify(removeContainer).exec();
    verify(removeVolume).exec();
  }

  @Test
  void connectsAnAgentContainerToTheManagedMcpNetwork() {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    NetworkSettings settings = mock(NetworkSettings.class);
    when(client.inspectContainerCmd("agent-id")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getNetworkSettings()).thenReturn(settings);
    when(settings.getNetworks()).thenReturn(Map.of());

    ListNetworksCmd listNetworks = mock(ListNetworksCmd.class, Answers.RETURNS_SELF);
    Network network = mock(Network.class);
    when(network.getName()).thenReturn("mission-control-mcp-net");
    when(network.getId()).thenReturn("network-id");
    when(client.listNetworksCmd()).thenReturn(listNetworks);
    when(listNetworks.exec()).thenReturn(List.of(network));
    ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, Answers.RETURNS_SELF);
    when(client.connectToNetworkCmd()).thenReturn(connect);

    gateway.connectNetwork("unix:///sock", "agent-id", "mission-control-mcp-net");

    verify(connect).withContainerId("agent-id");
    verify(connect).withNetworkId("network-id");
    verify(connect).exec();
  }

  private void stubMissingVolume(String name) {
    InspectVolumeCmd inspect = mock(InspectVolumeCmd.class);
    when(client.inspectVolumeCmd(name)).thenReturn(inspect);
    when(inspect.exec()).thenThrow(new NotFoundException("missing"));
  }

  private void stubOneShot(String id, Integer exitCode) {
    StartContainerCmd start = mock(StartContainerCmd.class);
    WaitContainerCmd wait = mock(WaitContainerCmd.class);
    WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
    RemoveContainerCmd remove = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.startContainerCmd(id)).thenReturn(start);
    when(client.waitContainerCmd(id)).thenReturn(wait);
    when(wait.start()).thenReturn(callback);
    when(callback.awaitStatusCode(90, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(exitCode);
    when(client.removeContainerCmd(id)).thenReturn(remove);
  }
}
