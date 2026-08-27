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
 * <p>{@link #dropEndpointKind()} is the one exception, and it runs once ever. It removes a
 * column whose value is now derived rather than stored; the rows themselves are copied across
 * untouched.
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
      new AddedColumn("profile_templates", "icon", "TEXT"));

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
    dropEndpointKind();
  }

  /**
   * Removes {@code model_providers.kind}.
   *
   * <p>Which protocol answers at a url is a property of the server, and the service probes for
   * it on every status refresh anyway — so the column was a stored copy of something already
   * known, able to go stale the moment a different server appeared behind the same url. It
   * also carried a {@code CHECK} that would have needed a table rebuild for every protocol
   * ever added. This is that rebuild, once, and then never again.
   *
   * <p>{@code ALTER TABLE … DROP COLUMN} cannot do it: SQLite refuses to drop a column named
   * in a CHECK constraint, which is exactly the constraint being removed.
   */
  private void dropEndpointKind() {
    if (!tableExists("model_providers") || !columns("model_providers").contains("kind")) {
      return;
    }
    log.info("dropping model_providers.kind — the protocol is probed, not stored");
    // One connection, one transaction. As separate autocommitted statements this could be
    // interrupted between the DROP and the RENAME and leave no model_providers table at all.
    // SQLite makes DDL transactional, so the swap either lands whole or not at all.
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE model_providers_new ("
            + "id TEXT PRIMARY KEY,"
            + "name TEXT NOT NULL,"
            + "url TEXT NOT NULL UNIQUE,"
            + "created_at INTEGER NOT NULL)");
        statement.execute("INSERT INTO model_providers_new"
            + " SELECT id, name, url, created_at FROM model_providers");
        statement.execute("DROP TABLE model_providers");
        statement.execute("ALTER TABLE model_providers_new RENAME TO model_providers");
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
