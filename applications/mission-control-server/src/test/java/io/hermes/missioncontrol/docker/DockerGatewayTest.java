package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.command.RenameContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
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
import org.mockito.ArgumentCaptor;

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

  // ── image updates ────────────────────────────────────────────────────────

  @Test
  void upgradeRefusesAContainerThisDashboardDoesNotManage() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, Map.of(), Map.of());

    assertThrows(IllegalArgumentException.class,
        () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));

    // an unmanaged container must not be stopped, renamed, or recreated
    verify(client, never()).stopContainerCmd(anyString());
    verify(client, never()).renameContainerCmd(anyString());
  }

  @Test
  void upgradeRefusesAContainerWithoutARecordedDataVolume() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true,
        Map.of("mc.managed", "true"), Map.of());

    assertThrows(IllegalArgumentException.class,
        () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));
    verify(client, never()).renameContainerCmd(anyString());
  }

  @Test
  void upgradeRefusesAContainerRunningAnotherImage() {
    stubManagedInspect("cid", "someone/else:v1", true, managedLabels(), Map.of());

    assertThrows(IllegalArgumentException.class,
        () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));
    verify(client, never()).renameContainerCmd(anyString());
  }

  @Test
  void upgradeRejectsATagTheContainerAlreadyRuns() {
    stubManagedInspect("cid", "hermes/image:v2026.8.3", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "image-sha");

    assertThrows(ResourceConflictException.class,
        () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));
    verify(client, never()).renameContainerCmd(anyString());
  }

  @Test
  void upgradeKeepsTheDataVolumeAndDoesNotReseedProfiles() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubRename("cid");
    stubStop("cid");
    CreateContainerCmd create = stubCreate("hermes/image:v2026.8.3", "new-id");
    stubStartAndReady("new-id");
    RemoveContainerCmd removeOld = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd("cid")).thenReturn(removeOld);

    DockerGateway.UpgradeResult result = gateway.upgrade("unix:///sock", "cid", "v2026.8.3");

    assertEquals("new-id", result.newContainerId());
    assertEquals("v2026.7.1", result.fromTag());
    assertEquals("v2026.8.3", result.toTag());
    // the volume holds every profile, soul and credential — it must survive
    verify(client, never()).removeVolumeCmd(anyString());
    verify(client, never()).createVolumeCmd();
    // no waitContainerCmd means no one-shot ran, so no profile was re-seeded
    verify(client, never()).waitContainerCmd(anyString());
    verify(removeOld).exec();

    // the replacement must actually carry the old container's identity forward —
    // dropping any of these leaves a running agent that has lost its credentials,
    // its data volume, or its managed-by markers, and every assertion above passes
    verify(create).withName("demo");
    verify(create).withLabels(managedLabels());
    verify(create).withEnv(List.of("ANTHROPIC_API_KEY=sk-secret", "TZ=UTC"));
    verify(create).withUser("hermes");
    verify(create).withWorkingDir("/opt/data");

    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(create).withHostConfig(hostConfig.capture());
    assertEquals("mc-hermes-demo:/opt/data:rw", hostConfig.getValue().getBinds()[0].toString());
    assertEquals(RestartPolicy.unlessStoppedRestart(), hostConfig.getValue().getRestartPolicy());
  }

  @Test
  void upgradeReattachesNonDefaultNetworks() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(),
        Map.of("bridge", networkWith(), "mission-control-mcp-net", networkWith("demo")));
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubRename("cid");
    stubStop("cid");
    stubCreate("hermes/image:v2026.8.3", "new-id");
    stubStartAndReady("new-id");
    when(client.removeContainerCmd("cid")).thenReturn(mock(RemoveContainerCmd.class, Answers.RETURNS_SELF));

    ListNetworksCmd listNetworks = mock(ListNetworksCmd.class, Answers.RETURNS_SELF);
    Network network = mock(Network.class);
    when(network.getName()).thenReturn("mission-control-mcp-net");
    when(network.getId()).thenReturn("network-id");
    when(client.listNetworksCmd()).thenReturn(listNetworks);
    when(listNetworks.exec()).thenReturn(List.of(network));
    ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, Answers.RETURNS_SELF);
    when(client.connectToNetworkCmd()).thenReturn(connect);

    gateway.upgrade("unix:///sock", "cid", "v2026.8.3");

    // losing this membership would silently break every catalog-linked MCP server
    verify(connect).withContainerId("new-id");
    verify(connect).withNetworkId("network-id");
    verify(connect).exec();
  }

  @Test
  void failedReadinessRestoresTheOriginalContainer() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    RenameContainerCmd rename = stubRename("cid");
    stubStop("cid");
    stubCreate("hermes/image:v2026.8.3", "new-id");
    StartContainerCmd startNew = mock(StartContainerCmd.class);
    when(client.startContainerCmd("new-id")).thenReturn(startNew);
    stubRunningState("new-id");
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(new RuntimeException("gateway never came up"));
    RemoveContainerCmd removeNew = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd("new-id")).thenReturn(removeNew);
    StartContainerCmd restartOld = mock(StartContainerCmd.class);
    when(client.startContainerCmd("cid")).thenReturn(restartOld);

    assertThrows(RuntimeException.class, () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));

    verify(removeNew).exec();
    verify(rename).withName("demo");        // renamed back off the parked name
    verify(restartOld).exec();
    verify(client, never()).removeVolumeCmd(anyString());
    verify(client, never()).removeContainerCmd("cid");
  }

  @Test
  void aStoppedContainerComesBackStopped() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", false, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubRename("cid");
    stubCreate("hermes/image:v2026.8.3", "new-id");
    when(client.removeContainerCmd("cid")).thenReturn(mock(RemoveContainerCmd.class, Answers.RETURNS_SELF));

    DockerGateway.UpgradeResult result = gateway.upgrade("unix:///sock", "cid", "v2026.8.3");

    assertFalse(result.running());
    // an operator parked this container on purpose; an update must not start it
    verify(client, never()).startContainerCmd("new-id");
    verify(client, never()).stopContainerCmd(anyString());
    verifyNoInteractions(dockerExec);
  }

  @Test
  void aRenameFailureRestoresTheStoppedContainer() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubStop("cid");
    RenameContainerCmd rename = mock(RenameContainerCmd.class, Answers.RETURNS_SELF);
    when(client.renameContainerCmd("cid")).thenReturn(rename);
    // a leftover name from an earlier crashed upgrade is the realistic cause; the
    // rollback's rename-back then succeeds
    org.mockito.Mockito.doThrow(new RuntimeException("name already in use"))
        .doNothing()
        .when(rename).exec();
    StartContainerCmd restartOld = mock(StartContainerCmd.class);
    when(client.startContainerCmd("cid")).thenReturn(restartOld);

    assertThrows(RuntimeException.class, () -> gateway.upgrade("unix:///sock", "cid", "v2026.8.3"));

    // the container was stopped before the rename was attempted, so failing there must
    // still put it back — otherwise the Agent stays down with nothing scheduled to fix it
    verify(restartOld).exec();
    verify(client, never()).removeContainerCmd("cid");
    verify(client, never()).removeVolumeCmd(anyString());
  }

  @Test
  void aTransientRemovalFailureDoesNotDestroyTheValidatedReplacement() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubRename("cid");
    stubStop("cid");
    stubCreate("hermes/image:v2026.8.3", "new-id");
    stubStartAndReady("new-id");
    RemoveContainerCmd removeOld = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd("cid")).thenReturn(removeOld);
    when(removeOld.exec()).thenThrow(new RuntimeException("removal already in progress"));
    RemoveContainerCmd removeNew = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd("new-id")).thenReturn(removeNew);

    // the replacement is created, started and validated by this point: the upgrade
    // succeeded, and clearing away the parked original is only cleanup
    DockerGateway.UpgradeResult result = gateway.upgrade("unix:///sock", "cid", "v2026.8.3");

    assertEquals("new-id", result.newContainerId());
    verify(removeNew, never()).exec();
    verify(client, never()).startContainerCmd("cid");
  }

  @Test
  void anAlreadyStoppedContainerDoesNotAbortTheUpgrade() {
    stubManagedInspect("cid", "hermes/image:v2026.7.1", true, managedLabels(), Map.of());
    stubImage("hermes/image:v2026.8.3", "new-sha");
    stubRename("cid");
    StopContainerCmd stop = mock(StopContainerCmd.class, Answers.RETURNS_SELF);
    when(client.stopContainerCmd("cid")).thenReturn(stop);
    // raced with a manual stop: the desired state is already the actual one
    when(stop.exec()).thenThrow(new NotModifiedException("container already stopped"));
    stubCreate("hermes/image:v2026.8.3", "new-id");
    stubStartAndReady("new-id");
    when(client.removeContainerCmd("cid")).thenReturn(mock(RemoveContainerCmd.class, Answers.RETURNS_SELF));

    DockerGateway.UpgradeResult result = gateway.upgrade("unix:///sock", "cid", "v2026.8.3");

    assertEquals("new-id", result.newContainerId());
    assertTrue(result.running());
  }

  @Test
  void containersParkedByAnInterruptedUpgradeAreHiddenFromTheFleet() {
    assertTrue(DockerGateway.isUpgradeLeftover("demo-mc-upgrade-0a1b2c3d"));
    assertFalse(DockerGateway.isUpgradeLeftover("demo"));
    assertFalse(DockerGateway.isUpgradeLeftover("demo-mc-upgrade-nothex"));
  }

  private static Map<String, String> managedLabels() {
    return Map.of("mc.managed", "true", "mc.dataVolume", "mc-hermes-demo", "mc.profiles", "ops");
  }

  private static ContainerNetwork networkWith(String... aliases) {
    ContainerNetwork network = mock(ContainerNetwork.class);
    when(network.getAliases()).thenReturn(List.of(aliases));
    return network;
  }

  private void stubManagedInspect(
      String id, String image, boolean running,
      Map<String, String> labels, Map<String, ContainerNetwork> networks) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    ContainerState state = mock(ContainerState.class);
    NetworkSettings settings = mock(NetworkSettings.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getId()).thenReturn(id);
    when(inspected.getName()).thenReturn("/demo");
    when(inspected.getImageId()).thenReturn("image-sha");
    when(inspected.getConfig()).thenReturn(config);
    when(inspected.getState()).thenReturn(state);
    when(inspected.getNetworkSettings()).thenReturn(settings);
    when(inspected.getHostConfig()).thenReturn(HostConfig.newHostConfig()
        .withBinds(new Bind("mc-hermes-demo", new Volume("/opt/data"), AccessMode.rw))
        .withRestartPolicy(RestartPolicy.unlessStoppedRestart()));
    when(config.getImage()).thenReturn(image);
    when(config.getLabels()).thenReturn(labels);
    when(config.getCmd()).thenReturn(new String[]{"gateway", "run"});
    when(config.getEnv()).thenReturn(new String[]{"ANTHROPIC_API_KEY=sk-secret", "TZ=UTC"});
    when(config.getUser()).thenReturn("hermes");
    when(config.getWorkingDir()).thenReturn("/opt/data");
    when(state.getRunning()).thenReturn(running);
    when(settings.getNetworks()).thenReturn(networks);
  }

  private void stubImage(String image, String imageId) {
    InspectImageCmd inspect = mock(InspectImageCmd.class);
    InspectImageResponse response = mock(InspectImageResponse.class);
    when(client.inspectImageCmd(image)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(response);
    when(response.getId()).thenReturn(imageId);
  }

  private RenameContainerCmd stubRename(String id) {
    RenameContainerCmd rename = mock(RenameContainerCmd.class, Answers.RETURNS_SELF);
    when(client.renameContainerCmd(id)).thenReturn(rename);
    return rename;
  }

  private void stubStop(String id) {
    StopContainerCmd stop = mock(StopContainerCmd.class, Answers.RETURNS_SELF);
    when(client.stopContainerCmd(id)).thenReturn(stop);
  }

  private CreateContainerCmd stubCreate(String image, String newId) {
    CreateContainerCmd create = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse created = mock(CreateContainerResponse.class);
    when(client.createContainerCmd(image)).thenReturn(create);
    when(create.exec()).thenReturn(created);
    when(created.getId()).thenReturn(newId);
    return create;
  }

  private void stubRunningState(String id) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    when(state.getRunning()).thenReturn(true);
  }

  private void stubStartAndReady(String id) {
    StartContainerCmd start = mock(StartContainerCmd.class);
    when(client.startContainerCmd(id)).thenReturn(start);
    stubRunningState(id);
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
