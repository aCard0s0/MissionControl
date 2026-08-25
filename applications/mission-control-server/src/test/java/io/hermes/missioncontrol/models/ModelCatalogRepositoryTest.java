package io.hermes.missioncontrol.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelCatalogRepositoryTest {

  private SqliteTestDatabase database;
  private ModelCatalogRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new ModelCatalogRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void aProviderNeverRefreshedHoldsNothing() {
    assertTrue(repository.models("nous").isEmpty());
  }

  @Test
  void theProvidersOwnOrderSurvivesTheRoundTrip() {
    // not alphabetical: the provider's ordering is what the picker shows
    repository.replace("nous", List.of("zeta", "alpha", "mid"), 1_700_000_000_000L);

    assertEquals(List.of("zeta", "alpha", "mid"), repository.models("nous"));
  }

  @Test
  void aSecondRefreshReplacesTheListRatherThanAddingToIt() {
    repository.replace("nvidia", List.of("old/one", "old/two"), 1L);

    repository.replace("nvidia", List.of("new/one"), 2L);

    // a model the provider withdrew has to leave, or the picker offers ids that now 404
    assertEquals(List.of("new/one"), repository.models("nvidia"));
  }

  @Test
  void oneProvidersRefreshLeavesTheOthersAlone() {
    repository.replace("nous", List.of("hermes"), 1L);
    repository.replace("nvidia", List.of("nemotron"), 1L);

    repository.replace("nous", List.of("hermes-2"), 2L);

    assertEquals(List.of("nemotron"), repository.models("nvidia"));
  }

  @Test
  void theSameModelIdUnderTwoProvidersIsTwoRows() {
    // openrouter and nvidia both namespace by publisher, and can name the same model
    repository.replace("openrouter", List.of("meta/llama-3.3-70b-instruct"), 1L);
    repository.replace("nvidia", List.of("meta/llama-3.3-70b-instruct"), 1L);

    assertEquals(List.of("meta/llama-3.3-70b-instruct"), repository.models("openrouter"));
    assertEquals(List.of("meta/llama-3.3-70b-instruct"), repository.models("nvidia"));
  }

  @Test
  void aProviderCanBeEmptiedDeliberately() {
    repository.replace("nous", List.of("hermes"), 1L);

    repository.replace("nous", List.of(), 2L);

    assertTrue(repository.models("nous").isEmpty());
  }
}
