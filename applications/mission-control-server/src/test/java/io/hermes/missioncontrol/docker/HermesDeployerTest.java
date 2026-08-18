package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * What a deploy that actually reaches the end builds: the labels, bind and restart
 * policy the running Agent inherits, and the readiness gate it has to clear.
 */
class HermesDeployerTest {

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  /** Endpoints that stream or long-poll (/wait, image pull) run on their own client. */
  private final DockerClient streamingClient = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final HermesDeployer subject = DockerWiring.deployer(clients, new AppProperties("", "unix:///sock", "hermes/image", "hermes", "test", true), dockerExec);

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
    when(clients.streamingForUrl("unix:///sock")).thenReturn(streamingClient);
  }

  @Test
  void aSuccessfulDeployLabelsBindsAndStartsTheContainer() {
    stubMissingVolume("mc-hermes-demo");
    CreateVolumeCmd createVolume = stubCreateVolume();
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd seed = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse seedCreated = createdWithId("seed-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, seed, main);
    when(init.exec()).thenReturn(initCreated);
    when(seed.exec()).thenReturn(seedCreated);
    when(main.exec()).thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    stubOneShot("seed-id", 0);
    StartContainerCmd start = stubStart("main-id");
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(true);

    String containerId = subject.deploy(
        "unix:///sock", "dh-local", "demo", "latest", List.of("ops"));

    assertEquals("main-id", containerId);
    verify(createVolume).withName("mc-hermes-demo");
    verify(main).withName("demo");
    verify(start).exec();

    ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
    verify(main).withLabels(labels.capture());
    // every later operation reads these back off the daemon: mc.managed is what makes
    // upgrade and permanent-remove agree to touch this container at all, and
    // mc.dataVolume is the only record of which volume holds its credentials
    assertEquals(
        Map.of("mc.managed", "true", "mc.profiles", "ops", "mc.dataVolume", "mc-hermes-demo"),
        labels.getValue());

    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(main).withHostConfig(hostConfig.capture());
    // the gateway must mount the same volume the one-shots just seeded, writable —
    // mounted read-only or not at all, the Agent starts on an empty config
    assertEquals("mc-hermes-demo:/opt/data:rw", hostConfig.getValue().getBinds()[0].toString());
    // without this an operator's daemon restart leaves the Agent down for good
    assertEquals(RestartPolicy.unlessStoppedRestart(), hostConfig.getValue().getRestartPolicy());
    verify(main).withCmd(List.of("gateway", "run"));
  }

  @Test
  void eachSeedProfileGetsItsOwnOneShotContainer() {
    stubMissingVolume("mc-hermes-demo");
    stubCreateVolume();
    // one fluent mock stands in for every create so the commands can be read back in order
    CreateContainerCmd create = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse opsCreated = createdWithId("ops-id");
    CreateContainerResponse researchCreated = createdWithId("research-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(create);
    when(create.exec()).thenReturn(initCreated, opsCreated, researchCreated, mainCreated);
    stubOneShot("init-id", 0);
    stubOneShot("ops-id", 0);
    stubOneShot("research-id", 0);
    stubStart("main-id");
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(true);

    String containerId = subject.deploy(
        "unix:///sock", "dh-local", "demo", "latest", List.of("ops", "research"));

    assertEquals("main-id", containerId);

    ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
    verify(create, times(4)).withCmd(commands.capture());
    // seeding runs while the gateway is still stopped, so the order matters: the init
    // one-shot creates the default profile the named ones are cut from, and only then
    // is the long-running gateway created
    assertEquals(List.of(
        List.of("true"),
        List.of("profile", "create", "ops", "--no-alias"),
        List.of("profile", "create", "research", "--no-alias"),
        List.of("gateway", "run")), commands.getAllValues());
  }

  @Test
  void aMissingImageIsPulledThenTheCreateIsRetried() throws InterruptedException {
    stubMissingVolume("mc-hermes-demo");
    stubCreateVolume();
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, main);
    when(init.exec()).thenReturn(initCreated);
    // the tag exists in the registry but not on this daemon yet
    when(main.exec())
        .thenThrow(new NotFoundException("no such image: hermes/image:latest"))
        .thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    PullImageCmd pull = mock(PullImageCmd.class, Answers.RETURNS_SELF);
    PullImageResultCallback pullCallback = mock(PullImageResultCallback.class);
    when(streamingClient.pullImageCmd("hermes/image")).thenReturn(pull);
    when(pull.exec(any(PullImageResultCallback.class))).thenReturn(pullCallback);
    when(pullCallback.awaitCompletion(180, TimeUnit.SECONDS)).thenReturn(true);
    stubStart("main-id");
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(true);

    String containerId = subject.deploy("unix:///sock", "dh-local", "demo", "latest", List.of());

    assertEquals("main-id", containerId);
    // the repository must be pulled without the tag glued on twice, and at the tag
    // the create asked for — pulling ':latest' here would deploy the wrong build
    verify(pull).withTag("latest");
    verify(pull).exec(any(PullImageResultCallback.class));
    verify(main, times(2)).exec();
  }

  @Test
  void aContainerThatNeverStartsFailsReadinessBeforeAnyExec() {
    stubMissingVolume("mc-hermes-demo");
    stubCreateVolume();
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, main);
    when(init.exec()).thenReturn(initCreated);
    when(main.exec()).thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    stubStart("main-id");
    // the image started and immediately died — a bad entrypoint, an unusable volume
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(false);
    stubRemoveContainer("main-id");
    stubRemoveVolume("mc-hermes-demo");

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> subject.deploy("unix:///sock", "dh-local", "demo", "latest", List.of()));

    assertTrue(failure.getMessage().contains("exited before readiness checks"), failure.getMessage());
    // an exec against a dead container blocks or reports a confusing daemon error
    // instead of the real cause, so the state check has to come first
    verifyNoInteractions(dockerExec);
  }

  @Test
  void aContainerThatDiesDuringReadinessRollsBackTheContainerAndVolume() {
    stubMissingVolume("mc-hermes-demo");
    stubCreateVolume();
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, main);
    when(init.exec()).thenReturn(initCreated);
    when(main.exec()).thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    stubStart("main-id");
    // alive when readiness starts, gone by the time the script returns: the exec's
    // own success says nothing about whether the gateway survived it
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(true, false);
    RemoveContainerCmd removeMain = stubRemoveContainer("main-id");
    RemoveVolumeCmd removeVolume = stubRemoveVolume("mc-hermes-demo");

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> subject.deploy("unix:///sock", "dh-local", "demo", "latest", List.of()));

    assertTrue(failure.getMessage().contains("stopped during readiness checks"), failure.getMessage());
    // a half-deployed Agent left behind would block the next deploy on its volume
    verify(removeMain).exec();
    verify(removeVolume).exec();
  }

  @Test
  void theDefaultProfileIsAlwaysValidatedEvenWhenNoSeedProfilesWereRequested() {
    stubMissingVolume("mc-hermes-demo");
    stubCreateVolume();
    CreateContainerCmd init = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerCmd main = mock(CreateContainerCmd.class, Answers.RETURNS_SELF);
    CreateContainerResponse initCreated = createdWithId("init-id");
    CreateContainerResponse mainCreated = createdWithId("main-id");
    when(client.createContainerCmd("hermes/image:latest")).thenReturn(init, main);
    when(init.exec()).thenReturn(initCreated);
    when(main.exec()).thenReturn(mainCreated);
    stubOneShot("init-id", 0);
    stubStart("main-id");
    ContainerState state = stubState("main-id");
    when(state.getRunning()).thenReturn(true);

    subject.deploy("unix:///sock", "dh-local", "demo", "latest", List.of());

    ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
    verify(dockerExec).runAsUser(eq("unix:///sock"), eq("main-id"), eq("hermes"), command.capture(),
        anyString(), anyBoolean(), anyBoolean(), any(Duration.class));

    List<String> argv = command.getValue();
    assertEquals(List.of("sh", "-c"), argv.subList(0, 2));
    // "_" is the stand-in $0, so anything after it is what the script's "$@" loop reads;
    // drop it and the first profile name is swallowed and never checked
    assertEquals("_", argv.get(3));
    // a deploy with no named profiles still has the default one, and it is the profile
    // the gateway itself serves — an unreadable config there is a dead Agent
    assertEquals(List.of("default"), argv.subList(4, argv.size()));
  }

  private static CreateContainerResponse createdWithId(String id) {
    CreateContainerResponse created = mock(CreateContainerResponse.class);
    when(created.getId()).thenReturn(id);
    return created;
  }

  private void stubMissingVolume(String name) {
    InspectVolumeCmd inspect = mock(InspectVolumeCmd.class);
    when(client.inspectVolumeCmd(name)).thenReturn(inspect);
    when(inspect.exec()).thenThrow(new NotFoundException("missing"));
  }

  private CreateVolumeCmd stubCreateVolume() {
    CreateVolumeCmd createVolume = mock(CreateVolumeCmd.class, Answers.RETURNS_SELF);
    when(client.createVolumeCmd()).thenReturn(createVolume);
    return createVolume;
  }

  private void stubOneShot(String id, Integer exitCode) {
    StartContainerCmd start = mock(StartContainerCmd.class);
    WaitContainerCmd wait = mock(WaitContainerCmd.class);
    WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
    RemoveContainerCmd remove = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.startContainerCmd(id)).thenReturn(start);
    when(streamingClient.waitContainerCmd(id)).thenReturn(wait);
    when(wait.start()).thenReturn(callback);
    when(callback.awaitStatusCode(90, TimeUnit.SECONDS)).thenReturn(exitCode);
    when(client.removeContainerCmd(id)).thenReturn(remove);
  }

  private StartContainerCmd stubStart(String id) {
    StartContainerCmd start = mock(StartContainerCmd.class);
    when(client.startContainerCmd(id)).thenReturn(start);
    return start;
  }

  private ContainerState stubState(String id) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    return state;
  }

  private RemoveContainerCmd stubRemoveContainer(String id) {
    RemoveContainerCmd remove = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd(id)).thenReturn(remove);
    return remove;
  }

  private RemoveVolumeCmd stubRemoveVolume(String name) {
    RemoveVolumeCmd remove = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd(name)).thenReturn(remove);
    return remove;
  }

  // ── the profiles a deploy seeds ──────────────────────────────────────────

  @Test
  void theProfileListIsNormalisedBeforeAnythingIsSeeded() {
    // the list becomes a series of seeding execs; a blank would create a profile named '' and a
    // duplicate would seed the same one twice. 'default' is dropped: the image already has it,
    // and seeding it again would overwrite a profile the operator may have configured.
    assertEquals(List.of(), HermesDeployer.normalizeProfiles(null));
    assertEquals(List.of(), HermesDeployer.normalizeProfiles(List.of()));
    assertEquals(List.of(), HermesDeployer.normalizeProfiles(java.util.Arrays.asList("  ", "", null)));
    assertEquals(List.of(), HermesDeployer.normalizeProfiles(List.of("default")));
    assertEquals(List.of("ops", "scout"),
        HermesDeployer.normalizeProfiles(List.of(" ops ", "scout", "ops", "default")));
  }
}
