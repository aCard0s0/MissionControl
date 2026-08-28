---
type: process
status: verified
verified: main @ 640da14 · 2026-08-28
consumes: [container, image]
produces: [container]
---

# upgrade-image

Move a running Agent onto a newer image tag. Docker cannot swap the image of a running
container, so this is a **replacement** — done without touching the Agent's data.

## Input → Movement → Output

A container id and a target tag go in. The tag is pulled, the original is renamed aside, a
replacement is created under the original name with the original's binds, networks, ports,
labels, command and restart policy, and the volume is reattached. Out comes a new container id —
and the dashboard's own rows are repointed at it.

## Why this shape

Every step exists to make a failure cost nothing:

- **Pull first, before anything stops.** A bad tag or an unreachable registry costs no downtime.
- **Rename aside, do not remove.** The original is *parked*, and only once the replacement passes
  readiness is it removed (`docker/ContainerUpgrader.java:199`). Any failure restores it
  (`:264` — best effort, and it never masks the original cause).
- **Reattach the volume, never recreate it.** Profiles, souls, memory, skills, sessions and
  credentials carry over. No bootstrap one-shots run: the profiles already exist, and the
  `mc.profiles` label records only what the *original deploy* seeded.
- **A stopped container stays stopped** (`:184`) — an operator parked it on purpose.

Two parts of the copied set are load-bearing and easy to lose:

- **User-defined networks**, notably `mission-control-mcp-net`, which is attached *after* deploy
  and would otherwise be silently lost (`reattachableNetworks`, `:107`).
- **Published ports**, `PublishAllPorts` included — the only way a
  [webhook listener](../objects/agents/webhook-subscription.md) is reachable, and a mapping
  cannot be re-applied to a running container. Without this, a tag bump silently un-exposes the
  listener with nothing on any page to say hooks had stopped arriving.

## Steps

1. `inspectManaged` — refuse anything this dashboard did not deploy (`:55`).
2. Pull the target tag.
3. `ParkedContainerName.of(name, id)` → `-mc-upgrade-<hex>`; rename (`:167`, `:175`).
4. Stop before replace (`:214`), then `createReplacement` under the original name (`:233`).
5. Readiness. On failure → `rollback` (`:271`).
6. Remove the parked original; a transient failure here is a **warning, not an error** (`:204`).
7. **Repoint** `board_tasks` and `mcp_agent_links` at the new id, in one transaction
   (`docker/ContainerUpdateService.java:53`). A failed remap is logged and does **not** undo a
   healthy update — the container is already on the new image, and undoing that to preserve a
   task link would trade a working Agent for a bookkeeping detail.

## If you change this

- **Hits:** `ManagedContainerSpec` (what gets copied), `ParkedContainerName`,
  `ContainerInventory` (parked leftovers are hidden from the fleet, reachable via `?all=true`);
  **all three** `ContainerIdListener` implementations — `board/BoardRepository`,
  `mcp/AgentMcpLinkRepository` and `agents/HermesProfileMcp` (an in-memory tool-count cache, not
  a table, and the one people forget); `core/store/container-lifecycle.ts`.
- **Does not hit:** host-config customizations applied out of band (`docker update`, CPU or
  memory limits). Those are **outside** the copied set and are not preserved. Port mappings are
  inside it, for the reason above.

## Surfaces

| Surface | Role |
|---|---|
| `PUT /api/containers/{hostId}/{id}` | entry |
| the registry | pulled first |
| SQLite `board_tasks`, `mcp_agent_links` | repointed |

## See

- Objects: [container](../objects/docker/container.md), [image](../objects/docker/image.md),
  [board task](../objects/dashboard/board-task.md), [MCP agent link](../objects/mcp/agent-mcp-link.md)
- Source: `.../docker/ContainerUpgrader.java`, `.../docker/ContainerUpdateService.java`
- `docs/architecture.md`, "Container image updates"
