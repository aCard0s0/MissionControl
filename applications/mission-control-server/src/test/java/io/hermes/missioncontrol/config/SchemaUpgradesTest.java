package io.hermes.missioncontrol.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.templates.ProfileTemplate;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateRepository;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The upgrade path for a database that already exists.
 *
 * <p>{@code schema.sql} cannot be the subject here: it declares the current shape, so a test
 * starting from it starts from a database needing no upgrade. Each test below therefore drops
 * the table and recreates it in the shape the previous release shipped — which is the only
 * state this class exists for.
 */
class SchemaUpgradesTest {

  private SqliteTestDatabase database;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  /** {@code profile_templates} as it stood before blueprints gained a category or a glyph. */
  private void rollBackToTheShapeWithoutCategory() {
    database.jdbc().execute("DROP TABLE profile_templates");
    database.jdbc().execute("""
        CREATE TABLE profile_templates (
          id          TEXT PRIMARY KEY,
          name        TEXT NOT NULL UNIQUE,
          description TEXT,
          provider    TEXT,
          model       TEXT,
          base_url    TEXT,
          cwd         TEXT,
          soul        TEXT,
          memory      TEXT,
          skills      TEXT,
          mcp_servers TEXT,
          secrets     TEXT,
          created_at  INTEGER NOT NULL,
          updated_at  INTEGER NOT NULL
        )
        """);
  }

  private List<String> columnsOfProfileTemplates() {
    return database.jdbc().query("PRAGMA table_info(profile_templates)",
        (rs, n) -> rs.getString("name").toLowerCase(Locale.ROOT));
  }

  private SchemaUpgrades upgrades() {
    return new SchemaUpgrades(database.jdbc());
  }

  @Test
  void aDatabaseFromThePreviousReleaseGainsTheColumnItIsMissing() {
    rollBackToTheShapeWithoutCategory();
    assertFalse(columnsOfProfileTemplates().contains("category"), "test set up the wrong shape");

    upgrades().apply();

    assertTrue(columnsOfProfileTemplates().contains("category"));
  }

  @Test
  void aDatabaseSeveralReleasesBehindGainsEveryColumnItIsMissing() {
    rollBackToTheShapeWithoutCategory();

    upgrades().apply();

    // one boot catches a database up, however many releases it skipped
    assertTrue(columnsOfProfileTemplates().contains("category"));
    assertTrue(columnsOfProfileTemplates().contains("icon"));
  }

  @Test
  void anUpgradedDatabaseStillReadsTheRowsItAlreadyHeld() {
    rollBackToTheShapeWithoutCategory();
    database.jdbc().update("""
        INSERT INTO profile_templates
          (id, name, description, provider, model, base_url, cwd, soul, memory,
           skills, mcp_servers, secrets, created_at, updated_at)
        VALUES ('pt-1', 'ops', 'd', 'anthropic', 'm', '', '/work', '', '', '[]', '[]', '[]', 1, 2)
        """);

    upgrades().apply();

    // the whole point: existing blueprints survive the upgrade, filed under nothing
    ProfileTemplate found = new ProfileTemplateRepository(database.jdbc(), new ObjectMapper())
        .findById("pt-1").orElseThrow();
    assertEquals("ops", found.name());
    assertNull(found.category());
    assertNull(found.icon());
  }

  @Test
  void runningTwiceIsNotAnError() {
    rollBackToTheShapeWithoutCategory();

    upgrades().apply();
    upgrades().apply();

    // sqlite's ALTER TABLE ADD COLUMN has no IF NOT EXISTS, so a second boot would
    // fail outright if the column check were not doing its job
    assertEquals(1, columnsOfProfileTemplates().stream().filter("category"::equals).count());
  }

  @Test
  void aCurrentDatabaseIsLeftExactlyAsItIs() {
    List<String> before = columnsOfProfileTemplates();

    upgrades().apply();

    assertEquals(before, columnsOfProfileTemplates());
  }

  @Test
  void aTableThatDoesNotExistIsSkippedRatherThanCreated() {
    database.jdbc().execute("DROP TABLE profile_templates");

    upgrades().apply();

    assertTrue(columnsOfProfileTemplates().isEmpty());
  }

  // ── model_providers.kind, dropped now the protocol is probed ─────────────

  /** {@code model_providers} as it shipped while the protocol was stored. */
  private void rollBackToTheShapeWithStoredKind() {
    database.jdbc().execute("DROP TABLE model_providers");
    database.jdbc().execute("CREATE TABLE model_providers ("
        + "id TEXT PRIMARY KEY,"
        + "name TEXT NOT NULL,"
        + "url TEXT NOT NULL UNIQUE,"
        + "kind TEXT NOT NULL CHECK (kind IN ('ollama')),"
        + "created_at INTEGER NOT NULL)");
  }

  private Set<String> columnsOfModelProviders() {
    return Set.copyOf(database.jdbc().query("PRAGMA table_info(model_providers)",
        (rs, n) -> rs.getString("name").toLowerCase(Locale.ROOT)));
  }

  @Test
  void theUpgradeDropsKindAndKeepsTheRowsThatHadOne() {
    rollBackToTheShapeWithStoredKind();
    database.jdbc().update("INSERT INTO model_providers (id, name, url, kind, created_at)"
        + " VALUES ('mp-1', 'box', 'http://box:11434', 'ollama', 1)");

    upgrades().apply();

    assertFalse(columnsOfModelProviders().contains("kind"));
    // the endpoint itself survived: only the stored protocol went
    assertEquals(List.of("http://box:11434"), database.jdbc().queryForList(
        "SELECT url FROM model_providers WHERE id = 'mp-1'", String.class));
  }

  /**
   * The reason a rebuild is needed at all. SQLite refuses {@code DROP COLUMN} on a column
   * named in a CHECK — which is precisely the constraint being removed.
   */
  @Test
  void theOldCheckIsGoneSoAnyProtocolCanBeProbedLater() {
    rollBackToTheShapeWithStoredKind();

    upgrades().apply();

    database.jdbc().update("INSERT INTO model_providers (id, name, url, created_at)"
        + " VALUES ('mp-2', 'mac', 'http://mac:1234', 1)");
    assertEquals(1, (int) database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM model_providers", Integer.class));
  }

  @Test
  void theUniqueUrlConstraintSurvivesTheRebuild() {
    rollBackToTheShapeWithStoredKind();
    upgrades().apply();
    database.jdbc().update("INSERT INTO model_providers (id, name, url, created_at)"
        + " VALUES ('mp-1', 'box', 'http://box:11434', 1)");

    // UNIQUE(url) is what stops one server being registered twice; a rebuild that dropped it
    // would let duplicates in silently
    assertThrows(Exception.class, () -> database.jdbc().update(
        "INSERT INTO model_providers (id, name, url, created_at)"
            + " VALUES ('mp-2', 'copy', 'http://box:11434', 1)"));
  }

  @Test
  void theUpgradeIsIdempotentAndLeavesNoHalfBuiltTable() {
    rollBackToTheShapeWithStoredKind();
    database.jdbc().update("INSERT INTO model_providers (id, name, url, kind, created_at)"
        + " VALUES ('mp-1', 'box', 'http://box:11434', 'ollama', 1)");

    upgrades().apply();
    upgrades().apply();

    assertEquals(1, (int) database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM model_providers", Integer.class));
    assertEquals(0, (int) database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM sqlite_master WHERE name = 'model_providers_new'", Integer.class));
  }
}
