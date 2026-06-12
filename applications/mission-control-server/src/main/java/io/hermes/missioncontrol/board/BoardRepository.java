package io.hermes.missioncontrol.board;

import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BoardRepository {

  private static final RowMapper<BoardTask> MAPPER = (rs, n) -> new BoardTask(
      rs.getString("id"),
      rs.getString("container_id"),
      rs.getString("agent_id"),
      rs.getString("title"),
      rs.getString("col"),
      rs.getString("priority"),
      splitTags(rs.getString("tags")),
      rs.getLong("created_at"));

  private static List<String> splitTags(String tags) {
    return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
  }

  private final JdbcTemplate jdbc;

  public BoardRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<BoardTask> findByContainer(String containerId) {
    return jdbc.query(
        "SELECT * FROM board_tasks WHERE container_id = ? ORDER BY created_at", MAPPER, containerId);
  }

  public List<BoardTask> findAll() {
    return jdbc.query("SELECT * FROM board_tasks ORDER BY created_at", MAPPER);
  }

  public void insert(BoardTask task) {
    jdbc.update(
        "INSERT INTO board_tasks (id, container_id, agent_id, title, col, priority, tags, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        task.id(), task.containerId(), task.agentId(), task.title(), task.column(),
        task.priority(), String.join(",", task.tags()), task.createdAt());
  }

  public int updateColumn(String id, String column) {
    return jdbc.update("UPDATE board_tasks SET col = ? WHERE id = ?", column, id);
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM board_tasks WHERE id = ?", id);
  }
}
