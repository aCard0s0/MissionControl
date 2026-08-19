package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RenameContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import io.hermes.missioncontrol.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;

/**
 * Reading a managed container back before it is replaced.
 *
 * <p>An upgrade recreates the container against the <em>existing</em> data volume, so every
 * refusal in {@code inspectManaged} is what keeps someone else's container — or a container of
 * ours running a different image — from being reattached to Hermes' profiles, souls and
 * credentials. The spec it produces is then the only record of what the replacement must
 * reproduce: a field read wrong here is a setting silently dropped on upgrade.
 */
class ContainerUpgraderInspectTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///sock");
  private static final String ID = "abc123def456";
  /** The port a profile's webhook listener binds, which is why an Agent gets published at all. */
  private static final ExposedPort WEBHOOK_PORT = ExposedPort.tcp(8644);

  private DockerClients clients;
  private DockerClient client;
  private ImageStore images;
  private DockerNetworks networks;
  private ContainerUpgrader upgrader;

  @BeforeEach
  void setUp() {
    clients = mock(DockerClients.class);
    client = mock(DockerClient.class);
    images = mock(ImageStore.class);
    networks = mock(DockerNetworks.class);
    when(clients.forUrl(HOST.url())).thenReturn(client);
    upgrader = new ContainerUpgrader(clients,
        new AppProperties("", "unix:///sock", "hermes/agent", "hermes", "test", true),
        images, mock(DeploymentReadiness.class), networks);
  }

  // ── refusals ────────────────────────────────────────────────────────────

  @Test
  void aContainerMissionControlDidNotCreateIsRefused() {
    // the label is the only evidence that the data volume below belongs to us
    inspectReturns(container(Map.of(), null, null, null));
    assertEquals("not a Mission Control-managed container", refused());

    inspectReturns(container(Map.of("mc.managed", "false"), null, null, null));
    assertEquals("not a Mission Control-managed container", refused());
  }

  @Test
  void aContainerWithNoConfigOrNoLabelsAtAllIsRefused() {
    // the daemon returns a config-less inspect for some container states
    InspectContainerResponse noConfig = mock(InspectContainerResponse.class);
    when(noConfig.getConfig()).thenReturn(null);
    inspectReturns(noConfig);
    assertEquals("not a Mission Control-managed container", refused());

    ContainerConfig config = mock(ContainerConfig.class);
    when(config.getLabels()).thenReturn(null);
    InspectContainerResponse noLabels = mock(InspectContainerResponse.class);
    when(noLabels.getConfig()).thenReturn(config);
    inspectReturns(noLabels);
    assertEquals("not a Mission Control-managed container", refused());
  }

  @Test
  void aManagedContainerWithNoRecognisableDataVolumeIsRefused() {
    // without it the replacement would come up with an empty /opt/data
    inspectReturns(container(managedLabels(null), null, null, null));
    assertEquals("container has no recorded managed data volume", refused());

    inspectReturns(container(managedLabels("someone-elses-volume"), null, null, null));
    assertEquals("container has no recorded managed data volume", refused());
  }

  @Test
  void aContainerRunningSomeOtherImageIsRefused() {
    ContainerConfig config = mock(ContainerConfig.class);
    when(config.getLabels()).thenReturn(managedLabels("mc-hermes-demo"));
    when(config.getImage()).thenReturn("someone/else:1.2");
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    when(inspected.getConfig()).thenReturn(config);
    inspectReturns(inspected);

    assertEquals("container does not run the configured Hermes image", refused());
  }

  // ── the spec it produces ────────────────────────────────────────────────

  @Test
  void everySettingTheReplacementNeedsIsCarriedOnTheSpec() {
    ContainerConfig config = mock(ContainerConfig.class);
    when(config.getLabels()).thenReturn(managedLabels("mc-hermes-demo"));
    when(config.getImage()).thenReturn("hermes/agent:1.2");
    when(config.getCmd()).thenReturn(new String[] {"gateway", "run"});
    when(config.getEntrypoint()).thenReturn(new String[] {"/init"});
    when(config.getEnv()).thenReturn(new String[] {"TZ=UTC"});
    when(config.getUser()).thenReturn("hermes");
    when(config.getWorkingDir()).thenReturn("/opt/data");
    HostConfig hostConfig = HostConfig.newHostConfig()
        .withBinds(Bind.parse("mc-hermes-demo:/opt/data"))
        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
        .withNetworkMode("bridge");
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    when(inspected.getConfig()).thenReturn(config);
    when(inspected.getHostConfig()).thenReturn(hostConfig);
    when(inspected.getId()).thenReturn(ID);
    when(inspected.getName()).thenReturn("/mc-hermes-demo");
    InspectContainerResponse.ContainerState state = runningState(true);
    when(inspected.getState()).thenReturn(state);
    inspectReturns(inspected);

    ManagedContainerSpec spec = upgrader.inspectManaged(HOST, ID);

    assertEquals("mc-hermes-demo", spec.name(), "the daemon's leading slash is stripped");
    assertEquals("1.2", spec.tag());
    assertEquals("mc-hermes-demo", spec.dataVolume());
    assertEquals(List.of("gateway", "run"), spec.cmd());
    assertEquals(List.of("/init"), spec.entrypoint());
    assertEquals(List.of("TZ=UTC"), spec.env());
    assertEquals("hermes", spec.user());
    assertEquals("/opt/data", spec.workingDir());
    assertEquals("bridge", spec.primaryNetwork());
    assertEquals(1, spec.binds().size());
    assertTrue(spec.wasRunning());
  }

  @Test
  void aPortAnOperatorPublishedByHandIsPartOfTheSpec() {
    // Mission Control publishes nothing for an Agent, so a mapping on one is the operator's
    // own — the documented way to reach a profile's webhook listener from outside the docker
    // network. It is a create-time setting, so it only survives if it is read here.
    inspectReturns(container(managedLabels("mc-hermes-demo"),
        HostConfig.newHostConfig()
            .withPortBindings(new Ports(WEBHOOK_PORT, Ports.Binding.bindIpAndPort("127.0.0.1", 8644)))
            .withPublishAllPorts(true),
        null, null));

    ManagedContainerSpec spec = upgrader.inspectManaged(HOST, ID);

    assertEquals(1, spec.portBindings().getBindings().size());
    assertTrue(spec.publishAllPorts());
  }

  @Test
  void aContainerWithNothingOptionalSetProducesAUsableSpecAnyway() {
    // a hand-created container may carry no host config, no cmd and no state at all
    inspectReturns(container(managedLabels("mc-hermes-demo"), null, null, null));

    ManagedContainerSpec spec = upgrader.inspectManaged(HOST, ID);

    assertEquals("", spec.name(), "a nameless container is empty, not null");
    assertNull(spec.cmd());
    assertNull(spec.entrypoint());
    assertNull(spec.env());
    assertNull(spec.primaryNetwork());
    assertNull(spec.restartPolicy());
    assertNull(spec.portBindings(), "no host config means nothing was published");
    assertTrue(spec.exposedPorts().isEmpty());
    assertFalse(spec.publishAllPorts());
    assertTrue(spec.binds().isEmpty());
    assertFalse(spec.wasRunning(), "no state means not running");
    assertTrue(spec.extraNetworks().isEmpty());
  }

  @Test
  void aStoppedContainerIsRecordedAsStoppedSoItComesBackParked() {
    inspectReturns(container(managedLabels("mc-hermes-demo"), null, runningState(false), null));

    assertFalse(upgrader.inspectManaged(HOST, ID).wasRunning());
  }

  // ── which networks have to be reattached by hand ────────────────────────

  @Test
  void onlyUserDefinedNetworksBesidesThePrimaryHaveToBeReattached() {
    // the primary and the built-ins come back with a new container; a user-defined one
    // (notably the managed MCP network) does not, so it has to be recorded here
    Map<String, ContainerNetwork> attached = new LinkedHashMap<>();
    attached.put("bridge", network("alias-bridge"));
    attached.put("host", network());
    attached.put("mission-control-mcp-net", network("mc-hermes-demo", ID.substring(0, 6)));
    inspectReturns(container(managedLabels("mc-hermes-demo"),
        HostConfig.newHostConfig().withNetworkMode("bridge"), runningState(true), attached));

    Map<String, List<String>> reattach = upgrader.inspectManaged(HOST, ID).extraNetworks();

    assertEquals(List.of("mission-control-mcp-net"), List.copyOf(reattach.keySet()));
    // the daemon's auto short-id alias would collide with the replacement's own
    assertEquals(List.of("mc-hermes-demo"), reattach.get("mission-control-mcp-net"));
  }

  @Test
  void aNetworkWithNoAliasesOrNoSettingsAtAllIsToleratedRatherThanFailingTheUpgrade() {
    Map<String, ContainerNetwork> attached = new LinkedHashMap<>();
    attached.put("mission-control-mcp-net", network());
    inspectReturns(container(managedLabels("mc-hermes-demo"), null, null, attached));
    assertEquals(List.of(), upgrader.inspectManaged(HOST, ID).extraNetworks()
        .get("mission-control-mcp-net"));

    InspectContainerResponse noNetworkSettings =
        container(managedLabels("mc-hermes-demo"), null, null, null);
    when(noNetworkSettings.getNetworkSettings()).thenReturn(null);
    inspectReturns(noNetworkSettings);
    assertTrue(upgrader.inspectManaged(HOST, ID).extraNetworks().isEmpty());
  }

  // ── what the replacement is created with ────────────────────────────────

  @Test
  void theReplacementIsCreatedWithEverySettingTheOriginalHad() {
    CreateContainerCmd create = upgradeHarness(true);

    upgrader.upgrade(HOST, ID, "1.3");

    verify(create).withCmd(List.of("gateway", "run"));
    verify(create).withEntrypoint(List.of("/init"));
    verify(create).withEnv(List.of("TZ=UTC"));
    verify(create).withUser("hermes");
    verify(create).withWorkingDir("/opt/data");
    verify(create).withExposedPorts(List.of(WEBHOOK_PORT));
    // the user-defined network is reconnected by hand with its aliases
    verify(networks).connect(HOST, "new-id", "mission-control-mcp-net", List.of("mc-hermes-demo"));
    verify(client).startContainerCmd("new-id");
  }

  @Test
  void anOperatorsOwnPortMappingSurvivesTheReplacement() {
    // Docker cannot add a mapping to a running container, so an upgrade that dropped it would
    // silently un-expose the operator's webhook listener with no way back but recreating the
    // container by hand — and nothing on the page would say the routes had stopped arriving.
    CreateContainerCmd create = upgradeHarness(true, true);

    upgrader.upgrade(HOST, ID, "1.3");

    HostConfig replacement = hostConfigOf(create);
    Ports.Binding[] bound = replacement.getPortBindings().getBindings().get(WEBHOOK_PORT);
    assertEquals(1, bound.length);
    assertEquals("127.0.0.1", bound[0].getHostIp());
    assertEquals("8644", bound[0].getHostPortSpec());
    assertTrue(replacement.getPublishAllPorts(), "`docker run -P` is carried over as well");
  }

  @Test
  void aReplacementForAContainerWithNothingOptionalSetOmitsThoseSettings() {
    // passing null to withCmd/withUser would be a different container, not the same one
    CreateContainerCmd create = upgradeHarness(false);

    upgrader.upgrade(HOST, ID, "1.3");

    verify(create, never()).withCmd(any(List.class));
    verify(create, never()).withEntrypoint(any(List.class));
    verify(create, never()).withEnv(any(List.class));
    verify(create, never()).withUser(anyString());
    verify(create, never()).withWorkingDir(anyString());
    verify(create, never()).withExposedPorts(any(List.class));
    assertNull(hostConfigOf(create).getPortBindings());
    assertNull(hostConfigOf(create).getPublishAllPorts());
    // it was not running, so the replacement stays parked
    verify(client, never()).startContainerCmd(anyString());
  }

  /** The host config the replacement was actually created with. */
  private static HostConfig hostConfigOf(CreateContainerCmd create) {
    ArgumentCaptor<HostConfig> captured = ArgumentCaptor.forClass(HostConfig.class);
    verify(create).withHostConfig(captured.capture());
    return captured.getValue();
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private String refused() {
    return assertThrows(IllegalArgumentException.class, () -> upgrader.inspectManaged(HOST, ID))
        .getMessage();
  }

  private void inspectReturns(InspectContainerResponse inspected) {
    InspectContainerCmd cmd = mock(InspectContainerCmd.class);
    when(client.inspectContainerCmd(anyString())).thenReturn(cmd);
    when(cmd.exec()).thenReturn(inspected);
  }

  private static Map<String, String> managedLabels(String dataVolume) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("mc.managed", "true");
    if (dataVolume != null) labels.put("mc.dataVolume", dataVolume);
    return labels;
  }

  private static InspectContainerResponse container(
      Map<String, String> labels, HostConfig hostConfig,
      InspectContainerResponse.ContainerState state, Map<String, ContainerNetwork> attached) {
    ContainerConfig config = mock(ContainerConfig.class);
    when(config.getLabels()).thenReturn(labels);
    when(config.getImage()).thenReturn("hermes/agent:1.2");
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    when(inspected.getConfig()).thenReturn(config);
    when(inspected.getHostConfig()).thenReturn(hostConfig);
    when(inspected.getId()).thenReturn(ID);
    when(inspected.getState()).thenReturn(state);
    if (attached != null) {
      NetworkSettings settings = mock(NetworkSettings.class);
      when(settings.getNetworks()).thenReturn(attached);
      when(inspected.getNetworkSettings()).thenReturn(settings);
    }
    return inspected;
  }

  private static InspectContainerResponse.ContainerState runningState(boolean running) {
    InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
    when(state.getRunning()).thenReturn(running);
    return state;
  }

  private static ContainerNetwork network(String... aliases) {
    ContainerNetwork network = mock(ContainerNetwork.class);
    when(network.getAliases()).thenReturn(aliases.length == 0 ? null : List.of(aliases));
    return network;
  }

  /**
   * The whole upgrade path with the daemon substituted. {@code fullySpecified} decides whether
   * the original carried the optional settings, which is what the replacement has to mirror.
   */
  private CreateContainerCmd upgradeHarness(boolean fullySpecified) {
    return upgradeHarness(fullySpecified, false);
  }

  private CreateContainerCmd upgradeHarness(boolean fullySpecified, boolean publishAllPorts) {
    ContainerConfig config = mock(ContainerConfig.class);
    when(config.getLabels()).thenReturn(managedLabels("mc-hermes-demo"));
    when(config.getImage()).thenReturn("hermes/agent:1.2");
    if (fullySpecified) {
      when(config.getCmd()).thenReturn(new String[] {"gateway", "run"});
      when(config.getEntrypoint()).thenReturn(new String[] {"/init"});
      when(config.getEnv()).thenReturn(new String[] {"TZ=UTC"});
      when(config.getUser()).thenReturn("hermes");
      when(config.getWorkingDir()).thenReturn("/opt/data");
      when(config.getExposedPorts()).thenReturn(new ExposedPort[] {WEBHOOK_PORT});
    } else {
      when(config.getUser()).thenReturn("   ");
      when(config.getWorkingDir()).thenReturn("");
    }

    Map<String, ContainerNetwork> attached = new LinkedHashMap<>();
    attached.put("mission-control-mcp-net", network("mc-hermes-demo"));
    NetworkSettings settings = mock(NetworkSettings.class);
    when(settings.getNetworks()).thenReturn(attached);

    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    when(inspected.getConfig()).thenReturn(config);
    when(inspected.getId()).thenReturn(ID);
    when(inspected.getName()).thenReturn("/mc-hermes-demo");
    when(inspected.getImageId()).thenReturn("sha256:current");
    when(inspected.getNetworkSettings()).thenReturn(settings);
    InspectContainerResponse.ContainerState state = runningState(fullySpecified);
    when(inspected.getState()).thenReturn(state);
    HostConfig hostConfig = fullySpecified
        ? HostConfig.newHostConfig()
            .withBinds(Bind.parse("mc-hermes-demo:/opt/data"))
            .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
            .withPortBindings(new Ports(WEBHOOK_PORT, Ports.Binding.bindIpAndPort("127.0.0.1", 8644)))
        : null;
    if (publishAllPorts) {
      hostConfig = (hostConfig == null ? HostConfig.newHostConfig() : hostConfig)
          .withPublishAllPorts(true);
    }
    when(inspected.getHostConfig()).thenReturn(hostConfig);
    inspectReturns(inspected);

    // the target tag is already present locally and resolves to a different image id, so the
    // upgrade is neither a no-op nor a pull
    InspectImageResponse target = mock(InspectImageResponse.class);
    when(target.getId()).thenReturn("sha256:new");
    InspectImageCmd inspectImage = mock(InspectImageCmd.class);
    when(inspectImage.exec()).thenReturn(target);
    when(client.inspectImageCmd(anyString())).thenReturn(inspectImage);
    when(images.hermesRepository()).thenReturn("hermes/agent");

    CreateContainerCmd create = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse created = mock(CreateContainerResponse.class);
    when(created.getId()).thenReturn("new-id");
    when(create.exec()).thenReturn(created);
    when(client.createContainerCmd(anyString())).thenReturn(create);

    when(client.renameContainerCmd(anyString())).thenReturn(mock(RenameContainerCmd.class, Answers.RETURNS_SELF));
    when(client.stopContainerCmd(anyString())).thenReturn(mock(StopContainerCmd.class, Answers.RETURNS_SELF));
    when(client.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class, Answers.RETURNS_SELF));
    when(client.removeContainerCmd(anyString())).thenReturn(mock(RemoveContainerCmd.class, Answers.RETURNS_SELF));
    return create;
  }

  @Test
  void anUpgradeToTheTagItAlreadyRunsIsRefused() {
    // recreating the container for no reason would drop every Agent's session
    CreateContainerCmd create = upgradeHarness(true);
    InspectImageResponse same = mock(InspectImageResponse.class);
    when(same.getId()).thenReturn("sha256:current");
    InspectImageCmd inspectImage = mock(InspectImageCmd.class);
    when(inspectImage.exec()).thenReturn(same);
    when(client.inspectImageCmd(anyString())).thenReturn(inspectImage);

    assertThrows(io.hermes.missioncontrol.errors.ResourceConflictException.class,
        () -> upgrader.upgrade(HOST, ID, "1.2"));

    verify(create, never()).exec();
  }

  @Test
  void anImageThatIsNotPresentLocallyIsPulledFirst() {
    // pulling before the container is touched means a bad tag costs nothing
    CreateContainerCmd create = upgradeHarness(true);
    InspectImageCmd inspectImage = mock(InspectImageCmd.class);
    when(inspectImage.exec())
        .thenThrow(new com.github.dockerjava.api.exception.NotFoundException("no such image"))
        .thenReturn(newImage());
    when(client.inspectImageCmd(anyString())).thenReturn(inspectImage);

    upgrader.upgrade(HOST, ID, "1.3");

    verify(images).pull(HOST, "hermes/agent", "1.3");
    verify(create).exec();
  }

  @Test
  void aFailedReplacementIsRemovedAndTheOriginalIsPutBack() {
    // both containers mount the same data volume and run the gateway; starting the original
    // while the replacement survives puts two gateways on one profile tree
    upgradeHarness(true);
    when(client.startContainerCmd("new-id"))
        .thenThrow(new IllegalStateException("replacement will not start"));

    assertThrows(IllegalStateException.class, () -> upgrader.upgrade(HOST, ID, "1.3"));

    verify(client).removeContainerCmd("new-id");
    verify(client, times(2)).renameContainerCmd(ID);   // parked aside, then renamed back
    verify(client).startContainerCmd(ID);
  }

  private static InspectImageResponse newImage() {
    InspectImageResponse image = mock(InspectImageResponse.class);
    when(image.getId()).thenReturn("sha256:new");
    return image;
  }
}
