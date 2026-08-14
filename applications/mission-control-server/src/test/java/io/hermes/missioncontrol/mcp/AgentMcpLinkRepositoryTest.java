package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent↔MCP link table. {@link io.hermes.missioncontrol.docker.ContainerUpdateServiceTest}
 * reaches {@code onContainerReplaced} indirectly; everything else here — the upsert's
 * conflict clause, the per-server lookup that decides whether a catalog entry may be
 * deleted — had no test.
 */
class AgentMcpLinkRepositoryTest {

  private static final String HOST = "dh-local";
  private static final String CONTAINER = "c1";

  private SqliteTestDatabase database;
  private AgentMcpLinkRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new AgentMcpLinkRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static AgentMcpLink link(String profile, String alias, String serverId, long createdAt) {
    return new AgentMcpLink(HOST, CONTAINER, profile, alias, serverId, 1L, createdAt, 0L);
  }

  @Test
  void upsertUpdatesOnTheCompositeKeyAndPreservesTheOriginalCreatedAt() {
    repository.upsert(link("scout", "files", "mcp-1", 1_000L));
    repository.upsert(new AgentMcpLink(HOST, CONTAINER, "scout", "files", "mcp-2", 7L, 0L, 0L));

    List<AgentMcpLink> stored = repository.list(HOST, CONTAINER, "scout");
    assertEquals(1, stored.size(), "the second write created a row instead of updating");
    assertEquals("mcp-2", stored.getFirst().serverId());
    assertEquals(7L, stored.getFirst().syncedRevision());
    // created_at is the link's age; re-syncing must not reset it
    assertEquals(1_000L, stored.getFirst().createdAt());
  }

  @Test
  void aDifferentAliasOnTheSameProfileIsASecondRow() {
    repository.upsert(link("scout", "files", "mcp-1", 1L));
    repository.upsert(link("scout", "documents", "mcp-1", 2L));

    // one agent may mount the same server under two aliases
    assertEquals(2, repository.list(HOST, CONTAINER, "scout").size());
  }

  @Test
  void listIsScopedToOneProfileAndOrderedByAlias() {
    repository.upsert(link("scout", "zulu", "mcp-1", 1L));
    repository.upsert(link("scout", "alpha", "mcp-1", 2L));
    repository.upsert(link("archivist", "files", "mcp-1", 3L));

    assertEquals(
        List.of("alpha", "zulu"),
        repository.list(HOST, CONTAINER, "scout").stream().map(AgentMcpLink::alias).toList());
    assertEquals(1, repository.list(HOST, CONTAINER, "archivist").size());
  }

  @Test
  void findByServerCrossesEveryHostProfileAndContainer() {
    repository.upsert(link("scout", "files", "mcp-1", 1L));
    repository.upsert(link("archivist", "files", "mcp-1", 2L));
    repository.upsert(new AgentMcpLink("dh-remote", "c9", "scout", "files", "mcp-1", 1L, 3L, 0L));
    repository.upsert(link("scout", "other", "mcp-2", 4L));

    // this is the count that decides whether deleting a catalog entry is refused —
    // under-reporting here silently orphans an agent's MCP config
    assertEquals(3, repository.findByServer("mcp-1").size());
    assertEquals(1, repository.findByServer("mcp-2").size());
    assertTrue(repository.findByServer("mcp-absent").isEmpty());
  }

  @Test
  void deleteByAgentRemovesEveryAliasForThatProfileOnlyAndDeleteByServerSpansAgents() {
    repository.upsert(link("scout", "files", "mcp-1", 1L));
    repository.upsert(link("scout", "documents", "mcp-2", 2L));
    repository.upsert(link("archivist", "files", "mcp-1", 3L));

    repository.deleteByAgent(HOST, CONTAINER, "scout");
    assertTrue(repository.list(HOST, CONTAINER, "scout").isEmpty());
    assertEquals(1, repository.list(HOST, CONTAINER, "archivist").size());

    repository.deleteByServer("mcp-1");
    assertTrue(repository.list(HOST, CONTAINER, "archivist").isEmpty());
  }

  @Test
  void deletingOneAliasLeavesTheOthersAndFindIsEmptyForAnUnknownAlias() {
    repository.upsert(link("scout", "files", "mcp-1", 1L));
    repository.upsert(link("scout", "documents", "mcp-2", 2L));

    repository.delete(HOST, CONTAINER, "scout", "files");

    assertTrue(repository.find(HOST, CONTAINER, "scout", "files").isEmpty());
    assertEquals("mcp-2", repository.find(HOST, CONTAINER, "scout", "documents").orElseThrow().serverId());
    assertTrue(repository.find(HOST, CONTAINER, "scout", "never-existed").isEmpty());
  }

  @Test
  void onContainerReplacedIsScopedToOneHost() {
    repository.upsert(link("scout", "files", "mcp-1", 1L));
    repository.upsert(new AgentMcpLink("dh-remote", CONTAINER, "scout", "files", "mcp-1", 1L, 2L, 0L));

    // container ids are unique in practice, but the primary key is host-scoped and a
    // cross-host update would rewrite an unrelated agent's link
    assertEquals(1, repository.onContainerReplaced(HOST, CONTAINER, "c2"));

    assertEquals(1, repository.list(HOST, "c2", "scout").size());
    assertTrue(repository.list(HOST, CONTAINER, "scout").isEmpty());
    assertEquals(1, repository.list("dh-remote", CONTAINER, "scout").size());
  }
}
