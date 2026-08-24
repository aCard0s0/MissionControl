package io.hermes.missioncontrol.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptRepositoryTest {

  private SqliteTestDatabase database;
  private PromptRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new PromptRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static Prompt prompt(String id, long updatedAt, List<String> tags) {
    return new Prompt(id, "Triage", "look at the logs", "ops", "read only", tags,
        1_700_000_000_000L, updatedAt);
  }

  @Test
  void everyFieldSurvivesARoundTrip() {
    repository.insert(prompt("p-1", 1_700_000_100_000L, List.of("ops", "triage")));

    Prompt found = repository.find("p-1").orElseThrow();
    assertEquals("p-1", found.id());
    assertEquals("Triage", found.title());
    assertEquals("look at the logs", found.body());
    assertEquals("ops", found.category());
    assertEquals("read only", found.notes());
    assertEquals(List.of("ops", "triage"), found.tags());
    assertEquals(1_700_000_000_000L, found.createdAt());
    assertEquals(1_700_000_100_000L, found.updatedAt());
  }

  @Test
  void aTagContainingACommaStaysOneTag() {
    // the JSON encoding is the point of the column — a delimiter-joined one would split this
    repository.insert(prompt("p-1", 1L, List.of("needs review, then reuse", "ops")));

    assertEquals(List.of("needs review, then reuse", "ops"), repository.find("p-1").orElseThrow().tags());
  }

  @Test
  void emptyAndUnreadableTagsBothReadAsAnEmptyList() {
    repository.insert(prompt("p-1", 1L, List.of()));
    database.jdbc().update("""
        INSERT INTO prompts (id, title, body, category, notes, tags, created_at, updated_at)
        VALUES ('p-2', 't', 'b', 'ops', NULL, 'not json', 1, 2)
        """);

    assertTrue(repository.find("p-1").orElseThrow().tags().isEmpty());
    assertTrue(repository.find("p-2").orElseThrow().tags().isEmpty());
  }

  @Test
  void theLibraryIsListedNewestEditFirst() {
    repository.insert(prompt("p-old", 1_000L, List.of()));
    repository.insert(prompt("p-new", 3_000L, List.of()));
    repository.insert(prompt("p-mid", 2_000L, List.of()));

    assertEquals(List.of("p-new", "p-mid", "p-old"), repository.findAll().stream().map(Prompt::id).toList());
  }

  @Test
  void findByCategoryNarrowsToThatCategoryOnly() {
    repository.insert(prompt("p-1", 1L, List.of()));
    database.jdbc().update("""
        INSERT INTO prompts (id, title, body, category, notes, tags, created_at, updated_at)
        VALUES ('p-2', 't', 'b', 'review', NULL, '[]', 1, 2)
        """);

    assertEquals(List.of("p-1"), repository.findByCategory("ops").stream().map(Prompt::id).toList());
    assertTrue(repository.findByCategory("nothing-here").isEmpty());
  }

  @Test
  void findAnswersEmptyForAnIdNobodyHolds() {
    assertTrue(repository.find("p-missing").isEmpty());
  }

  @Test
  void updateRewritesTheEditableFieldsAndKeepsCreatedAt() {
    repository.insert(prompt("p-1", 1_000L, List.of("ops")));

    int rows = repository.update(new Prompt(
        "p-1", "Triage v2", "read the first error", "review", null, List.of("review"),
        // a caller passing a different createdAt must not be able to rewrite it
        9L, 2_000L));

    assertEquals(1, rows);
    Prompt found = repository.find("p-1").orElseThrow();
    assertEquals("Triage v2", found.title());
    assertEquals("read the first error", found.body());
    assertEquals("review", found.category());
    assertEquals(null, found.notes());
    assertEquals(List.of("review"), found.tags());
    assertEquals(1_700_000_000_000L, found.createdAt());
    assertEquals(2_000L, found.updatedAt());
  }

  @Test
  void updateReportsNoRowsForAPromptThatIsGone() {
    assertEquals(0, repository.update(prompt("p-missing", 1L, List.of())));
  }

  @Test
  void deleteRemovesThePrompt() {
    repository.insert(prompt("p-1", 1L, List.of()));

    repository.delete("p-1");

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void metaRemembersAKeyAndOverwritesIt() {
    assertTrue(repository.meta("library-seed-version").isEmpty());

    repository.putMeta("library-seed-version", "1");
    assertEquals("1", repository.meta("library-seed-version").orElseThrow());

    repository.putMeta("library-seed-version", "2");
    assertEquals("2", repository.meta("library-seed-version").orElseThrow());
  }
}
