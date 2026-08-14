package io.hermes.missioncontrol.board;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.ContainerIdListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BoardRepository implements ContainerIdListener {

  private static final Logger log = LoggerFactory.getLogger(BoardRepository.class);

  private final RowMapper<BoardTask> mapper = (rs, n) -> new BoardTask(
      rs.getString("id"),
      rs.getString("container_id"),
      rs.getString("agent_id"),
      rs.getString("title"),
      rs.getString("col"),
      rs.getString("priority"),
      readTags(rs.getString("tags")),
      rs.getLong("created_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public BoardRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Tags are JSON, like every other list column in this schema. Rows written before that
   * are comma-separated, and there is no migration step — {@code schema.sql} only creates
   * missing tables — so the legacy branch stays for as long as those databases exist.
   */
  private List<String> readTags(String tags) {
    if (tags == null || tags.isBlank()) {
      return List.of();
    }
    if (!tags.startsWith("[")) {
      return List.of(tags.split(","));
    }
    try {
      return objectMapper.readValue(tags, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      log.warn("dropping unparseable tags column on a board task: {}", e.getMessage());
      return List.of();
    }
  }

  private String writeTags(List<String> tags) {
    try {
      return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize board task tags", e);
    }
  }

  public List<BoardTask> findByContainer(String containerId) {
    return jdbc.query(
        "SELECT * FROM board_tasks WHERE container_id = ? ORDER BY created_at", mapper, containerId);
  }

  /** Container ids are globally unique, so the host does not narrow this further. */
  @Override
  public int onContainerReplaced(String hostId, String oldContainerId, String newContainerId) {
    return jdbc.update(
        "UPDATE board_tasks SET container_id = ? WHERE container_id = ?",
        newContainerId, oldContainerId);
  }

  public List<BoardTask> findAll() {
    return jdbc.query("SELECT * FROM board_tasks ORDER BY created_at", mapper);
  }

  public void insert(BoardTask task) {
    jdbc.update(
        "INSERT INTO board_tasks (id, container_id, agent_id, title, col, priority, tags, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        task.id(), task.containerId(), task.agentId(), task.title(), task.column(),
        task.priority(), writeTags(task.tags()), task.createdAt());
  }

  public int updateColumn(String id, String column) {
    return jdbc.update("UPDATE board_tasks SET col = ? WHERE id = ?", column, id);
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM board_tasks WHERE id = ?", id);
  }
}
