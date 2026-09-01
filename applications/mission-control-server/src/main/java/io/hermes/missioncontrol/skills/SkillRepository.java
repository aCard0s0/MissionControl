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

/** SQL for the skill library. */
@Repository
public class SkillRepository {

  private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

  private final RowMapper<Skill> mapper = (rs, n) -> new Skill(
      rs.getString("id"),
      rs.getString("kind"),
      rs.getString("name"),
      rs.getString("description"),
      rs.getString("category"),
      rs.getString("repo_url"),
      rs.getString("version"),
      files(rs.getString("files")),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SkillRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Newest edit first — the library is browsed, so what was just touched reads first. */
  public List<Skill> findAll() {
    return jdbc.query("SELECT * FROM skills ORDER BY updated_at DESC", mapper);
  }

  public List<Skill> findByCategory(String category) {
    return jdbc.query(
        "SELECT * FROM skills WHERE category = ? ORDER BY updated_at DESC", mapper, category);
  }

  public Optional<Skill> find(String id) {
    return jdbc.query("SELECT * FROM skills WHERE id = ?", mapper, id).stream().findFirst();
  }

  /** By the name a deploy uses, so an import can update the row it already has rather
   *  than colliding with the {@code NOCASE UNIQUE} index. */
  public Optional<Skill> findByName(String name) {
    return jdbc.query("SELECT * FROM skills WHERE name = ?", mapper, name).stream().findFirst();
  }

  public void insert(Skill skill) {
    jdbc.update(
        "INSERT INTO skills (id, kind, name, description, category, repo_url, version, files, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        skill.id(), skill.kind(), skill.name(), skill.description(), skill.category(),
        skill.repoUrl(), skill.version(), writeFiles(skill.files()),
        skill.createdAt(), skill.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the skill was first saved. */
  public int update(Skill skill) {
    return jdbc.update(
        "UPDATE skills SET kind = ?, name = ?, description = ?, category = ?, repo_url = ?, "
            + "version = ?, files = ?, updated_at = ? WHERE id = ?",
        skill.kind(), skill.name(), skill.description(), skill.category(), skill.repoUrl(),
        skill.version(), writeFiles(skill.files()), skill.updatedAt(), skill.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM skills WHERE id = ?", id);
  }

  /** An unparseable column degrades to empty and says so, rather than failing the read and
   *  taking the whole library off the page with it. */
  private List<SkillFile> files(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<SkillFile>>() {});
    } catch (Exception e) {
      log.warn("dropping unparseable files column on a skill: {}", e.getMessage());
      return List.of();
    }
  }

  /** A hub row stores NULL rather than {@code []} — it owns no content, and a stored
   *  empty array would read as "a local skill with no files", which cannot deploy. */
  private String writeFiles(List<SkillFile> files) {
    if (files == null || files.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(files);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize skill files", e);
    }
  }
}
