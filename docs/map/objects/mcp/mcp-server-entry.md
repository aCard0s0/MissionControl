---
type: object
cluster: mcp
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/McpRegistryService.java
---

# MCP server entry

A catalog row for one MCP server. Three kinds: **`managed`** (a container we run via Compose),
**`external`** (an HTTP/SSE endpoint someone else runs), **`stdio`** (a reusable command
definition). `McpServerDto` / `McpServerRepository`, `mcp_servers` table, served at
**`/api/mcp-servers`**.

The page is **global**, not scoped to the active container.

## Why this shape

**Two read modes, and picking the wrong one is a real cost.** `definition(id)` answers from the
SQLite row alone; `live(id)` first re-reads the managed service's runtime state, which forks
`docker compose ps` plus a container listing, and persists what it finds. (Reads do not take
the host's compose lock — only mutations do — so the cost is the forks, not a wait behind an
image pull.) The split exists because there used to be one method and it was the refreshing
one: the Agent read path calls it per linked entry per profile on a 12-second poll, and every one
of those was forking Compose to reach a `revision` column
(`mcp/McpRegistryService.java:97`). Only a caller about to act on whether the server is *up*
takes `live` (`:113`).

`list()` is a third mode: rows refreshed **together**, because per row it costs one `compose ps`
plus a full container listing, and per host only one of each (`:79`).

**Desired vs applied is two columns, not a boolean.** `revision` is bumped by an edit,
`applied_revision` by a successful Compose apply, and `pendingChanges` is just
`revision > appliedRevision` (`mcp/McpServerDtoMapper.java:36`).

## The repository link is documentation

`repo_url` is a top-level column beside `description`, deliberately not inside `config_json`:
that column is *how the thing runs*, and nothing about this ever reaches a profile's MCP config.
Nothing fetches it either — unlike a [skill](../dashboard/skill-library.md)'s repository, which
`UpstreamCheck` parses to ask GitHub about releases. This one is only ever opened by a person,
from the `repo ↗` button on the roster.

The scheme is validated to `http`/`https` at the boundary
(`mcp/McpRequestValidator.java:314`). The value is rendered as an `href`, and a store that will
hand it to any client should not rely on one client's framework to sanitize it.

It arrived on a table that had already shipped, so it is a `SchemaUpgrades` column
(`config/SchemaUpgrades.java:52`) rather than a bare `schema.sql` addition — and an entry
written before it reads null.

Editing it bumps the revision like any other edit, so a managed entry with linked agents shows
**apply required** afterwards. Same as `description`; not a rule this field introduces.

## Shape

`mcp_servers` — `schema.sql:59`. `name` is `COLLATE NOCASE UNIQUE`; `service_key` and `seed_key`
are UNIQUE; `config_json` carries the whole allowlisted container config.

Three state columns, three enums: `desired_state`, `runtime_state`
(`RUNNING|STOPPED|MISSING|ERROR|UNAVAILABLE|UNKNOWN` — `mcp/McpRuntimeState.java:16`),
`operation_state` (`PROVISIONING|RECONCILING|STARTING|STOPPING|APPLYING|DELETING|IDLE|ERROR` —
`mcp/McpOperationState.java:22`).

**Config is allowlisted** (`mcp/McpRequestValidator.java:106`): image, list-form command,
environment, ports, support services, named volumes. Host binds, host networking, privileged
mode, devices, capabilities and Docker-socket mounts are **rejected**.

Secret environment and header values use the same encrypted-at-rest key as
[profile templates](../agents/profile-template.md) and are passed to Compose **at execution
time**, never written into the generated YAML. Their runtime values are still visible to anyone
with daemon access — as with all container environment variables.

## Connected to

- **named-by:** any number of [MCP groups](mcp-group.md), by id in a JSON list — so unlike
  `mcp_agent_links.server_id` there is no FK and no cascade. Deleting an entry leaves it in every
  group that named it, and a group deploy reports it as skipped.
- **owns:** its rendered Compose service in the [managed stack](managed-mcp-stack.md); its
  `mcp_retained_resources` rows; its support services and volumes
- **owned-by:** a [Docker host](../docker/docker-host.md) when `managed` (`host_id`); nothing when
  `external` or `stdio`
- **joins:** [MCP agent link](agent-mcp-link.md) by `server_id`
- **looks-like-but-is-not:** an [Inference endpoint](../models/inference-endpoint.md) — also a URL
  the operator registers and we probe. Unrelated.

## If you change this

- **Hits:** `McpRegistryService`, `McpServerDtoMapper`, `McpRequestValidator`,
  `ComposeStackRenderer`, `McpStartupReconciler`, `McpCatalogSeeder`; `schema.sql` +
  `SchemaUpgrades`; `pages/mcp-servers.ts` + `mcp-server-editor.ts`;
  `core/mcp/catalog-{draft,rules}.ts` (the FE mirrors the validation rules);
  `core/store/mcp-catalog-store.ts`.
- **Does not hit:** the pre-existing user project named **`mcp`**. Mission Control owns only
  `mission-control-mcp` and never adopts or changes `mcp`. Also does not hit an Agent's config
  until an [MCP agent link](agent-mcp-link.md) is made.

## Surfaces

| Surface | Role |
|---|---|
| `/api/mcp-servers` | reads / writes |
| Docker Compose, per host | written under the host lock |
| `MC_MCP_STACK_DIR` (`/data/mcp-stacks`) | generated files — SQLite is the truth, these regenerate |
| `MC_SECRET_KEY` | encrypts secret config values |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/`
- `docs/architecture.md`, "MCP server catalog and Compose projects"
