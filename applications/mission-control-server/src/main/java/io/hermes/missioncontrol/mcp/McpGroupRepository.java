package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the MCP groups. */
@Repository
public class McpGroupRepository {

  private static final Logger log = LoggerFactory.getLogger(McpGroupRepository.class);

  private final RowMapper<McpGroup> mapper = (rs, n) -> new McpGroup(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      ids(rs.getString("server_ids")),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public McpGroupRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** By name, the way the other two group tables read: these are headers, so their order is
   *  the page's and must not change when one is edited. */
  public List<McpGroup> findAll() {
    return jdbc.query("SELECT * FROM mcp_groups ORDER BY name COLLATE NOCASE", mapper);
  }

  public Optional<McpGroup> find(String id) {
    return jdbc.query("SELECT * FROM mcp_groups WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(McpGroup group) {
    jdbc.update(
        "INSERT INTO mcp_groups (id, name, description, server_ids, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        group.id(), group.name(), group.description(), ids(group.serverIds()),
        group.createdAt(), group.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the
   *  statement — an update must not rewrite when the group was first saved. */
  public int update(McpGroup group) {
    return jdbc.update(
        "UPDATE mcp_groups SET name = ?, description = ?, server_ids = ?, updated_at = ? "
            + "WHERE id = ?",
        group.name(), group.description(), ids(group.serverIds()), group.updatedAt(), group.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM mcp_groups WHERE id = ?", id);
  }

  /** An unparseable column degrades to empty and says so, rather than failing the read. */
  private List<String> ids(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      log.warn("dropping unparseable server_ids column on an MCP group: {}", e.getMessage());
      return List.of();
    }
  }

  private String ids(List<String> ids) {
    try {
      return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize MCP group server ids", e);
    }
  }
}
