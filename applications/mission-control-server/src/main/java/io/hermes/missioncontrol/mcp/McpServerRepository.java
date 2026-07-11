package io.hermes.missioncontrol.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class McpServerRepository {

  record ServerRow(
      String id,
      String name,
      String description,
      String kind,
      String hostId,
      String serviceKey,
      String configJson,
      String desiredState,
      String runtimeState,
      String operationState,
      String operationError,
      long revision,
      long appliedRevision,
      String seedKey,
      String checkStatus,
      String checkError,
      Long checkedAt,
      Long latencyMs,
      long createdAt,
      long updatedAt) {}

  private static final RowMapper<ServerRow> MAPPER = (rs, n) -> map(rs);
  private final JdbcTemplate jdbc;

  McpServerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<ServerRow> findAll() {
    return jdbc.query("SELECT * FROM mcp_servers ORDER BY name COLLATE NOCASE", MAPPER);
  }

  List<ServerRow> findByHost(String hostId) {
    return jdbc.query("SELECT * FROM mcp_servers WHERE host_id = ? ORDER BY created_at", MAPPER, hostId);
  }

  Optional<ServerRow> findById(String id) {
    return jdbc.query("SELECT * FROM mcp_servers WHERE id = ?", MAPPER, id).stream().findFirst();
  }

  Optional<ServerRow> findBySeedKey(String seedKey) {
    return jdbc.query("SELECT * FROM mcp_servers WHERE seed_key = ?", MAPPER, seedKey).stream().findFirst();
  }

  boolean nameExists(String name, String exceptId) {
    Integer count = exceptId == null
        ? jdbc.queryForObject("SELECT COUNT(*) FROM mcp_servers WHERE name = ? COLLATE NOCASE", Integer.class, name)
        : jdbc.queryForObject("SELECT COUNT(*) FROM mcp_servers WHERE name = ? COLLATE NOCASE AND id <> ?", Integer.class, name, exceptId);
    return count != null && count > 0;
  }

  void insert(ServerRow row) {
    jdbc.update("""
        INSERT INTO mcp_servers
          (id, name, description, kind, host_id, service_key, config_json,
           desired_state, runtime_state, operation_state, operation_error,
           revision, applied_revision, seed_key, check_status, check_error,
           checked_at, latency_ms, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(), row.name(), row.description(), row.kind(), row.hostId(), row.serviceKey(),
        row.configJson(), row.desiredState(), row.runtimeState(), row.operationState(),
        row.operationError(), row.revision(), row.appliedRevision(), row.seedKey(),
        row.checkStatus(), row.checkError(), row.checkedAt(), row.latencyMs(),
        row.createdAt(), row.updatedAt());
  }

  void updateDefinition(
      String id, String name, String description, String configJson, long revision,
      long appliedRevision, String operationState) {
    jdbc.update("""
        UPDATE mcp_servers
           SET name = ?, description = ?, config_json = ?, revision = ?, applied_revision = ?,
               operation_state = ?, operation_error = NULL, updated_at = ?
         WHERE id = ?
        """, name, description, configJson, revision, appliedRevision, operationState,
        System.currentTimeMillis(), id);
  }

  void beginOperation(String id, String desiredState, String operationState) {
    jdbc.update("""
        UPDATE mcp_servers SET desired_state = ?, operation_state = ?, operation_error = NULL,
          updated_at = ? WHERE id = ?
        """, desiredState, operationState, System.currentTimeMillis(), id);
  }

  void finishOperation(String id, String runtimeState, long appliedRevision) {
    jdbc.update("""
        UPDATE mcp_servers SET runtime_state = ?, operation_state = 'idle', operation_error = NULL,
          applied_revision = ?, updated_at = ? WHERE id = ?
        """, runtimeState, appliedRevision, System.currentTimeMillis(), id);
  }

  void failOperation(String id, String message) {
    jdbc.update("""
        UPDATE mcp_servers SET runtime_state = 'error', operation_state = 'error',
          operation_error = ?, updated_at = ? WHERE id = ?
        """, brief(message), System.currentTimeMillis(), id);
  }

  void updateRuntime(String id, String runtimeState) {
    jdbc.update("UPDATE mcp_servers SET runtime_state = ? WHERE id = ?", runtimeState, id);
  }

  void updateCheck(String id, String status, String error, Long checkedAt, Long latencyMs) {
    jdbc.update("""
        UPDATE mcp_servers SET check_status = ?, check_error = ?, checked_at = ?, latency_ms = ?,
          updated_at = ? WHERE id = ?
        """, status, brief(error), checkedAt, latencyMs, System.currentTimeMillis(), id);
  }

  void delete(String id) {
    jdbc.update("DELETE FROM mcp_servers WHERE id = ?", id);
  }

  Optional<String> meta(String key) {
    return jdbc.query("SELECT value FROM mcp_registry_meta WHERE key = ?", (rs, n) -> rs.getString(1), key)
        .stream().findFirst();
  }

  void putMeta(String key, String value) {
    jdbc.update("""
        INSERT INTO mcp_registry_meta (key, value) VALUES (?, ?)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """, key, value);
  }

  private static ServerRow map(ResultSet rs) throws SQLException {
    return new ServerRow(
        rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getString("kind"),
        rs.getString("host_id"), rs.getString("service_key"), rs.getString("config_json"),
        rs.getString("desired_state"), rs.getString("runtime_state"), rs.getString("operation_state"),
        rs.getString("operation_error"), rs.getLong("revision"), rs.getLong("applied_revision"),
        rs.getString("seed_key"), rs.getString("check_status"), rs.getString("check_error"),
        nullableLong(rs, "checked_at"), nullableLong(rs, "latency_ms"),
        rs.getLong("created_at"), rs.getLong("updated_at"));
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static String brief(String value) {
    if (value == null) return null;
    String line = value.lines().findFirst().orElse(value);
    return line.length() > 500 ? line.substring(0, 500) : line;
  }
}
