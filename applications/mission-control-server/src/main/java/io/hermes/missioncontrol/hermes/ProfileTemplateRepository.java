package io.hermes.missioncontrol.hermes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JDBC store for {@link ProfileTemplate}s; list-valued columns are JSON text. */
@Repository
public class ProfileTemplateRepository {

  private static final Logger log = LoggerFactory.getLogger(ProfileTemplateRepository.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  private final RowMapper<ProfileTemplate> mapper = (rs, n) -> new ProfileTemplate(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      rs.getString("provider"),
      rs.getString("model"),
      rs.getString("base_url"),
      rs.getString("cwd"),
      rs.getString("soul"),
      rs.getString("memory"),
      readList(rs.getString("skills"), String.class),
      readList(rs.getString("mcp_servers"), McpServerSpec.class),
      readList(rs.getString("secrets"), StoredSecret.class),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  public ProfileTemplateRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public List<ProfileTemplate> findAll() {
    return jdbc.query("SELECT * FROM profile_templates ORDER BY updated_at DESC", mapper);
  }

  public Optional<ProfileTemplate> findById(String id) {
    return jdbc.query("SELECT * FROM profile_templates WHERE id = ?", mapper, id).stream().findFirst();
  }

  public boolean existsByName(String name) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM profile_templates WHERE name = ?", Integer.class, name);
    return count != null && count > 0;
  }

  /** Like {@link #existsByName} but ignores the row with {@code id} — for the
   *  rename check on update, where the template keeping its own name is fine. */
  public boolean existsByNameExcept(String name, String id) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM profile_templates WHERE name = ? AND id <> ?", Integer.class, name, id);
    return count != null && count > 0;
  }

  public void insert(ProfileTemplate t) {
    jdbc.update(
        "INSERT INTO profile_templates "
            + "(id, name, description, provider, model, base_url, cwd, soul, memory, "
            + "skills, mcp_servers, secrets, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        t.id(), t.name(), t.description(), t.provider(), t.model(), t.baseUrl(), t.cwd(),
        t.soul(), t.memory(), writeJson(t.skills()), writeJson(t.mcpServers()), writeJson(t.secrets()),
        t.createdAt(), t.updatedAt());
  }

  public void update(ProfileTemplate t) {
    jdbc.update(
        "UPDATE profile_templates SET name = ?, description = ?, provider = ?, model = ?, "
            + "base_url = ?, cwd = ?, soul = ?, memory = ?, skills = ?, mcp_servers = ?, "
            + "secrets = ?, updated_at = ? WHERE id = ?",
        t.name(), t.description(), t.provider(), t.model(), t.baseUrl(), t.cwd(),
        t.soul(), t.memory(), writeJson(t.skills()), writeJson(t.mcpServers()), writeJson(t.secrets()),
        t.updatedAt(), t.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM profile_templates WHERE id = ?", id);
  }

  private <T> List<T> readList(String json, Class<T> type) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(
          json, objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (Exception e) {
      // corrupt JSON would otherwise vanish silently on every read — surface it
      log.warn("dropping unparseable {} column in profile_templates: {}", type.getSimpleName(), e.getMessage());
      return List.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize template field", e);
    }
  }
}
