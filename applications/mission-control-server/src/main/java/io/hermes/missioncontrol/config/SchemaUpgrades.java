package io.hermes.missioncontrol.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>{@link #widenEndpointKinds()} is additive in the same sense even though it rebuilds a
 * table. SQLite cannot alter a {@code CHECK} in place, and the constraint only ever gains
 * values — every row legal before is still legal after, and the copy carries them across.
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

  /**
   * Endpoint protocols {@code model_providers.kind} accepts. Adding one here is the only
   * schema change a new {@code EndpointClient} needs — {@link #widenEndpointKinds()} notices
   * the stored constraint no longer matches and rebuilds the table to suit.
   */
  private static final List<String> ENDPOINT_KINDS = List.of("ollama", "openai");

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
    widenEndpointKinds();
  }

  /**
   * Widens {@code model_providers.kind}'s CHECK to every kind in {@link #ENDPOINT_KINDS}.
   *
   * <p>{@code schema.sql} is {@code CREATE TABLE IF NOT EXISTS}, so a database created before a
   * kind was added still carries the old constraint and rejects the new value on insert — with
   * a bare "CHECK constraint failed", which reads like a bug rather than a stale schema.
   *
   * <p>Detection is on the stored DDL rather than a version counter: if the table's own SQL
   * already names every kind there is nothing to do. That makes this idempotent, and makes
   * adding the next kind a one-line edit to the constant above.
   */
  private void widenEndpointKinds() {
    if (!tableExists("model_providers")) return;
    String ddl = jdbc.queryForObject(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'model_providers'",
        String.class);
    if (ddl == null || ENDPOINT_KINDS.stream().allMatch(kind -> ddl.contains("'" + kind + "'"))) {
      return;
    }
    String allowed = ENDPOINT_KINDS.stream()
        .map(kind -> "'" + kind + "'")
        .collect(Collectors.joining(","));
    log.info("widening model_providers.kind to {}", allowed);
    // The documented SQLite table rebuild, on ONE connection inside ONE transaction. Run as
    // four separate jdbc.execute calls it would autocommit each step and could be interrupted
    // between the DROP and the RENAME — leaving the deployment with no model_providers table
    // at all. SQLite makes DDL transactional, so the whole swap either lands or does not.
    // Nothing holds a foreign key onto this table, so no PRAGMA foreign_keys dance is needed.
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        // a previous run killed between CREATE and RENAME would leave this behind
        statement.execute("DROP TABLE IF EXISTS model_providers_new");
        statement.execute("CREATE TABLE model_providers_new ("
            + "id TEXT PRIMARY KEY,"
            + "name TEXT NOT NULL,"
            + "url TEXT NOT NULL UNIQUE,"
            + "kind TEXT NOT NULL CHECK (kind IN (" + allowed + ")),"
            + "created_at INTEGER NOT NULL)");
        statement.execute("INSERT INTO model_providers_new"
            + " SELECT id, name, url, kind, created_at FROM model_providers");
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
