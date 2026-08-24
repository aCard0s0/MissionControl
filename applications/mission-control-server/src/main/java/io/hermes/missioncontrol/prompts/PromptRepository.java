package io.hermes.missioncontrol.prompts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the prompt library, plus the one-key marker table its seed is guarded on. */
@Repository
public class PromptRepository {

  private static final Logger log = LoggerFactory.getLogger(PromptRepository.class);

  private final RowMapper<Prompt> mapper = (rs, n) -> new Prompt(
      rs.getString("id"),
      rs.getString("title"),
      rs.getString("body"),
      rs.getString("category"),
      rs.getString("notes"),
      readTags(rs.getString("tags")),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public PromptRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Newest edit first — the library is browsed, so what was just touched reads first. */
  public List<Prompt> findAll() {
    return jdbc.query("SELECT * FROM prompts ORDER BY updated_at DESC", mapper);
  }

  public List<Prompt> findByCategory(String category) {
    return jdbc.query(
        "SELECT * FROM prompts WHERE category = ? ORDER BY updated_at DESC", mapper, category);
  }

  public Optional<Prompt> find(String id) {
    return jdbc.query("SELECT * FROM prompts WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(Prompt prompt) {
    jdbc.update(
        "INSERT INTO prompts (id, title, body, category, notes, tags, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        prompt.id(), prompt.title(), prompt.body(), prompt.category(), prompt.notes(),
        writeTags(prompt.tags()), prompt.createdAt(), prompt.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the prompt was first saved. */
  public int update(Prompt prompt) {
    return jdbc.update(
        "UPDATE prompts SET title = ?, body = ?, category = ?, notes = ?, tags = ?, "
            + "updated_at = ? WHERE id = ?",
        prompt.title(), prompt.body(), prompt.category(), prompt.notes(),
        writeTags(prompt.tags()), prompt.updatedAt(), prompt.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM prompts WHERE id = ?", id);
  }

  public Optional<String> meta(String key) {
    return jdbc.query("SELECT value FROM prompt_meta WHERE key = ?", (rs, n) -> rs.getString(1), key)
        .stream().findFirst();
  }

  public void putMeta(String key, String value) {
    jdbc.update("""
        INSERT INTO prompt_meta (key, value) VALUES (?, ?)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """, key, value);
  }

  /** Tags are a JSON array. This table was created with that encoding, so unlike
   *  {@code board_tasks} there is no legacy comma-separated form to read. */
  private List<String> readTags(String tags) {
    if (tags == null || tags.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(tags, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      log.warn("dropping unparseable tags column on a prompt: {}", e.getMessage());
      return List.of();
    }
  }

  private String writeTags(List<String> tags) {
    try {
      return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize prompt tags", e);
    }
  }
}
