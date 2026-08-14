package io.hermes.missioncontrol.support;

import io.hermes.missioncontrol.SqliteExceptionTranslator;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * An in-memory sqlite database with the production schema applied, for tests that
 * exercise real SQL instead of a mocked repository.
 *
 * <p>{@code jdbc:sqlite::memory:} is scoped to a single connection, so the database
 * lives and dies with the one held here — each {@link #open()} is an isolated instance
 * and {@link #close()} discards it.
 *
 * <p>Deliberately does not enable {@code PRAGMA foreign_keys}. Production runs with
 * sqlite's default (off), so turning it on here would make tests disagree with the
 * behaviour they are meant to protect.
 */
public final class SqliteTestDatabase implements AutoCloseable {

  private final Connection connection;
  private final SingleConnectionDataSource dataSource;
  private final JdbcTemplate jdbc;

  private SqliteTestDatabase(Connection connection, SingleConnectionDataSource dataSource) {
    this.connection = connection;
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    // same translator JdbcConfig installs in production — without it, constraint
    // failures would surface as a different exception type here than at runtime
    this.jdbc.setExceptionTranslator(new SqliteExceptionTranslator());
  }

  public static SqliteTestDatabase open() throws Exception {
    Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    return new SqliteTestDatabase(connection, dataSource);
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public DataSource dataSource() {
    return dataSource;
  }

  @Override
  public void close() throws Exception {
    connection.close();
  }
}
