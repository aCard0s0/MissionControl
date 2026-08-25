package io.hermes.missioncontrol.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.templates.ProfileTemplate;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateRepository;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
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
}
