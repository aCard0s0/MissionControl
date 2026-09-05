package io.hermes.missioncontrol.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Columns added to a table that had already shipped.
 *
 * <p>{@code schema.sql} is all {@code CREATE TABLE IF NOT EXISTS}, which is a no-op against a
 * database that already holds the table — so a new column declared there reaches fresh installs
 * only, and every existing deployment would fail on the first read of it. SQLite has no
 * {@code ADD COLUMN IF NOT EXISTS} either, and the schema script runs without
 * {@code continueOnError}, so a bare {@code ALTER} in it would break boot on the second start.
 *
 * <p>Hence this: read the table's actual columns, add what is missing, once. Idempotent by
 * construction rather than by catching an error, and it runs at context startup — annotated to
 * be ordered after the schema script — so every repository below still reads a complete row.
 *
 * <p>Deliberately additive only. Nothing here drops or rewrites a column: this exists so an
 * operator's data survives an upgrade, and a destructive step in the same mechanism would be
 * the one thing that could take it away.
 *
 * <p>{@link #moveEndpointsOffModelProviders()} is the one exception, and it runs once ever. It
 * carries rows from a table that was renamed; every row is copied across untouched before the
 * old table goes.
 */
@Component
@DependsOnDatabaseInitialization
class SchemaUpgrades {

  private static final Logger log = LoggerFactory.getLogger(SchemaUpgrades.class);

  /** A column this application expects, and the definition to add it with. */
  private record AddedColumn(String table, String column, String definition) {}

  private static final List<AddedColumn> COLUMNS = List.of(
      // Blueprints gained a category so the page can file and filter them.
      new AddedColumn("profile_templates", "category", "TEXT"),
      // …and a glyph, drawn beside the name in the list and the editor.
      new AddedColumn("profile_templates", "icon", "TEXT"),
      // …and two more lists to deploy: rows of the skill library, and guides.
      new AddedColumn("profile_templates", "library_skill_ids", "TEXT"),
      new AddedColumn("profile_templates", "guide_ids", "TEXT"),
      // A catalog entry gained a link to where it comes from, for the roster's `repo` button.
      new AddedColumn("mcp_servers", "repo_url", "TEXT"));

  private final JdbcTemplate jdbc;

  SchemaUpgrades(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @PostConstruct
  void apply() {
    for (AddedColumn added : COLUMNS) {
      if (!tableExists(added.table()) || columns(added.table()).contains(added.column())) {
        continue;
      }
      log.info("adding column {}.{} to an existing database", added.table(), added.column());
      // identifiers come from the constant above, never from a request
      jdbc.execute("ALTER TABLE " + added.table()
          + " ADD COLUMN " + added.column() + " " + added.definition());
    }
    moveEndpointsOffModelProviders();
  }

  /**
   * Carries inference endpoints from {@code model_providers} to {@code inference_endpoints}.
   *
   * <p>The table shipped as {@code model_providers} while the concept was still called a model
   * provider. It is not one: a row here is a URL you run — ollama, or anything OpenAI-compatible
   * — while a model provider is an upstream vendor, and that one is a compiled-in list with no
   * table at all. Two things called the same name, one route rename apart from being confused in
   * a diff, so the table follows the rename its route and service already had.
   *
   * <p>A copy rather than {@code ALTER TABLE … RENAME TO}: this class runs after the schema
   * script, which has already created an empty {@code inference_endpoints}, so there is nowhere
   * to rename onto. Copying also lets the column list be explicit, which is what drops a legacy
   * {@code kind} column on a database old enough to still carry one — the protocol is probed,
   * not stored, and SQLite refuses {@code DROP COLUMN} on a column named in a CHECK.
   *
   * <p>Guarded on the url rather than on "did this run", so a second pass moves nothing and
   * still cannot lose a row. One connection, one transaction: as separate autocommitted
   * statements this could be interrupted between the INSERT and the DROP, and a retry would
   * then see rows in both tables.
   */
  private void moveEndpointsOffModelProviders() {
    if (!tableExists("model_providers")) {
      return;
    }
    log.info("moving inference endpoints from model_providers to inference_endpoints");
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO inference_endpoints (id, name, url, created_at)"
            + " SELECT id, name, url, created_at FROM model_providers"
            + " WHERE url NOT IN (SELECT url FROM inference_endpoints)");
        statement.execute("DROP TABLE model_providers");
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
      return null;
    });
  }

  private boolean tableExists(String table) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        Integer.class, table);
    return count != null && count > 0;
  }

  /** Lower-cased, because sqlite column names are case-insensitive. */
  private Set<String> columns(String table) {
    return Set.copyOf(jdbc.query("PRAGMA table_info(" + table + ")",
        (rs, n) -> rs.getString("name").toLowerCase(Locale.ROOT)));
  }
}
