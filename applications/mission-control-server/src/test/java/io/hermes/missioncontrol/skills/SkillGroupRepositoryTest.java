package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The skill groups' SQL against a real sqlite. */
class SkillGroupRepositoryTest {

  private SqliteTestDatabase database;
  private SkillGroupRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillGroupRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static SkillGroup group(String id, String name, String guideId) {
    return new SkillGroup(id, name, "everything that touches a PDF", List.of("s-1", "s-2"),
        guideId, 1_000L, 2_000L);
  }

  @Test
  void twoGroupsCannotShareANameThatDiffersOnlyInCase() {
    // two headers reading the same is the one way the filed list becomes unreadable
    repository.insert(group("sg-1", "pdf", "g-1"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(group("sg-2", "PDF", "g-1")));
  }

  @Test
  void theSkillIdsRoundTripThroughTheJsonColumn() {
    repository.insert(group("sg-1", "pdf", "g-1"));

    assertEquals(List.of("s-1", "s-2"), repository.find("sg-1").orElseThrow().skillIds());
  }

  @Test
  void anUnparseableIdColumnDegradesToEmptyRatherThanFailingTheRead() {
    repository.insert(group("sg-1", "pdf", "g-1"));
    database.jdbc().update("UPDATE skill_groups SET skill_ids = ? WHERE id = ?", "{not json", "sg-1");

    SkillGroup stored = repository.find("sg-1").orElseThrow();
    // the group survives one bad column — its name and its guide are still readable
    assertEquals(List.of(), stored.skillIds());
    assertEquals("pdf", stored.name());
    assertEquals("g-1", stored.guideId());
  }

  @Test
  void aNullIdColumnReadsAsEmptyRatherThanThrowing() {
    // a row written before this column existed, or by hand, has NULL rather than "[]"
    repository.insert(group("sg-1", "pdf", "g-1"));
    database.jdbc().update("UPDATE skill_groups SET skill_ids = NULL WHERE id = ?", "sg-1");

    assertEquals(List.of(), repository.find("sg-1").orElseThrow().skillIds());
  }

  @Test
  void aBlankIdColumnReadsAsEmptyToo() {
    repository.insert(group("sg-1", "pdf", "g-1"));
    database.jdbc().update("UPDATE skill_groups SET skill_ids = ? WHERE id = ?", "  ", "sg-1");

    assertEquals(List.of(), repository.find("sg-1").orElseThrow().skillIds());
  }

  @Test
  void aGroupInsertedWithNoSkillListStoresAnEmptyOne() {
    repository.insert(new SkillGroup("sg-1", "pdf", null, null, null, 1_000L, 1_000L));

    assertEquals(List.of(), repository.find("sg-1").orElseThrow().skillIds());
  }

  @Test
  void aGroupNeedsNoGuide() {
    repository.insert(group("sg-1", "pdf", null));

    assertNull(repository.find("sg-1").orElseThrow().guideId());
  }

  @Test
  void theListReadsByNameRatherThanByNewestEdit() {
    // these are headers the skills list is filed under, so their order is the page's
    repository.insert(new SkillGroup("sg-1", "zebra", null, List.of(), null, 1_000L, 9_000L));
    repository.insert(new SkillGroup("sg-2", "alpha", null, List.of(), null, 1_000L, 1_000L));

    assertEquals(List.of("alpha", "zebra"),
        repository.findAll().stream().map(SkillGroup::name).toList());
  }

  @Test
  void anUpdateKeepsWhenTheGroupWasFirstSaved() {
    repository.insert(group("sg-1", "pdf", "g-1"));

    repository.update(new SkillGroup("sg-1", "pdf-tools", "renamed", List.of("s-3"), null,
        4_000L, 5_000L));

    SkillGroup stored = repository.find("sg-1").orElseThrow();
    assertEquals(1_000L, stored.createdAt());
    assertEquals(5_000L, stored.updatedAt());
    assertEquals("pdf-tools", stored.name());
    assertNull(stored.guideId());
  }
}
