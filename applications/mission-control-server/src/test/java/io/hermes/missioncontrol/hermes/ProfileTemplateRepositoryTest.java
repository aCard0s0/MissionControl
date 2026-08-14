package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ProfileTemplateRepositoryTest {

  private SqliteTestDatabase database;
  private ProfileTemplateRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new ProfileTemplateRepository(database.jdbc(), new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static ProfileTemplate template(String id, String name) {
    return new ProfileTemplate(id, name, "a description", "anthropic", "claude-opus-5",
        "https://api.anthropic.com", "/work", "you are helpful", "remembers things",
        List.of("skill-a", "skill-b"),
        List.of(new McpServerSpec("files", "stdio", null, "npx", "-y @modelcontextprotocol/files", true)),
        List.of(new StoredSecret("ANTHROPIC_API_KEY", "enc:v1:abc123")),
        1_700_000_000_000L, 1_700_000_001_000L);
  }

  @Test
  void everyScalarAndJsonColumnSurvivesARoundTrip() {
    repository.insert(template("pt-1", "researcher"));

    ProfileTemplate found = repository.findById("pt-1").orElseThrow();
    assertEquals("pt-1", found.id());
    assertEquals("researcher", found.name());
    assertEquals("a description", found.description());
    assertEquals("anthropic", found.provider());
    assertEquals("claude-opus-5", found.model());
    assertEquals("https://api.anthropic.com", found.baseUrl());
    assertEquals("/work", found.cwd());
    assertEquals("you are helpful", found.soul());
    assertEquals("remembers things", found.memory());
    assertEquals(1_700_000_000_000L, found.createdAt());
    assertEquals(1_700_000_001_000L, found.updatedAt());

    assertEquals(List.of("skill-a", "skill-b"), found.skills());

    McpServerSpec spec = found.mcpServers().getFirst();
    assertEquals("files", spec.name());
    assertEquals("stdio", spec.transport());
    assertEquals("npx", spec.command());
    assertEquals("-y @modelcontextprotocol/files", spec.args());
    assertTrue(spec.enabled());

    StoredSecret secret = found.secrets().getFirst();
    assertEquals("ANTHROPIC_API_KEY", secret.key());
    assertEquals("enc:v1:abc123", secret.enc());
  }

  @Test
  void emptyListColumnsRoundTripAsEmptyNotNull() {
    repository.insert(new ProfileTemplate("pt-1", "bare", null, null, null, null, null, null, null,
        List.of(), List.of(), List.of(), 1L, 1L));

    ProfileTemplate found = repository.findById("pt-1").orElseThrow();
    assertTrue(found.skills().isEmpty());
    assertTrue(found.mcpServers().isEmpty());
    assertTrue(found.secrets().isEmpty());
  }

  @Test
  void updateReplacesScalarsAndJsonColumns() {
    repository.insert(template("pt-1", "researcher"));

    repository.update(new ProfileTemplate("pt-1", "renamed", "new description", "openai", "gpt-5.2",
        null, "/other", "different soul", "different memory",
        List.of("skill-c"), List.of(), List.of(new StoredSecret("OPENAI_API_KEY", "enc:v1:xyz")),
        1_700_000_000_000L, 1_700_000_009_000L));

    ProfileTemplate found = repository.findById("pt-1").orElseThrow();
    assertEquals("renamed", found.name());
    assertEquals("openai", found.provider());
    assertEquals(List.of("skill-c"), found.skills());
    assertTrue(found.mcpServers().isEmpty());
    assertEquals("OPENAI_API_KEY", found.secrets().getFirst().key());
    assertEquals(1_700_000_009_000L, found.updatedAt());
  }

  @Test
  void findAllOrdersByMostRecentlyUpdated() {
    repository.insert(new ProfileTemplate("pt-old", "old", null, null, null, null, null, null, null,
        List.of(), List.of(), List.of(), 1L, 100L));
    repository.insert(new ProfileTemplate("pt-new", "new", null, null, null, null, null, null, null,
        List.of(), List.of(), List.of(), 1L, 200L));

    assertEquals(List.of("pt-new", "pt-old"),
        repository.findAll().stream().map(ProfileTemplate::id).toList());
  }

  @Test
  void existsByNameExceptIgnoresTheTemplateKeepingItsOwnName() {
    repository.insert(template("pt-1", "researcher"));

    assertTrue(repository.existsByName("researcher"));
    assertFalse(repository.existsByName("nobody"));
    // a rename check must not trip over the row being renamed
    assertFalse(repository.existsByNameExcept("researcher", "pt-1"));
    assertTrue(repository.existsByNameExcept("researcher", "pt-2"));
  }

  @Test
  void theSchemaRefusesADuplicateName() {
    repository.insert(template("pt-1", "researcher"));

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(template("pt-2", "researcher")));
  }

  @Test
  void anUnreadableColumnFailsTheSingleTemplateReadInsteadOfEmptyingIt() {
    repository.insert(template("pt-1", "researcher"));
    database.jdbc().update("UPDATE profile_templates SET secrets = ? WHERE id = ?",
        "{not json at all", "pt-1");

    // findById feeds update(), so returning an empty list here would write the loss back
    assertThrows(IllegalStateException.class, () -> repository.findById("pt-1"));

    assertEquals("{not json at all", database.jdbc().queryForObject(
        "SELECT secrets FROM profile_templates WHERE id = ?", String.class, "pt-1"));
  }

  @Test
  void oneUnreadableRowDoesNotBreakTheTemplateList() {
    repository.insert(template("pt-1", "researcher"));
    repository.insert(template("pt-2", "analyst"));
    database.jdbc().update("UPDATE profile_templates SET secrets = ? WHERE id = ?",
        "{not json at all", "pt-1");

    // the list view degrades for the damaged row rather than 503-ing the whole page
    List<ProfileTemplate> all = repository.findAll();
    assertEquals(2, all.size());
    assertTrue(all.stream().filter(t -> t.id().equals("pt-1")).findFirst()
        .orElseThrow().secrets().isEmpty());
  }

  @Test
  void deleteRemovesTheRow() {
    repository.insert(template("pt-1", "researcher"));
    repository.delete("pt-1");

    assertTrue(repository.findById("pt-1").isEmpty());
    assertTrue(repository.findAll().isEmpty());
  }
}
