package io.hermes.missioncontrol.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.common.IdList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the guide library. */
@Repository
public class SkillGuideRepository {



  private final RowMapper<SkillGuide> mapper = (rs, n) -> new SkillGuide(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      rs.getString("body"),
      rs.getString("category"),
      ids(rs, "skill_ids"),
      ids(rs, "mcp_server_ids"),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));


  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SkillGuideRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Newest edit first — the library is browsed, so what was just touched reads first. */
  public List<SkillGuide> findAll() {
    return jdbc.query("SELECT * FROM skill_guides ORDER BY updated_at DESC", mapper);
  }

  public List<SkillGuide> findByCategory(String category) {
    return jdbc.query(
        "SELECT * FROM skill_guides WHERE category = ? ORDER BY updated_at DESC", mapper, category);
  }

  public Optional<SkillGuide> find(String id) {
    return jdbc.query("SELECT * FROM skill_guides WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(SkillGuide guide) {
    jdbc.update(
        "INSERT INTO skill_guides (id, name, description, body, category, skill_ids, "
            + "mcp_server_ids, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        guide.id(), guide.name(), guide.description(), guide.body(), guide.category(),
        IdList.write(objectMapper, guide.skillIds()), IdList.write(objectMapper, guide.mcpServerIds()),
        guide.createdAt(), guide.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the guide was first saved. */
  public int update(SkillGuide guide) {
    return jdbc.update(
        "UPDATE skill_guides SET name = ?, description = ?, body = ?, category = ?, "
            + "skill_ids = ?, mcp_server_ids = ?, updated_at = ? WHERE id = ?",
        guide.name(), guide.description(), guide.body(), guide.category(),
        IdList.write(objectMapper, guide.skillIds()), IdList.write(objectMapper, guide.mcpServerIds()),
        guide.updatedAt(), guide.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM skill_guides WHERE id = ?", id);
  }



  /** Deferred to a method so the row mapper, a field initializer, may read {@code objectMapper}. */
  private List<String> ids(ResultSet rs, String column) throws SQLException {
    return IdList.read(objectMapper, rs.getString(column), column);
  }
}
