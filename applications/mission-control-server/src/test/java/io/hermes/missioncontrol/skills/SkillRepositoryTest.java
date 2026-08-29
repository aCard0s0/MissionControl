package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The skill library's SQL against a real sqlite, because the constraints are the part
 * worth protecting: the {@code kind} check and the case-insensitive unique name both
 * stop a row that would later deploy to the wrong place, or to two places at once.
 */
class SkillRepositoryTest {

  private SqliteTestDatabase database;
  private SkillRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static Skill local(String id, String name, long updatedAt) {
    return new Skill(id, Skill.LOCAL, name, "reads pdfs", "docs", null, "1.0",
        List.of(new SkillFile("SKILL.md", "# pdf")), 1_000L, updatedAt);
  }

  @Test
  void aKindOtherThanHubOrLocalIsRefusedByTheDatabase() {
    Skill bogus = new Skill("s-1", "sideload", "pdf", null, "general", null, null,
        List.of(), 1_000L, 1_000L);

    assertThrows(DataIntegrityViolationException.class, () -> repository.insert(bogus));
  }

  @Test
  void twoSkillsCannotShareANameThatDiffersOnlyInCase() {
    // both would deploy to skills/pdf, and on a case-insensitive filesystem the second
    // would silently overwrite the first
    repository.insert(local("s-1", "pdf", 1_000L));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(local("s-2", "PDF", 1_000L)));
  }

  @Test
  void filesRoundTripThroughTheJsonColumn() {
    repository.insert(new Skill("s-1", Skill.LOCAL, "pdf", null, "docs", null, null,
        List.of(new SkillFile("SKILL.md", "# pdf"), new SkillFile("scripts/run.sh", "echo hi")),
        1_000L, 1_000L));

    List<SkillFile> files = repository.find("s-1").orElseThrow().files();

    assertEquals(2, files.size());
    assertEquals("SKILL.md", files.getFirst().path());
    assertEquals("echo hi", files.getLast().body());
  }

  @Test
  void aHubRowStoresNullRatherThanAnEmptyArray() {
    // `[]` would read back as "a local skill with no files", which can never deploy
    repository.insert(new Skill("s-1", Skill.HUB, "pdf", null, "docs",
        "https://example.test/pdf", null, List.of(), 1_000L, 1_000L));

    assertNull(database.jdbc().queryForObject(
        "SELECT files FROM skills WHERE id = ?", String.class, "s-1"));
    assertEquals(List.of(), repository.find("s-1").orElseThrow().files());
  }

  @Test
  void anUnparseableFilesColumnDegradesToEmptyRatherThanFailingTheList() {
    repository.insert(local("s-1", "pdf", 1_000L));
    database.jdbc().update("UPDATE skills SET files = ? WHERE id = ?", "{not json", "s-1");

    assertEquals(List.of(), repository.find("s-1").orElseThrow().files());
  }

  @Test
  void theLibraryReadsNewestEditFirst() {
    repository.insert(local("s-1", "old", 1_000L));
    repository.insert(local("s-2", "new", 9_000L));

    assertEquals(List.of("new", "old"), repository.findAll().stream().map(Skill::name).toList());
  }

  @Test
  void anUpdateNeverRewritesWhenTheSkillWasFirstSaved() {
    repository.insert(local("s-1", "pdf", 1_000L));

    repository.update(new Skill("s-1", Skill.LOCAL, "pdf", "now with tables", "docs", null, "2.0",
        List.of(new SkillFile("SKILL.md", "# pdf v2")), 7_777L, 5_000L));

    Skill updated = repository.find("s-1").orElseThrow();
    assertEquals(1_000L, updated.createdAt(), "createdAt is not in the UPDATE statement");
    assertEquals(5_000L, updated.updatedAt());
    assertEquals("2.0", updated.version());
  }

  @Test
  void findByNameIsHowAnImportUpdatesTheRowItAlreadyHas() {
    repository.insert(local("s-1", "pdf", 1_000L));

    assertEquals("s-1", repository.findByName("pdf").orElseThrow().id());
    assertTrue(repository.findByName("nope").isEmpty());
  }

  @Test
  void deleteIsIdempotent() {
    repository.insert(local("s-1", "pdf", 1_000L));

    repository.delete("s-1");
    repository.delete("s-1");

    assertTrue(repository.find("s-1").isEmpty());
  }
}
