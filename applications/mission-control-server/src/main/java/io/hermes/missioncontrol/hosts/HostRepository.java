package io.hermes.missioncontrol.hosts;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class HostRepository {

  public record HostRow(String id, String name, String url, String kind) {}

  private static final RowMapper<HostRow> MAPPER = (rs, n) ->
      new HostRow(rs.getString("id"), rs.getString("name"), rs.getString("url"), rs.getString("kind"));

  private final JdbcTemplate jdbc;

  public HostRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<HostRow> findAll() {
    return jdbc.query("SELECT id, name, url, kind FROM docker_hosts ORDER BY created_at", MAPPER);
  }

  public Optional<HostRow> findById(String id) {
    return jdbc.query("SELECT id, name, url, kind FROM docker_hosts WHERE id = ?", MAPPER, id)
        .stream().findFirst();
  }

  public boolean urlExists(String url) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM docker_hosts WHERE url = ?", Integer.class, url);
    return count != null && count > 0;
  }

  public void insert(HostRow row) {
    jdbc.update("INSERT INTO docker_hosts (id, name, url, kind, created_at) VALUES (?, ?, ?, ?, ?)",
        row.id(), row.name(), row.url(), row.kind(), System.currentTimeMillis());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM docker_hosts WHERE id = ?", id);
  }
}
