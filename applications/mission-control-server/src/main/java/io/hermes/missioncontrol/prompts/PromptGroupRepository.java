package io.hermes.missioncontrol.prompts;

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

/** SQL for the prompt groups. */
@Repository
public class PromptGroupRepository {



  private final RowMapper<PromptGroup> mapper = (rs, n) -> new PromptGroup(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      ids(rs, "prompt_ids"),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));


  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public PromptGroupRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * By name, not by newest edit.
   *
   * <p>The library itself reads newest-first because it is browsed. These are headers the
   * prompt list is filed under, so their order is the reading order of the whole page — and a
   * header that jumped to the top on a rename would move every prompt beneath it.
   */
  public List<PromptGroup> findAll() {
    return jdbc.query("SELECT * FROM prompt_groups ORDER BY name COLLATE NOCASE", mapper);
  }

  public Optional<PromptGroup> find(String id) {
    return jdbc.query("SELECT * FROM prompt_groups WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(PromptGroup group) {
    jdbc.update(
        "INSERT INTO prompt_groups (id, name, description, prompt_ids, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        group.id(), group.name(), group.description(), IdList.write(objectMapper, group.promptIds()),
        group.createdAt(), group.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the group was first saved. */
  public int update(PromptGroup group) {
    return jdbc.update(
        "UPDATE prompt_groups SET name = ?, description = ?, prompt_ids = ?, updated_at = ? "
            + "WHERE id = ?",
        group.name(), group.description(), IdList.write(objectMapper, group.promptIds()), group.updatedAt(), group.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM prompt_groups WHERE id = ?", id);
  }



  /** Deferred to a method so the row mapper, a field initializer, may read {@code objectMapper}. */
  private List<String> ids(ResultSet rs, String column) throws SQLException {
    return IdList.read(objectMapper, rs.getString(column), column);
  }
}
