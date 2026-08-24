package io.hermes.missioncontrol.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptSeederTest {

  private SqliteTestDatabase database;
  private PromptRepository repository;
  private PromptSeeder seeder;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new PromptRepository(database.jdbc(), new ObjectMapper());
    seeder = new PromptSeeder(repository);
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void aFreshInstallGetsOneSamplePrompt() {
    seeder.seedOnce();

    List<Prompt> library = repository.findAll();
    assertEquals(1, library.size());
    Prompt sample = library.getFirst();
    assertEquals(PromptSeeder.SEED_ID, sample.id());
    assertEquals("ops", sample.category());
    assertTrue(sample.tags().contains("sample"));
    assertTrue(sample.body().contains("hermes status"), "the sample is not a usable prompt");
    assertEquals(PromptSeeder.SEED_VERSION, repository.meta(PromptSeeder.SEED_META).orElseThrow());
  }

  @Test
  void bootingAgainDoesNotSeedASecondCopy() {
    seeder.seedOnce();
    seeder.seedOnce();

    assertEquals(1, repository.findAll().size());
  }

  @Test
  void aSampleTheOperatorDeletedStaysDeleted() {
    // the marker row, not an empty-table check, is what makes this true — an operator
    // who clears the library must not have the sample pushed back at the next boot
    seeder.seedOnce();
    repository.delete(PromptSeeder.SEED_ID);

    seeder.seedOnce();

    assertTrue(repository.findAll().isEmpty());
  }
}
