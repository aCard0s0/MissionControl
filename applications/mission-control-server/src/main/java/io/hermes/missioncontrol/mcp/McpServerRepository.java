package io.hermes.missioncontrol.mcp;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
      String repoUrl,
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
          (id, name, description, repo_url, kind, host_id, service_key, config_json,
           desired_state, runtime_state, operation_state, operation_error,
           revision, applied_revision, seed_key, check_status, check_error,
           checked_at, latency_ms, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(), row.name(), row.description(), row.repoUrl(), row.kind(), row.hostId(),
        row.serviceKey(),
        row.configJson(), row.desiredState(), row.runtimeState(), row.operationState(),
        row.operationError(), row.revision(), row.appliedRevision(), row.seedKey(),
        row.checkStatus(), row.checkError(), row.checkedAt(), row.latencyMs(),
        row.createdAt(), row.updatedAt());
  }

  /**
   * Writes a new definition, but only over the revision the caller read.
   *
   * <p>{@code expectedRevision} is what makes two concurrent saves of the same record safe. Both
   * read revision 3, both compute 4, and without the guard the second silently overwrites the
   * first — the losing operator's edit is gone and their request reported success.
   *
   * <p>Deliberately does not check {@code operation_state}: the boot-time seed repair writes
   * over a record that is legitimately still {@code provisioning}. An operator edit goes through
   * {@link #updateDefinitionIfSettled}, which does.
   *
   * @return false when the record has moved on, or is no longer there
   */
  boolean updateDefinition(
      String id, String name, String description, String repoUrl, String configJson,
      long revision, long appliedRevision, String operationState, long expectedRevision) {
    return jdbc.update("""
        UPDATE mcp_servers
           SET name = ?, description = ?, repo_url = ?, config_json = ?, revision = ?,
               applied_revision = ?, operation_state = ?, operation_error = NULL, updated_at = ?
         WHERE id = ? AND revision = ?
        """, name, description, repoUrl, configJson, revision, appliedRevision, operationState,
        System.currentTimeMillis(), id, expectedRevision) == 1;
  }

  /**
   * {@link #updateDefinition}, refused while an operation is in flight — from the write itself,
   * for the reason {@link #claimOperation} gives. A claim does not bump {@code revision}, so the
   * revision guard alone admits an edit that read the row just before a start or stop claimed
   * it: the edit then overwrites the claim's {@code operation_state} mid-Compose-run, and the
   * run's {@code finishOperation} stamps the new revision applied though the stack it brought up
   * was rendered from the old one.
   *
   * @return false when the record has moved on, is mid-operation, or is no longer there
   */
  boolean updateDefinitionIfSettled(
      String id, String name, String description, String repoUrl, String configJson,
      long revision, long appliedRevision, String operationState, long expectedRevision) {
    List<String> settled = McpOperationState.settledWire();
    String placeholders = String.join(",", Collections.nCopies(settled.size(), "?"));
    List<Object> arguments = new ArrayList<>(Arrays.asList(
        name, description, repoUrl, configJson, revision, appliedRevision, operationState,
        System.currentTimeMillis(), id, expectedRevision));
    arguments.addAll(settled);
    return jdbc.update("""
        UPDATE mcp_servers
           SET name = ?, description = ?, repo_url = ?, config_json = ?, revision = ?,
               applied_revision = ?, operation_state = ?, operation_error = NULL, updated_at = ?
         WHERE id = ? AND revision = ? AND operation_state IN (%s)
        """.formatted(placeholders), arguments.toArray()) == 1;
  }

  /**
   * Takes a record that is not doing anything and records what is about to be done to it — in
   * one statement, so nothing can slip between the two.
   *
   * <p>This is the only thing serializing operations on a record. The alternative it replaced —
   * read the state, decide, then write — leaves a window as long as whatever the caller does in
   * between, and the deletion path spends that window rewriting {@code config.yaml} on every
   * Agent holding the server. A second request claiming the record in that window made the
   * deletion refuse <em>after</em> its listeners had already severed those Agents.
   *
   * @return false when the record is mid-operation, or is no longer there
   */
  boolean claimOperation(String id, String desiredState, String operationState) {
    List<String> settled = McpOperationState.settledWire();
    String placeholders = String.join(",", Collections.nCopies(settled.size(), "?"));
    List<Object> arguments = new ArrayList<>(
        List.of(desiredState, operationState, System.currentTimeMillis(), id));
    arguments.addAll(settled);
    return jdbc.update("""
        UPDATE mcp_servers SET desired_state = ?, operation_state = ?, operation_error = NULL,
          updated_at = ? WHERE id = ? AND operation_state IN (%s)
        """.formatted(placeholders), arguments.toArray()) == 1;
  }

  /** Gives a claim back, for a caller that took one and then could not go through with it. */
  void releaseOperation(String id) {
    jdbc.update("""
        UPDATE mcp_servers SET operation_state = 'idle', operation_error = NULL,
          updated_at = ? WHERE id = ?
        """, System.currentTimeMillis(), id);
  }

  /**
   * Records an operation without asking whether the record is free, for the one caller that must
   * not be refused: {@link McpStartupReconciler}, whose whole job is the records left mid-flight
   * by a dashboard that went down. Those are exactly the rows {@link #claimOperation} declines,
   * and declining them would strand the record in {@code starting} forever.
   */
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
        """, brief(message, 500, null), System.currentTimeMillis(), id);
  }

  void updateRuntime(String id, String runtimeState) {
    jdbc.update("UPDATE mcp_servers SET runtime_state = ? WHERE id = ?", runtimeState, id);
  }

  void updateCheck(String id, String status, String error, Long checkedAt, Long latencyMs) {
    jdbc.update("""
        UPDATE mcp_servers SET check_status = ?, check_error = ?, checked_at = ?, latency_ms = ?,
          updated_at = ? WHERE id = ?
        """, status, brief(error, 500, null), checkedAt, latencyMs, System.currentTimeMillis(), id);
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
        rs.getString("id"), rs.getString("name"), rs.getString("description"),
        rs.getString("repo_url"), rs.getString("kind"),
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
}
