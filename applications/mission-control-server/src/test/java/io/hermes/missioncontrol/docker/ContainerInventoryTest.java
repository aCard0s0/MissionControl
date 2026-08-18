package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.hermes.missioncontrol.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class ContainerInventoryTest {

  /** The Engine substitutes this for a reference it can no longer resolve. */
  private static final String BARE_IMAGE_ID =
      "sha256:e5b3a1c7d90f4b2e6a8c0d1f3b5a7c9e1d3f5b7a9c1e3d5f7b9a1c3e5d7f9b1c";

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerClient streamingClient = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final ContainerInventory subject = DockerWiring.inventory(clients, new AppProperties("live", "", "unix:///sock", "hermes/image", "hermes", "test"));

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
    when(clients.streamingForUrl("unix:///sock")).thenReturn(streamingClient);
  }

  @Test
  void parkedUpgradeLeftoversAreExcludedFromTheDefaultListingButVisibleWithAll() {
    stubListing(
        container("aaaaaaa1111", "/demo", "hermes/image:v1"),
        container("bbbbbbb2222", "/demo-mc-upgrade-0a1b2c3d", "hermes/image:v1"));

    // the parked original runs the same image as the live one, so only the name
    // tells them apart — showing both would offer the operator two identical cards
    assertEquals(List.of("demo"), names(subject.listContainers("unix:///sock", "local", false)));
    assertEquals(List.of("demo", "demo-mc-upgrade-0a1b2c3d"),
        names(subject.listContainers("unix:///sock", "local", true)));
  }

  @Test
  void onlyContainersOnTheConfiguredHermesRepositoryAreListed() {
    stubListing(
        container("aaaaaaa1111", "/demo", "hermes/image:v1"),
        container("bbbbbbb2222", "/postgres", "other/thing:v1"));

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertEquals("v1", fleet.get(0).version());
  }

  @Test
  void aDigestPinnedHermesContainerStillAppearsInTheFleet() {
    stubListing(container("aaaaaaa1111", "/demo",
        "hermes/image@sha256:9b2c1e4f6a8d0c3e5f7a9b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7d9f01"));

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    // the digest carries its own ':', so a repository comparison that does not strip
    // it first hides every container this dashboard pinned by digest
    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertEquals("latest", fleet.get(0).version());
  }

  @Test
  void aDockerHubQualifiedReferenceMatchesTheShortConfiguredForm() {
    stubListing(container("aaaaaaa1111", "/demo", "docker.io/hermes/image:v1"));

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    assertEquals(List.of("demo"), names(fleet));
    // the card still shows the reference the daemon reported, registry host included
    assertEquals("docker.io/hermes/image", fleet.get(0).image());
    assertEquals("v1", fleet.get(0).version());
  }

  @Test
  void theSubstringFilterMatchesImageOrNameWhenNoHermesImageIsConfigured() {
    ContainerInventory substringInventory = DockerWiring.inventory(clients, new AppProperties("live", "", "unix:///sock", "", "hermes", "test"));
    stubListing(
        container("aaaaaaa1111", "/alpha", "Acme/HERMES-agent:v1"),
        container("bbbbbbb2222", "/hermes-beta", "acme/other:v1"),
        container("ccccccc3333", "/gamma", "acme/other:v1"));

    List<ContainerDto> fleet = substringInventory.listContainers("unix:///sock", "local", false);

    // an operator who typed a lowercase filter still expects to see a container whose
    // image was published with capitals
    assertEquals(List.of("alpha", "hermes-beta"), names(fleet));
  }

  @Test
  void runningStoppedAndUnhealthyStatesAreMappedForTheStatusPill() {
    stubListing(
        containerInState("aaaaaaa1111", "/healthy", "running", "Up 2 hours"),
        containerInState("bbbbbbb2222", "/sick", "running", "Up 2 hours (unhealthy)"),
        containerInState("ccccccc3333", "/flapping", "restarting", "Restarting (1) 3 seconds ago"),
        containerInState("ddddddd4444", "/gone", "exited", "Exited (0) 5 minutes ago"),
        containerInState("eeeeeee5555", "/fresh", "created", "Created"),
        containerInState("fff11115555", "/held", "paused", "Up 2 hours (Paused)"),
        containerInState("aaabbbb6666", "/lost", "dead", "Dead"),
        containerInState("cccdddd7777", "/vanishing", "removing", "Removal In Progress"));
    stubStartedAt("aaaaaaa1111", "2026-08-14T10:00:00Z");
    // raced with a removal: the container is gone by the time uptime is read
    stubMissingOnInspect("bbbbbbb2222");

    Map<String, ContainerDto> fleet = byName(subject.listContainers("unix:///sock", "local", false));

    assertEquals("running", fleet.get("healthy").status());
    assertEquals("unhealthy", fleet.get("sick").status());
    assertEquals("unhealthy", fleet.get("flapping").status());
    assertEquals("stopped", fleet.get("gone").status());
    assertEquals("stopped", fleet.get("fresh").status());
    assertEquals("stopped", fleet.get("held").status());
    assertEquals("stopped", fleet.get("lost").status());
    assertEquals("unknown", fleet.get("vanishing").status());

    assertEquals(1786701600000L, fleet.get("healthy").startedAt());
    assertNull(fleet.get("gone").startedAt());
    // a failed inspect costs one card its uptime, never the whole fleet view
    assertNull(fleet.get("sick").startedAt());
  }

  @Test
  void theProfilesLabelIsSplitAndAnEmptyLabelYieldsNoProfiles() {
    Container seeded = container("aaaaaaa1111", "/seeded", "hermes/image:v1");
    when(seeded.getLabels()).thenReturn(Map.of("mc.profiles", "ops,research"));
    Container blank = container("bbbbbbb2222", "/blank", "hermes/image:v1");
    when(blank.getLabels()).thenReturn(Map.of("mc.profiles", "   "));
    Container otherLabels = container("ccccccc3333", "/plain", "hermes/image:v1");
    when(otherLabels.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    // deployed outside this dashboard, so the daemon reports no labels at all
    Container unlabelled = container("ddddddd4444", "/foreign", "hermes/image:v1");
    when(unlabelled.getLabels()).thenReturn(null);
    stubListing(seeded, blank, otherLabels, unlabelled);

    Map<String, ContainerDto> fleet = byName(subject.listContainers("unix:///sock", "local", false));

    assertEquals(List.of("ops", "research"), fleet.get("seeded").profiles());
    assertEquals(List.of(), fleet.get("blank").profiles());
    assertEquals(List.of(), fleet.get("plain").profiles());
    assertEquals(List.of(), fleet.get("foreign").profiles());
  }

  @Test
  void aContainerWithNoNamesIsStillListed() {
    Container nameless = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    when(nameless.getNames()).thenReturn(null);
    stubListing(nameless);

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    // a container mid-rename reports no name; dropping the whole listing over it
    // would blank the dashboard
    assertEquals(List.of("?"), names(fleet));
    assertEquals("aaaaaaa1111", fleet.get(0).id());
  }

  @Test
  void theShortIdIsTheFirstSevenCharactersOfTheContainerId() {
    stubListing(container("9f3c1a4e8b2d7c05", "/demo", "hermes/image:v1"));

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    assertEquals("9f3c1a4", fleet.get(0).shortId());
    assertEquals("9f3c1a4e8b2d7c05", fleet.get(0).id());
  }

  @Test
  void rootFilesystemSizeIsReportedInGibibytes() {
    Container sized = container("aaaaaaa1111", "/sized", "hermes/image:v1");
    when(sized.getSizeRootFs()).thenReturn(1_073_741_824L);
    // the daemon only reports size when asked, and answers null when it cannot
    Container unsized = container("bbbbbbb2222", "/unsized", "hermes/image:v1");
    when(unsized.getSizeRootFs()).thenReturn(null);
    stubListing(sized, unsized);

    Map<String, ContainerDto> fleet = byName(subject.listContainers("unix:///sock", "local", false));

    assertEquals(1.0, fleet.get("sized").sizeRootFsGb(), 1e-9);
    assertNull(fleet.get("unsized").sizeRootFsGb());
  }

  @Test
  void anAgentWeDeployedStaysInTheFleetAfterItsImageReferenceStopsResolving() {
    Container agent = container("aaaaaaa1111", "/demo", BARE_IMAGE_ID);
    when(agent.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(agent);

    List<ContainerDto> fleet = subject.listContainers("unix:///sock", "local", false);

    // moving a floating tag during another container's upgrade makes the Engine report this
    // one by image id; that parses as repository "sha256", which matches nothing
    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertNotEquals("sha256", fleet.get(0).image());
    assertNotEquals(BARE_IMAGE_ID.substring("sha256:".length()), fleet.get(0).version());
    assertEquals("", fleet.get(0).version());
  }

  @Test
  void anUnmanagedContainerReportingABareImageIdIsNotGuessedIntoTheFleet() {
    Container stray = container("bbbbbbb2222", "/stray", BARE_IMAGE_ID);
    when(stray.getLabels()).thenReturn(Map.of("mc.profiles", "ops"));
    Container agent = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    when(agent.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(agent, stray);

    // nothing ties this one to the configured repository, so claiming it runs Hermes
    // would invent an Agent the operator never deployed
    assertEquals(List.of("demo"), names(subject.listContainers("unix:///sock", "local", false)));
  }

  @Test
  void isImageIdReferenceRecognisesBothPrefixedAndBareDigests() {
    String hex = BARE_IMAGE_ID.substring("sha256:".length());

    assertTrue(ContainerInventory.isImageIdReference(BARE_IMAGE_ID));
    assertTrue(ContainerInventory.isImageIdReference(hex));
    assertFalse(ContainerInventory.isImageIdReference("hermes/image:v1"));
    assertFalse(ContainerInventory.isImageIdReference("hermes/image"));
    assertFalse(ContainerInventory.isImageIdReference(""));
    assertFalse(ContainerInventory.isImageIdReference(null));
  }

  @Test
  void bootstrapHelpersFromADeployAreNotShownAsAgents() {
    Container agent = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    Container helper = container("bbbbbbb2222", "/nostalgic_wozniak", "hermes/image:v1");
    when(helper.getLabels()).thenReturn(Map.of("mc.bootstrap", "true"));
    stubListing(agent, helper);

    // a seeding helper runs the Hermes image but is never an Agent; a stray left by a
    // failed deploy still has to be reachable through ?all=true so it can be reaped
    assertEquals(List.of("demo"), names(subject.listContainers("unix:///sock", "local", false)));
    assertEquals(List.of("demo", "nostalgic_wozniak"),
        names(subject.listContainers("unix:///sock", "local", true)));
  }

  private static List<String> names(List<ContainerDto> containers) {
    return containers.stream().map(ContainerDto::name).toList();
  }

  private static Map<String, ContainerDto> byName(List<ContainerDto> containers) {
    Map<String, ContainerDto> byName = new LinkedHashMap<>();
    for (ContainerDto dto : containers) {
      byName.put(dto.name(), dto);
    }
    return byName;
  }

  /** A stopped container — the one state that needs no inspect round-trip for uptime. */
  private static Container container(String id, String name, String image) {
    Container c = mock(Container.class);
    when(c.getId()).thenReturn(id);
    when(c.getNames()).thenReturn(new String[]{name});
    when(c.getImage()).thenReturn(image);
    when(c.getState()).thenReturn("exited");
    return c;
  }

  private static Container containerInState(String id, String name, String state, String statusText) {
    Container c = container(id, name, "hermes/image:v1");
    when(c.getState()).thenReturn(state);
    when(c.getStatus()).thenReturn(statusText);
    return c;
  }

  private void stubListing(Container... containers) {
    ListContainersCmd list = mock(ListContainersCmd.class, Answers.RETURNS_SELF);
    when(client.listContainersCmd()).thenReturn(list);
    when(list.exec()).thenReturn(List.of(containers));
  }

  private void stubStartedAt(String id, String iso) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    when(state.getStartedAt()).thenReturn(iso);
  }

  private void stubMissingOnInspect(String id) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenThrow(new NotFoundException("no such container"));
  }

  // ── pure mapping the fleet view depends on ───────────────────────────────

  @Test
  void aBareImageIdIsRecognisedWithAndWithoutItsPrefix() {
    // the Engine reports one once the stored reference stops resolving, and it is not a repository
    assertTrue(ContainerInventory.isImageIdReference(
        "sha256:e5b3f7c1a9d24b6e8f0a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f607"));
    assertTrue(ContainerInventory.isImageIdReference(
        "e5b3f7c1a9d24b6e8f0a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f607"));
    // an ordinary reference, a short hex string and nothing at all are not image ids
    assertFalse(ContainerInventory.isImageIdReference("hermes/agent:latest"));
    assertFalse(ContainerInventory.isImageIdReference("deadbeef"));
    assertFalse(ContainerInventory.isImageIdReference("sha256:notevenhexnotevenhexnotevenhex00"));
    assertFalse(ContainerInventory.isImageIdReference(null));
    assertFalse(ContainerInventory.isImageIdReference("   "));
  }

  @Test
  void everyEngineStateMapsToOneOfTheFourTheDashboardRenders() {
    assertEquals("running", ContainerInventory.mapStatus("running", "Up 3 hours"));
    // the health suffix is the only way the Engine reports an unhealthy container in a list
    assertEquals("unhealthy", ContainerInventory.mapStatus("running", "Up 3 hours (unhealthy)"));
    assertEquals("unhealthy", ContainerInventory.mapStatus("restarting", "Restarting (1)"));
    assertEquals("stopped", ContainerInventory.mapStatus("exited", "Exited (0)"));
    assertEquals("stopped", ContainerInventory.mapStatus("created", ""));
    assertEquals("stopped", ContainerInventory.mapStatus("paused", "Paused"));
    assertEquals("stopped", ContainerInventory.mapStatus("dead", "Dead"));
    assertEquals("unknown", ContainerInventory.mapStatus("removing", ""));
    assertEquals("unknown", ContainerInventory.mapStatus(null, null));
    // the Engine capitalises inconsistently across versions
    assertEquals("running", ContainerInventory.mapStatus("Running", "Up 1 second"));
  }

  @Test
  void aRunningContainerWithNoStatusTextIsStillRunning() {
    assertEquals("running", ContainerInventory.mapStatus("running", null));
  }

  @Test
  void aContainerWeDeployedIsShownWhateverItsImageReferenceReadsAs() {
    // the Engine substitutes a bare image id once the stored reference stops resolving — which
    // another Agent's upgrade does by moving a floating tag — and an id matches no repository
    Container managed = container("aaaaaaa1111", "/demo", BARE_IMAGE_ID);
    when(managed.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(managed);

    assertEquals(List.of("demo"), names(subject.listContainers("unix:///sock", "local", false)));
  }

  @Test
  void aContainerReportingAnImageIdThatWeDidNotDeployIsNotShown() {
    Container foreign = container("bbbbbbb2222", "/someone-elses", BARE_IMAGE_ID);
    stubListing(foreign);

    assertTrue(names(subject.listContainers("unix:///sock", "local", false)).isEmpty());
  }

  @Test
  void aBootstrapHelperIsNeverAnAgent() {
    Container helper = container("ccccccc3333", "/mc-seed-demo", "hermes/image:v1");
    when(helper.getLabels()).thenReturn(Map.of("mc.bootstrap", "true"));
    stubListing(helper);

    assertTrue(names(subject.listContainers("unix:///sock", "local", false)).isEmpty());
  }
}
