package io.hermes.missioncontrol.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoardRepositoryTest {

  private SqliteTestDatabase database;
  private BoardRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new BoardRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static BoardTask task(String id, List<String> tags) {
    return new BoardTask(id, "c1", "agent-1", "ship it", "queued", "med", tags, 1_700_000_000_000L);
  }

  @Test
  void everyFieldSurvivesARoundTrip() {
    repository.insert(task("bt-1", List.of("infra", "urgent")));

    BoardTask found = repository.findByContainer("c1").getFirst();
    assertEquals("bt-1", found.id());
    assertEquals("c1", found.containerId());
    assertEquals("agent-1", found.agentId());
    assertEquals("ship it", found.title());
    assertEquals("queued", found.column());
    assertEquals("med", found.priority());
    assertEquals(List.of("infra", "urgent"), found.tags());
    assertEquals(1_700_000_000_000L, found.createdAt());
  }

  @Test
  void aTagContainingACommaStaysOneTag() {
    // the old comma-separated encoding split this into two tags on read
    repository.insert(task("bt-1", List.of("needs review, then merge", "infra")));

    assertEquals(List.of("needs review, then merge", "infra"),
        repository.findByContainer("c1").getFirst().tags());
  }

  @Test
  void emptyTagsRoundTripAsAnEmptyList() {
    repository.insert(task("bt-1", List.of()));

    assertTrue(repository.findByContainer("c1").getFirst().tags().isEmpty());
  }

  @Test
  void rowsWrittenBeforeTheJsonEncodingStillRead() {
    // no migration runs against a deployed database, so legacy rows must keep working
    database.jdbc().update("""
        INSERT INTO board_tasks (id, container_id, agent_id, title, col, priority, tags, created_at)
        VALUES ('bt-legacy', 'c1', 'agent-1', 'old task', 'queued', 'med', 'infra,urgent', 1)
        """);

    assertEquals(List.of("infra", "urgent"), repository.findByContainer("c1").getFirst().tags());
  }

  @Test
  void aLegacyRowWithNoTagsReadsAsEmpty() {
    database.jdbc().update("""
        INSERT INTO board_tasks (id, container_id, agent_id, title, col, priority, tags, created_at)
        VALUES ('bt-legacy', 'c1', 'agent-1', 'old task', 'queued', 'med', '', 1)
        """);

    assertTrue(repository.findByContainer("c1").getFirst().tags().isEmpty());
  }

  @Test
  void updateColumnMovesTheTaskAndReportsTheRowCount() {
    repository.insert(task("bt-1", List.of()));

    assertEquals(1, repository.updateColumn("bt-1", "done"));
    assertEquals("done", repository.findByContainer("c1").getFirst().column());
    assertEquals(0, repository.updateColumn("bt-missing", "done"));
  }

  @Test
  void replacingAContainerMovesItsTasks() {
    repository.insert(task("bt-1", List.of("infra")));

    assertEquals(1, repository.onContainerReplaced("dh-local", "c1", "c2"));

    assertTrue(repository.findByContainer("c1").isEmpty());
    assertEquals(List.of("infra"), repository.findByContainer("c2").getFirst().tags());
  }

  @Test
  void deleteRemovesTheTask() {
    repository.insert(task("bt-1", List.of()));
    repository.delete("bt-1");

    assertTrue(repository.findAll().isEmpty());
  }
}
