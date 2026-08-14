package io.hermes.missioncontrol.modelproviders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.modelproviders.ModelProviderRepository.ProviderRow;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ModelProviderRepositoryTest {

  private SqliteTestDatabase database;
  private ModelProviderRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new ModelProviderRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void insertedRowComesBackWithEveryFieldIntact() {
    repository.insert(new ProviderRow("mp-1", "workshop ollama", "http://10.0.0.4:11434", "ollama"));

    ProviderRow found = repository.findById("mp-1").orElseThrow();
    assertEquals("mp-1", found.id());
    assertEquals("workshop ollama", found.name());
    assertEquals("http://10.0.0.4:11434", found.url());
    assertEquals("ollama", found.kind());
  }

  @Test
  void findByIdIsEmptyForAnUnknownProvider() {
    assertTrue(repository.findById("nope").isEmpty());
  }

  @Test
  void findAllOrdersByInsertionTime() {
    repository.insert(new ProviderRow("mp-1", "first", "http://a:11434", "ollama"));
    repository.insert(new ProviderRow("mp-2", "second", "http://b:11434", "ollama"));

    assertEquals(List.of("mp-1", "mp-2"),
        repository.findAll().stream().map(ProviderRow::id).toList());
  }

  @Test
  void urlExistsTracksInsertsAndDeletes() {
    assertFalse(repository.urlExists("http://a:11434"));

    repository.insert(new ProviderRow("mp-1", "first", "http://a:11434", "ollama"));
    assertTrue(repository.urlExists("http://a:11434"));

    repository.delete("mp-1");
    assertFalse(repository.urlExists("http://a:11434"));
    assertTrue(repository.findById("mp-1").isEmpty());
  }

  @Test
  void theSchemaRefusesADuplicateUrl() {
    repository.insert(new ProviderRow("mp-1", "first", "http://a:11434", "ollama"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(new ProviderRow("mp-2", "copy", "http://a:11434", "ollama")));
  }

  @Test
  void theSchemaRefusesAKindOtherThanOllama() {
    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(new ProviderRow("mp-1", "first", "http://a:11434", "vllm")));
  }
}
