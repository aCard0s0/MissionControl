---
type: object
cluster: mcp
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/AgentMcpLink.java
---

# MCP agent link

The row saying "this [profile](../agents/profile.md) is connected to this
[catalog entry](mcp-server-entry.md), under this alias". `AgentMcpLink` /
`AgentMcpLinkRepository`, `mcp_agent_links` table. Reached through
**`/api/agents/{hostId}/{containerId}/{name}/mcp`**.

## Why this shape

**A catalog link augments, but never replaces, the Agent's own materialized MCP configuration**
— the schema says so where it is defined (`schema.sql:105`). Disabling an Agent entry therefore
*leaves the row*, with its connection details available for a later reconnect. That is why
deletion of a link is a deliberate act and not a side effect of disabling one.

**The link stores a revision, which is how drift is visible.** `synced_revision` records the
catalog entry's revision at the moment the profile was written, and the read path compares it to
the entry's current one (`agents/AgentMcpCatalogService.java:189`). If the catalog entry moved on,
the profile is out of date and the page can say so.

The same read path is where a **deleted catalog entry** self-heals: a `NoSuchElementException`
from `definition()` deletes the orphan link rather than failing the listing
(`agents/AgentMcpCatalogService.java:191`). The FK is `ON DELETE CASCADE`, so this only covers
rows the cascade did not reach.

This is also the path that made `definition` vs `live` matter: it runs **per linked entry, per
profile, on a 12-second poll**, and needs one column.

## Shape

`mcp_agent_links` — `schema.sql:105`.

- PRIMARY KEY `(host_id, container_id, profile, alias)` — the alias is the profile's own name for
  the server, so the same catalog entry can be linked twice under different aliases.
- `FOREIGN KEY (server_id) REFERENCES mcp_servers(id) ON DELETE CASCADE`
- Index on `server_id` — `schema.sql:119`

## Connected to

- **owns:** nothing
- **owned-by:** an [MCP server entry](mcp-server-entry.md) by FK; a
  [profile](../agents/profile.md) by the first three key columns
- **joins:** [Container](../docker/container.md) by `container_id` — **repointed when an upgrade
  mints a new id**, in the same transaction as `board_tasks`, via `ContainerIdListener`
  (`docker/ContainerUpdateService.java:53`)
- **looks-like-but-is-not:** the profile's own MCP config inside the container. That is hermes'
  file; this row only records what we wrote into it and at which revision.

## If you change this

- **Hits:** `AgentMcpCatalogService`, `AgentMcpController`, `McpServerDeletion`,
  `ContainerUpdateService.remap`, `pages/agent-mcp-panel.ts`, `core/store/agent-mcp-store.ts`;
  and the same-host network attachment — connecting a link attaches the Hermes container to
  [`mission-control-mcp-net`](managed-mcp-stack.md).
- **Does not hit:** cross-host connections. Those require an **explicit agent-reachable URL**
  (`crossHostUrl`); the shared network only spans one daemon.

## Surfaces

| Surface | Role |
|---|---|
| `…/{name}/mcp` | reads / writes |
| the profile's MCP config, in-container | written via hermes |
| SQLite `mcp_agent_links` | our record of it |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/AgentMcpCatalogService.java`
