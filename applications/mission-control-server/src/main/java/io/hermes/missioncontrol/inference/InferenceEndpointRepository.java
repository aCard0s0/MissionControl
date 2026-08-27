package io.hermes.missioncontrol.inference;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InferenceEndpointRepository {

  public record EndpointRow(String id, String name, String url, String kind) {}

  private static final RowMapper<EndpointRow> MAPPER = (rs, n) ->
      new EndpointRow(rs.getString("id"), rs.getString("name"), rs.getString("url"), rs.getString("kind"));

  private final JdbcTemplate jdbc;

  public InferenceEndpointRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<EndpointRow> findAll() {
    return jdbc.query("SELECT id, name, url, kind FROM model_providers ORDER BY created_at", MAPPER);
  }

  public Optional<EndpointRow> findById(String id) {
    return jdbc.query("SELECT id, name, url, kind FROM model_providers WHERE id = ?", MAPPER, id)
        .stream().findFirst();
  }

  public boolean urlExists(String url) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM model_providers WHERE url = ?", Integer.class, url);
    return count != null && count > 0;
  }

  public void insert(EndpointRow row) {
    jdbc.update("INSERT INTO model_providers (id, name, url, kind, created_at) VALUES (?, ?, ?, ?, ?)",
        row.id(), row.name(), row.url(), row.kind(), System.currentTimeMillis());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM model_providers WHERE id = ?", id);
  }
}
