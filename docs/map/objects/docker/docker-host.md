---
type: object
cluster: docker
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/hosts/
---

# Docker host

A Docker daemon this dashboard can reach: the mounted local socket, or a remote `tcp://`.
Code names `DockerHostDto` (wire) / `DockerHostRef` (id + url, passed around internally).
`docker_hosts` table. Served at **`/api/hosts`**.

## Why this shape

`DockerHostRef(id, url)` exists so the rest of the backend never resolves a host itself —
the edge resolves it once and both halves travel in one value: the endpoint to reach the daemon
and the id the dashboard's own rows are keyed by (`docker/ContainerUpdateService.java:37`). That
is why packages like `docker/` do not depend on `hosts/`.

The **local row always exists**, seeded from `MC_DOCKER_SOCKET` at startup
(`hosts/HostService.java:49`, id `dh-local` at `:29`), and **cannot be removed**
(`hosts/HostService.java:129`) — removing the socket the container is mounted on would leave a
dashboard with nothing to manage and no way back.

## Shape

- Stored: `id, name, url, kind ('local'|'remote'), created_at` — `schema.sql:4`. `url` is UNIQUE.
- Derived per probe: `status, engine, apiVersion, latencyMs, note` — `hosts/DockerHostDto.java:4`.

## Connected to

- **owns:** nothing in SQLite by FK, but is the scope for almost everything: containers, images,
  [MCP stacks](../mcp/managed-mcp-stack.md), `mcp_agent_links.host_id`,
  `mcp_retained_resources.host_id`
- **owned-by:** the operator
- **joins:** every `{hostId}` path variable in the API
- **looks-like-but-is-not:** [Inference endpoint](../models/inference-endpoint.md) — also a URL
  the operator registers, also probed, entirely unrelated table and purpose.

## If you change this

- **Hits:** every `/api/{...}/{hostId}/...` route (nearly all of them); `DockerClients`
  (connection pooling per host); the per-host Compose lock
  (`mcp/ComposeStackManager.java:42`) and generated stack files under `MC_MCP_STACK_DIR`;
  the host picker in every FE page; `core/store/host-store.ts`.
- **Does not hit:** container *contents*. A host is a connection, not state — nothing about a
  Hermes profile lives on the host row.

## Surfaces

| Surface | Role |
|---|---|
| `/api/hosts` | reads / writes |
| every other API route | reads, as `{hostId}` |
| FE `core/store/host-store.ts` | reads / writes |

## Security

Remote hosts are plain `tcp://`; TLS daemon sockets are **not implemented**. The mounted local
socket is root-equivalent on the host. See the security notes in
[../../../architecture.md](../../../architecture.md).

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/hosts/`
