package io.hermes.missioncontrol.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The credentials' SQL against a real sqlite. */
class CredentialRepositoryTest {

  private SqliteTestDatabase database;
  private CredentialRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new CredentialRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static Credential credential(String id, String name) {
    return new Credential(id, name, "the production key", List.of(
        new CredentialEntry("ANTHROPIC_API_KEY", "enc:v1:abc", true),
        new CredentialEntry("TELEGRAM_HOME_CHANNEL", "#ops", false)), 1_000L, 2_000L);
  }

  @Test
  void aBlankEntriesColumnReadsAsHoldingNothing() {
    // what the dashboard's wire types promise: `entries` is a list, never null. The column is
    // NOT NULL, so blank is the only way in — by hand
    database.jdbc().update("""
        INSERT INTO credentials (id, name, description, entries_json, created_at, updated_at)
        VALUES ('cr-blank', 'empty', NULL, '', 1, 2)
        """);

    assertTrue(repository.find("cr-blank").orElseThrow().entries().isEmpty());
  }

  @Test
  void twoCredentialsCannotShareANameThatDiffersOnlyInCase() {
    // the name is what the dropdown shows; two options reading the same makes it a guess
    repository.insert(credential("cr-1", "anthropic"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(credential("cr-2", "Anthropic")));
  }

  @Test
  void theEntriesRoundTripThroughTheJsonColumn() {
    repository.insert(credential("cr-1", "anthropic"));

    List<CredentialEntry> stored = repository.find("cr-1").orElseThrow().entries();
    assertEquals(2, stored.size());
    assertEquals("ANTHROPIC_API_KEY", stored.get(0).key());
    assertTrue(stored.get(0).secret());
    assertEquals("enc:v1:abc", stored.get(0).value());
    assertEquals("#ops", stored.get(1).value());
    assertEquals(false, stored.get(1).secret());
  }

  @Test
  void anUnparseableEntriesColumnIsAConflictRatherThanAnEmptyCredential() {
    // answering with no entries reads as "never filled in", and the editor's next save would
    // then write that emptiness over the only copy of the values
    repository.insert(credential("cr-1", "anthropic"));
    database.jdbc().update("UPDATE credentials SET entries_json = ? WHERE id = ?", "{not json", "cr-1");

    assertThrows(ResourceConflictException.class, () -> repository.find("cr-1"));
  }

  @Test
  void aBlankEntriesColumnReadsAsEmptyRatherThanFailingTheWholeListing() {
    // only reachable by hand — the column is NOT NULL and an insert always writes at least
    // "[]" — but one such row must not 409 every other credential out of the dropdown
    repository.insert(credential("cr-1", "anthropic"));
    database.jdbc().update("UPDATE credentials SET entries_json = ? WHERE id = ?", "  ", "cr-1");

    assertEquals(List.of(), repository.find("cr-1").orElseThrow().entries());
  }

  @Test
  void theListReadsByNameRatherThanByNewestEdit() {
    // this list is a dropdown: an option that moves because an unrelated row was renamed
    // makes the picker unreadable
    repository.insert(new Credential("cr-1", "zebra", null, List.of(), 1_000L, 9_000L));
    repository.insert(new Credential("cr-2", "alpha", null, List.of(), 1_000L, 1_000L));

    assertEquals(List.of("alpha", "zebra"),
        repository.findAll().stream().map(Credential::name).toList());
  }

  @Test
  void anUpdateKeepsWhenTheCredentialWasFirstSaved() {
    repository.insert(credential("cr-1", "anthropic"));

    repository.update(new Credential("cr-1", "anthropic-prod", "renamed",
        List.of(new CredentialEntry("OPENAI_API_KEY", "enc:v1:xyz", true)), 4_000L, 5_000L));

    Credential stored = repository.find("cr-1").orElseThrow();
    assertEquals(1_000L, stored.createdAt());
    assertEquals(5_000L, stored.updatedAt());
    assertEquals("anthropic-prod", stored.name());
    assertEquals(List.of("OPENAI_API_KEY"), stored.entries().stream().map(CredentialEntry::key).toList());
  }

  @Test
  void deletingIsIdempotent() {
    repository.delete("cr-nope");

    assertEquals(List.of(), repository.findAll());
  }
}
