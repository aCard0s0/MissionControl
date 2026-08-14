package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetainedResourceRepositoryTest {

  private SqliteTestDatabase database;
  private RetainedResourceRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new RetainedResourceRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void retainedVolumeComesBackWithEveryFieldIntact() {
    repository.retain("srv-1", "postgres-mcp", "dh-local", "mission-control-mcp-postgres-data");

    RetainedResourceDto found = repository.findAll().getFirst();
    assertTrue(found.id().startsWith("mrr-"));
    assertEquals("srv-1", found.serverId());
    assertEquals("postgres-mcp", found.serverName());
    assertEquals("dh-local", found.hostId());
    assertEquals("volume", found.type());
    assertEquals("mission-control-mcp-postgres-data", found.name());
    assertTrue(found.createdAt() > 0);
  }

  @Test
  void retainingTheSameVolumeTwiceKeepsTheOriginalRow() {
    repository.retain("srv-1", "postgres-mcp", "dh-local", "mission-control-mcp-postgres-data");
    String firstId = repository.findAll().getFirst().id();

    // deleting a server twice, or retaining after a failed purge, must not duplicate
    repository.retain("srv-2", "renamed", "dh-local", "mission-control-mcp-postgres-data");

    List<RetainedResourceDto> all = repository.findAll();
    assertEquals(1, all.size());
    assertEquals(firstId, all.getFirst().id());
    assertEquals("srv-1", all.getFirst().serverId());
  }

  @Test
  void theSameVolumeNameOnAnotherHostIsADistinctResource() {
    repository.retain("srv-1", "postgres-mcp", "dh-local", "mission-control-mcp-postgres-data");
    repository.retain("srv-1", "postgres-mcp", "dh-remote", "mission-control-mcp-postgres-data");

    assertEquals(2, repository.findAll().size());
  }

  @Test
  void requireThrowsForAnUnknownId() {
    assertThrows(NoSuchElementException.class, () -> repository.require("mrr-nope"));
  }

  @Test
  void deleteRemovesTheRowAndFreesTheUniqueSlot() {
    repository.retain("srv-1", "postgres-mcp", "dh-local", "mission-control-mcp-postgres-data");
    String id = repository.findAll().getFirst().id();

    repository.delete(id);
    assertTrue(repository.findById(id).isEmpty());

    // once forgotten, the same volume can be retained again by a later server
    repository.retain("srv-2", "postgres-mcp", "dh-local", "mission-control-mcp-postgres-data");
    assertEquals(1, repository.findAll().size());
  }
}
