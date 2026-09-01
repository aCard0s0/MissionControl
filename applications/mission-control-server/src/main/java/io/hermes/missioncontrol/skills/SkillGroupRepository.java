package io.hermes.missioncontrol.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the skill groups. */
@Repository
public class SkillGroupRepository {

  private static final Logger log = LoggerFactory.getLogger(SkillGroupRepository.class);

  private final RowMapper<SkillGroup> mapper = (rs, n) -> new SkillGroup(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      ids(rs.getString("skill_ids")),
      rs.getString("guide_id"),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SkillGroupRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * By name, not by newest edit.
   *
   * <p>The other libraries read newest-first because they are browsed. These are headers the
   * skills list is filed under, so their order is the reading order of the whole page — and a
   * header that jumps to the top because someone renamed it moves every skill beneath it.
   */
  public List<SkillGroup> findAll() {
    return jdbc.query("SELECT * FROM skill_groups ORDER BY name COLLATE NOCASE", mapper);
  }

  public Optional<SkillGroup> find(String id) {
    return jdbc.query("SELECT * FROM skill_groups WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(SkillGroup group) {
    jdbc.update(
        "INSERT INTO skill_groups (id, name, description, skill_ids, guide_id, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        group.id(), group.name(), group.description(), ids(group.skillIds()), group.guideId(),
        group.createdAt(), group.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the group was first saved. */
  public int update(SkillGroup group) {
    return jdbc.update(
        "UPDATE skill_groups SET name = ?, description = ?, skill_ids = ?, guide_id = ?, "
            + "updated_at = ? WHERE id = ?",
        group.name(), group.description(), ids(group.skillIds()), group.guideId(),
        group.updatedAt(), group.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM skill_groups WHERE id = ?", id);
  }

  /** An unparseable column degrades to empty and says so, rather than failing the read. */
  private List<String> ids(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      log.warn("dropping unparseable skill_ids column on a group: {}", e.getMessage());
      return List.of();
    }
  }

  private String ids(List<String> ids) {
    try {
      return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize group skill ids", e);
    }
  }
}
