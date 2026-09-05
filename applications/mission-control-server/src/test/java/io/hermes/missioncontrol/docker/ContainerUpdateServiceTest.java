package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.board.BoardRepository;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ContainerUpdateServiceTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");
  private static final String OLD_ID = "old-container-id";
  private static final String NEW_ID = "new-container-id";

  private SqliteTestDatabase database;
  private JdbcTemplate jdbc;
  private BoardRepository board;
  private AgentMcpLinkRepository links;
  private DockerGateway docker;
  private DataSourceTransactionManager transactions;
  private ContainerUpdateService service;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    jdbc = database.jdbc();
    board = new BoardRepository(jdbc, new ObjectMapper());
    links = new AgentMcpLinkRepository(jdbc);
    docker = mock(DockerGateway.class);
    transactions = new DataSourceTransactionManager(database.dataSource());

    service = new ContainerUpdateService(docker, List.of(board, links), transactions);
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private void stubUpgrade() {
    when(docker.upgrade(any(), anyString(), anyString(), any()))
        .thenReturn(new UpgradeResult(OLD_ID, NEW_ID, "v2026.7.1", "v2026.8.3", true));
  }

  private void seedBoardTask(String containerId) {
    jdbc.update("""
        INSERT INTO board_tasks (id, container_id, agent_id, title, col, priority, tags, created_at)
        VALUES (?, ?, 'ops', 'ship it', 'queued', 'med', '', 1)
        """, "task-" + containerId, containerId);
  }

  private void seedLink(String hostId, String containerId) {
    jdbc.update("""
        INSERT INTO mcp_servers
          (id, name, description, kind, host_id, service_key, config_json, desired_state,
           runtime_state, operation_state, revision, applied_revision, created_at, updated_at)
        VALUES ('srv-1', 'files', '', 'external', NULL, NULL, '{}', 'running',
                'running', 'idle', 1, 1, 1, 1)
        """);
    links.upsert(new AgentMcpLink(hostId, containerId, "default", "files", "srv-1", 1, 1, 1));
  }

  @Test
  void movesBoardTasksAndMcpLinksOntoTheReplacementContainer() {
    stubUpgrade();
    seedBoardTask(OLD_ID);
    seedLink(HOST.id(), OLD_ID);

    assertEquals(NEW_ID, service.update(HOST, OLD_ID, "v2026.8.3"));

    assertEquals(1, board.findByContainer(NEW_ID).size());
    assertTrue(board.findByContainer(OLD_ID).isEmpty());
    assertEquals(1, links.list(HOST.id(), NEW_ID, "default").size());
    assertTrue(links.list(HOST.id(), OLD_ID, "default").isEmpty());
  }

  @Test
  void leavesLinksOnOtherHostsAlone() {
    stubUpgrade();
    seedLink(HOST.id(), OLD_ID);
    // a different daemon can legitimately hold a link row with the same container id
    links.upsert(new AgentMcpLink("dh-remote", OLD_ID, "default", "files", "srv-1", 1, 1, 1));

    service.update(HOST, OLD_ID, "v2026.8.3");

    assertEquals(1, links.list("dh-remote", OLD_ID, "default").size());
    assertTrue(links.list(HOST.id(), OLD_ID, "default").isEmpty());
  }

  @Test
  void anUpdateWithNothingToRemapStillSucceeds() {
    stubUpgrade();
    assertEquals(NEW_ID, service.update(HOST, OLD_ID, "v2026.8.3"));
  }

  @Test
  void aFailedRemapDoesNotUndoAHealthyUpdate() {
    stubUpgrade();
    ContainerIdListener broken = (host, oldId, newId) -> {
      throw new IllegalStateException("database is locked");
    };
    service = new ContainerUpdateService(docker, List.of(broken), transactions);

    // the container is already running the new image; refusing to report that
    // would trade a working Agent for a bookkeeping detail
    assertEquals(NEW_ID, service.update(HOST, OLD_ID, "v2026.8.3"));
  }

  @Test
  void aFailedUpgradePropagatesAndRemapsNothing() {
    when(docker.upgrade(any(), anyString(), anyString(), any()))
        .thenThrow(new IllegalArgumentException("not a Mission Control-managed container"));
    seedBoardTask(OLD_ID);

    assertThrows(IllegalArgumentException.class, () -> service.update(HOST, OLD_ID, "v2026.8.3"));

    assertEquals(1, board.findByContainer(OLD_ID).size());
  }

  @Test
  void aRemapThatFailsOnceIsRetriedAndSucceeds() {
    stubUpgrade();
    seedBoardTask(OLD_ID);
    seedLink(HOST.id(), OLD_ID);
    // sqlite is single-writer, so a concurrent writer holding the lock is the realistic
    // failure this retry exists for — it is gone a moment later
    FailingListener locked = new FailingListener(1);
    // first in the list: the first attempt dies before either store writes, so the rows
    // below can only have moved on the retry
    service = new ContainerUpdateService(docker, List.of(locked, board, links), transactions);

    assertEquals(NEW_ID, service.update(HOST, OLD_ID, "v2026.8.3"));

    assertEquals(2, locked.attempts());
    assertEquals(1, board.findByContainer(NEW_ID).size());
    assertTrue(board.findByContainer(OLD_ID).isEmpty());
    assertEquals(1, links.list(HOST.id(), NEW_ID, "default").size());
    assertTrue(links.list(HOST.id(), OLD_ID, "default").isEmpty());
  }

  @Test
  void aRemapThatKeepsFailingStillReturnsAHealthyUpdate() {
    stubUpgrade();
    seedBoardTask(OLD_ID);
    seedLink(HOST.id(), OLD_ID);
    FailingListener locked = new FailingListener(Integer.MAX_VALUE);
    // last in the list, so both stores have already written when it fails
    service = new ContainerUpdateService(docker, List.of(board, links, locked), transactions);

    // undoing a working upgrade to preserve a bookkeeping row would trade a live Agent
    // for a link, so the caller still learns where its container went
    assertEquals(NEW_ID, service.update(HOST, OLD_ID, "v2026.8.3"));

    assertEquals(2, locked.attempts());
    // every table moves in one transaction: an abandoned remap leaves the whole Agent on
    // the old id rather than splitting its tasks and links across two container ids
    assertEquals(1, board.findByContainer(OLD_ID).size());
    assertEquals(1, links.list(HOST.id(), OLD_ID, "default").size());
    assertTrue(board.findByContainer(NEW_ID).isEmpty());
    assertTrue(links.list(HOST.id(), NEW_ID, "default").isEmpty());
  }

  /** Refuses its first {@code failures} attempts the way a locked sqlite write would. */
  private static final class FailingListener implements ContainerIdListener {

    private final int failures;
    private int attempts;

    FailingListener(int failures) {
      this.failures = failures;
    }

    int attempts() {
      return attempts;
    }

    @Override
    public int onContainerReplaced(String hostId, String oldContainerId, String newContainerId) {
      if (++attempts <= failures) {
        throw new IllegalStateException("[SQLITE_BUSY] database is locked");
      }
      return 0;
    }
  }
}
