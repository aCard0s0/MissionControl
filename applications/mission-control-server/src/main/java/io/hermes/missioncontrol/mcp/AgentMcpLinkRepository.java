package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.ContainerIdListener;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AgentMcpLinkRepository implements ContainerIdListener {

  private static final RowMapper<AgentMcpLink> MAPPER = (rs, n) -> new AgentMcpLink(
      rs.getString("host_id"), rs.getString("container_id"), rs.getString("profile"),
      rs.getString("alias"), rs.getString("server_id"), rs.getLong("synced_revision"),
      rs.getLong("created_at"), rs.getLong("updated_at"));
  private final JdbcTemplate jdbc;

  public AgentMcpLinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<AgentMcpLink> list(String hostId, String containerId, String profile) {
    return jdbc.query("""
        SELECT * FROM mcp_agent_links
         WHERE host_id = ? AND container_id = ? AND profile = ? ORDER BY alias
        """, MAPPER, hostId, containerId, profile);
  }

  public Optional<AgentMcpLink> find(String hostId, String containerId, String profile, String alias) {
    return jdbc.query("""
        SELECT * FROM mcp_agent_links
         WHERE host_id = ? AND container_id = ? AND profile = ? AND alias = ?
        """, MAPPER, hostId, containerId, profile, alias).stream().findFirst();
  }

  public List<AgentMcpLink> findByServer(String serverId) {
    return jdbc.query("SELECT * FROM mcp_agent_links WHERE server_id = ?", MAPPER, serverId);
  }

  public void upsert(AgentMcpLink link) {
    long now = System.currentTimeMillis();
    long created = link.createdAt() > 0 ? link.createdAt() : now;
    jdbc.update("""
        INSERT INTO mcp_agent_links
          (host_id, container_id, profile, alias, server_id, synced_revision, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(host_id, container_id, profile, alias) DO UPDATE SET
          server_id = excluded.server_id,
          synced_revision = excluded.synced_revision,
          updated_at = excluded.updated_at
        """, link.hostId(), link.containerId(), link.profile(), link.alias(), link.serverId(),
        link.syncedRevision(), created, now);
  }

  public void delete(String hostId, String containerId, String profile, String alias) {
    jdbc.update("""
        DELETE FROM mcp_agent_links
         WHERE host_id = ? AND container_id = ? AND profile = ? AND alias = ?
        """, hostId, containerId, profile, alias);
  }

  /** Every link a profile holds, in one statement — the table's primary key covers it. */
  public void deleteByAgent(String hostId, String containerId, String profile) {
    jdbc.update("""
        DELETE FROM mcp_agent_links
         WHERE host_id = ? AND container_id = ? AND profile = ?
        """, hostId, containerId, profile);
  }

  public void deleteByServer(String serverId) {
    jdbc.update("DELETE FROM mcp_agent_links WHERE server_id = ?", serverId);
  }

  /** Host-scoped, matching this table's primary key. */
  @Override
  public int onContainerReplaced(String hostId, String oldContainerId, String newContainerId) {
    return jdbc.update("""
        UPDATE mcp_agent_links SET container_id = ?, updated_at = ?
         WHERE host_id = ? AND container_id = ?
        """, newContainerId, System.currentTimeMillis(), hostId, oldContainerId);
  }
}
