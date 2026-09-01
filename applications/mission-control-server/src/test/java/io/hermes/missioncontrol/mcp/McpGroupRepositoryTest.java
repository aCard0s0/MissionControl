package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The MCP groups' SQL against a real sqlite. */
class McpGroupRepositoryTest {

  private SqliteTestDatabase database;
  private McpGroupRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new McpGroupRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static McpGroup group(String id, String name) {
    return new McpGroup(id, name, "everything a researcher needs", List.of("m-1", "m-2"),
        1_000L, 2_000L);
  }

  @Test
  void twoGroupsCannotShareANameThatDiffersOnlyInCase() {
    repository.insert(group("mg-1", "research"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(group("mg-2", "Research")));
  }

  @Test
  void theServerIdsRoundTripThroughTheJsonColumn() {
    repository.insert(group("mg-1", "research"));

    assertEquals(List.of("m-1", "m-2"), repository.find("mg-1").orElseThrow().serverIds());
  }

  @Test
  void anUnparseableIdColumnDegradesToEmptyRatherThanFailingTheRead() {
    repository.insert(group("mg-1", "research"));
    database.jdbc().update("UPDATE mcp_groups SET server_ids = ? WHERE id = ?", "{no", "mg-1");

    McpGroup stored = repository.find("mg-1").orElseThrow();
    // the group survives one bad column — its name is still readable
    assertEquals(List.of(), stored.serverIds());
    assertEquals("research", stored.name());
  }

  @Test
  void aNullIdColumnReadsAsEmptyRatherThanThrowing() {
    repository.insert(group("mg-1", "research"));
    database.jdbc().update("UPDATE mcp_groups SET server_ids = NULL WHERE id = ?", "mg-1");

    assertEquals(List.of(), repository.find("mg-1").orElseThrow().serverIds());
  }

  @Test
  void aBlankIdColumnReadsAsEmptyToo() {
    repository.insert(group("mg-1", "research"));
    database.jdbc().update("UPDATE mcp_groups SET server_ids = ? WHERE id = ?", " ", "mg-1");

    assertEquals(List.of(), repository.find("mg-1").orElseThrow().serverIds());
  }

  @Test
  void aGroupInsertedWithNoServerListStoresAnEmptyOne() {
    repository.insert(new McpGroup("mg-1", "research", null, null, 1_000L, 1_000L));

    assertEquals(List.of(), repository.find("mg-1").orElseThrow().serverIds());
  }

  @Test
  void theListReadsByNameRatherThanByNewestEdit() {
    repository.insert(new McpGroup("mg-1", "zebra", null, List.of(), 1_000L, 9_000L));
    repository.insert(new McpGroup("mg-2", "alpha", null, List.of(), 1_000L, 1_000L));

    assertEquals(List.of("alpha", "zebra"),
        repository.findAll().stream().map(McpGroup::name).toList());
  }

  @Test
  void anUpdateKeepsWhenTheGroupWasFirstSaved() {
    repository.insert(group("mg-1", "research"));

    repository.update(new McpGroup("mg-1", "research-v2", "renamed", List.of("m-3"),
        4_000L, 5_000L));

    McpGroup stored = repository.find("mg-1").orElseThrow();
    assertEquals(1_000L, stored.createdAt());
    assertEquals(5_000L, stored.updatedAt());
    assertEquals("research-v2", stored.name());
    assertEquals(List.of("m-3"), stored.serverIds());
  }
}
