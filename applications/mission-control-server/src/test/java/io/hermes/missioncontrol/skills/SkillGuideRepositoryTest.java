package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The guide library's SQL against a real sqlite. */
class SkillGuideRepositoryTest {

  private SqliteTestDatabase database;
  private SkillGuideRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillGuideRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static SkillGuide guide(String id, String name, long updatedAt) {
    return new SkillGuide(id, name, "triage a broken export", "Read the log first.", "docs",
        List.of("s-1", "s-2"), List.of("m-1"), 1_000L, updatedAt);
  }

  @Test
  void twoGuidesCannotShareANameThatDiffersOnlyInCase() {
    // the name is the umbrella skill's directory, so both would write to the same place
    repository.insert(guide("g-1", "pdf-workflow", 1_000L));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(guide("g-2", "PDF-Workflow", 1_000L)));
  }

  @Test
  void idListsRoundTripThroughTheJsonColumns() {
    repository.insert(guide("g-1", "pdf-workflow", 1_000L));

    SkillGuide stored = repository.find("g-1").orElseThrow();

    assertEquals(List.of("s-1", "s-2"), stored.skillIds());
    assertEquals(List.of("m-1"), stored.mcpServerIds());
  }

  @Test
  void anUnparseableIdColumnDegradesToEmptyRatherThanFailingTheList() {
    repository.insert(guide("g-1", "pdf-workflow", 1_000L));
    database.jdbc().update("UPDATE skill_guides SET skill_ids = ? WHERE id = ?", "{not json", "g-1");

    assertEquals(List.of(), repository.find("g-1").orElseThrow().skillIds());
    // the other column is untouched, so a guide is not lost over one bad value
    assertEquals(List.of("m-1"), repository.find("g-1").orElseThrow().mcpServerIds());
  }

  @Test
  void theLibraryReadsNewestEditFirst() {
    repository.insert(guide("g-1", "old", 1_000L));
    repository.insert(guide("g-2", "new", 9_000L));

    assertEquals(List.of("new", "old"),
        repository.findAll().stream().map(SkillGuide::name).toList());
  }

  @Test
  void anUpdateNeverRewritesWhenTheGuideWasFirstSaved() {
    repository.insert(guide("g-1", "pdf-workflow", 1_000L));

    repository.update(new SkillGuide("g-1", "pdf-workflow", "now with tables", "New body.",
        "docs", List.of("s-9"), List.of(), 7_777L, 5_000L));

    SkillGuide updated = repository.find("g-1").orElseThrow();
    assertEquals(1_000L, updated.createdAt(), "createdAt is not in the UPDATE statement");
    assertEquals(5_000L, updated.updatedAt());
    assertEquals(List.of("s-9"), updated.skillIds());
    assertEquals(List.of(), updated.mcpServerIds());
  }

  @Test
  void findingByCategoryFiltersTheLibrary() {
    repository.insert(guide("g-1", "a", 1_000L));
    repository.insert(new SkillGuide("g-2", "b", null, "body", "writing",
        List.of(), List.of(), 1_000L, 1_000L));

    assertEquals(List.of("a"), repository.findByCategory("docs").stream()
        .map(SkillGuide::name).toList());
  }

  @Test
  void deleteIsIdempotent() {
    repository.insert(guide("g-1", "pdf-workflow", 1_000L));

    repository.delete("g-1");
    repository.delete("g-1");

    assertTrue(repository.find("g-1").isEmpty());
  }
}
