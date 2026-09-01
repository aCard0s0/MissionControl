package io.hermes.missioncontrol.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The prompt groups' SQL against a real sqlite. */
class PromptGroupRepositoryTest {

  private SqliteTestDatabase database;
  private PromptGroupRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new PromptGroupRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static PromptGroup group(String id, String name) {
    return new PromptGroup(id, name, "everything for a bad deploy", List.of("p-1", "p-2"),
        1_000L, 2_000L);
  }

  @Test
  void twoGroupsCannotShareANameThatDiffersOnlyInCase() {
    repository.insert(group("pg-1", "triage"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(group("pg-2", "Triage")));
  }

  @Test
  void thePromptIdsRoundTripThroughTheJsonColumn() {
    repository.insert(group("pg-1", "triage"));

    assertEquals(List.of("p-1", "p-2"), repository.find("pg-1").orElseThrow().promptIds());
  }

  @Test
  void anUnparseableIdColumnDegradesToEmptyRatherThanFailingTheRead() {
    repository.insert(group("pg-1", "triage"));
    database.jdbc().update("UPDATE prompt_groups SET prompt_ids = ? WHERE id = ?", "{no", "pg-1");

    PromptGroup stored = repository.find("pg-1").orElseThrow();
    // the group survives one bad column — its name is still readable
    assertEquals(List.of(), stored.promptIds());
    assertEquals("triage", stored.name());
  }

  @Test
  void aNullIdColumnReadsAsEmptyRatherThanThrowing() {
    // a row written before this column existed, or by hand, has NULL rather than "[]"
    repository.insert(group("pg-1", "triage"));
    database.jdbc().update("UPDATE prompt_groups SET prompt_ids = NULL WHERE id = ?", "pg-1");

    assertEquals(List.of(), repository.find("pg-1").orElseThrow().promptIds());
  }

  @Test
  void aBlankIdColumnReadsAsEmptyToo() {
    repository.insert(group("pg-1", "triage"));
    database.jdbc().update("UPDATE prompt_groups SET prompt_ids = ? WHERE id = ?", " ", "pg-1");

    assertEquals(List.of(), repository.find("pg-1").orElseThrow().promptIds());
  }

  @Test
  void aGroupInsertedWithNoPromptListStoresAnEmptyOne() {
    repository.insert(new PromptGroup("pg-1", "triage", null, null, 1_000L, 1_000L));

    assertEquals(List.of(), repository.find("pg-1").orElseThrow().promptIds());
  }

  @Test
  void theListReadsByNameRatherThanByNewestEdit() {
    // these are headers the prompt list is filed under, so their order is the page's
    repository.insert(new PromptGroup("pg-1", "zebra", null, List.of(), 1_000L, 9_000L));
    repository.insert(new PromptGroup("pg-2", "alpha", null, List.of(), 1_000L, 1_000L));

    assertEquals(List.of("alpha", "zebra"),
        repository.findAll().stream().map(PromptGroup::name).toList());
  }

  @Test
  void anUpdateKeepsWhenTheGroupWasFirstSaved() {
    repository.insert(group("pg-1", "triage"));

    repository.update(new PromptGroup("pg-1", "triage-v2", "renamed", List.of("p-3"),
        4_000L, 5_000L));

    PromptGroup stored = repository.find("pg-1").orElseThrow();
    assertEquals(1_000L, stored.createdAt());
    assertEquals(5_000L, stored.updatedAt());
    assertEquals("triage-v2", stored.name());
    assertEquals(List.of("p-3"), stored.promptIds());
  }
}
