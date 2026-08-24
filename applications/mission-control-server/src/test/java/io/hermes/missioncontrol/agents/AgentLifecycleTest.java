package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The two agent writes that span the container and the dashboard's own SQLite, and the order
 * between their halves.
 *
 * <p>Both used to be asserted through MockMvc, because both were two statements in a
 * controller handler.
 *
 * <p>Also what happens when the SQLite half fails after the container half has landed: the
 * cleanup is retried, a persistent failure does not fail a request for work that succeeded,
 * and a failure of the container half is still a failure.
 */
class AgentLifecycleTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");
  private static final String CONTAINER = "c1";

  private final HermesProfiles profiles = mock(HermesProfiles.class);
  private final AgentMcpCatalogService mcpCatalog = mock(AgentMcpCatalogService.class);
  private final AgentLifecycle lifecycle = new AgentLifecycle(profiles, mcpCatalog);

  @Test
  void deletingAProfileAlsoDropsItsCatalogLinks() {
    // the links are dashboard-owned rows keyed by profile name; leaving them behind would
    // resurrect MCP entries on a later profile that happens to reuse the name
    lifecycle.delete(HOST, CONTAINER, "scout");

    InOrder order = inOrder(profiles, mcpCatalog);
    order.verify(profiles).delete(HOST, CONTAINER, "scout");
    order.verify(mcpCatalog).deleteAgentLinks(HOST, CONTAINER, "scout");
  }

  @Test
  void removingAServerForgetsItsCatalogLinkAfterTheProfileWrite() {
    // the order matters: if the link were dropped first and the profile write then failed, the
    // entry would be left in config.yaml with nothing recording where it came from
    when(profiles.removeMcpServer(HOST, CONTAINER, "scout", "files")).thenReturn(profile());
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(call -> call.getArgument(1));

    assertEquals("scout", lifecycle.removeMcpServer(HOST, CONTAINER, "scout", "files").name());

    InOrder order = inOrder(profiles, mcpCatalog);
    order.verify(profiles).removeMcpServer(HOST, CONTAINER, "scout", "files");
    order.verify(mcpCatalog).forgetLink(HOST, CONTAINER, "scout", "files");
  }

  @Test
  void aRemovalAnswersWithTheProfileAsTheRemainingLinksLeaveIt() {
    // the response is what the page redraws from, so it has to carry the catalog overlay
    // rather than the raw hermes read the profile write handed back
    AgentProfileDto enriched = profile();
    when(profiles.removeMcpServer(any(), anyString(), anyString(), anyString()))
        .thenReturn(profile());
    when(mcpCatalog.enrich(eq(HOST), any())).thenReturn(enriched);

    assertEquals(enriched, lifecycle.removeMcpServer(HOST, CONTAINER, "scout", "files"));
  }

  // ── the cleanup that runs after the container write has landed ────────────

  @Test
  void aCleanupThatLosesOneRaceIsRetriedRatherThanGivenUpOn() {
    // SQLite here is single-writer with a pool of one, so a brief lock is the realistic cause
    doThrow(new RuntimeException("database is locked")).doNothing()
        .when(mcpCatalog).deleteAgentLinks(HOST, CONTAINER, "scout");

    lifecycle.delete(HOST, CONTAINER, "scout");

    verify(mcpCatalog, times(2)).deleteAgentLinks(HOST, CONTAINER, "scout");
  }

  @Test
  void aCleanupThatKeepsFailingDoesNotFailARequestWhoseContainerWriteLanded() {
    // the profile is gone; answering 500 would say it is not. The link row left behind is
    // reachable afterwards — delete tolerates a profile that is already gone, so a retry
    // reaches the cleanup, and enrich drops a link whose entry is no longer on the profile
    doThrow(new RuntimeException("database is locked"))
        .when(mcpCatalog).deleteAgentLinks(HOST, CONTAINER, "scout");

    lifecycle.delete(HOST, CONTAINER, "scout");

    verify(profiles).delete(HOST, CONTAINER, "scout");
    verify(mcpCatalog, times(2)).deleteAgentLinks(HOST, CONTAINER, "scout");
  }

  @Test
  void theSameHoldsForAnMcpEntryRemoval() {
    when(profiles.removeMcpServer(HOST, CONTAINER, "scout", "files")).thenReturn(profile());
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(call -> call.getArgument(1));
    doThrow(new RuntimeException("database is locked"))
        .when(mcpCatalog).forgetLink(HOST, CONTAINER, "scout", "files");

    assertEquals("scout", lifecycle.removeMcpServer(HOST, CONTAINER, "scout", "files").name());

    verify(mcpCatalog, times(2)).forgetLink(HOST, CONTAINER, "scout", "files");
  }

  @Test
  void aFailedContainerWriteIsStillAFailedRequest() {
    // only the cleanup is best-effort: the half this is trading away has already succeeded
    doThrow(new IllegalStateException("hermes refused")).when(profiles).delete(HOST, CONTAINER, "scout");

    assertThrows(IllegalStateException.class, () -> lifecycle.delete(HOST, CONTAINER, "scout"));

    verifyNoInteractions(mcpCatalog);
  }

  private static AgentProfileDto profile() {
    return new AgentProfileDto("c1--scout", CONTAINER, "scout", "Profile", "idle",
        "anthropic", "claude-opus-5", "", "/opt/data", "", "", "", List.of(), List.of(),
        List.of(), 0L);
  }
}
