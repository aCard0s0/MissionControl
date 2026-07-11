package io.hermes.missioncontrol.mcp;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class RetainedResourceRepository {

  private static final RowMapper<RetainedResourceDto> MAPPER = (rs, n) -> new RetainedResourceDto(
      rs.getString("id"), rs.getString("server_id"), rs.getString("server_name"),
      rs.getString("host_id"), rs.getString("type"), rs.getString("name"), rs.getLong("created_at"));
  private final JdbcTemplate jdbc;

  RetainedResourceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<RetainedResourceDto> findAll() {
    return jdbc.query("SELECT * FROM mcp_retained_resources ORDER BY created_at DESC", MAPPER);
  }

  Optional<RetainedResourceDto> findById(String id) {
    return jdbc.query("SELECT * FROM mcp_retained_resources WHERE id = ?", MAPPER, id).stream().findFirst();
  }

  void retain(String serverId, String serverName, String hostId, String volumeName) {
    jdbc.update("""
        INSERT INTO mcp_retained_resources
          (id, server_id, server_name, host_id, type, name, created_at)
        VALUES (?, ?, ?, ?, 'volume', ?, ?)
        ON CONFLICT(host_id, type, name) DO NOTHING
        """, "mrr-" + UUID.randomUUID().toString().substring(0, 12), serverId, serverName,
        hostId, volumeName, System.currentTimeMillis());
  }

  RetainedResourceDto require(String id) {
    return findById(id).orElseThrow(() -> new NoSuchElementException("unknown retained resource: " + id));
  }

  void delete(String id) {
    jdbc.update("DELETE FROM mcp_retained_resources WHERE id = ?", id);
  }
}
