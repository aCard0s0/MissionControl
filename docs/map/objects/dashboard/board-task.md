---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/board/
---

# Board task

A card on the ops kanban, scoped to a [container](../docker/container.md). `BoardTask`,
`board_tasks` table, served at **`/api/board/tasks`**.

## Why this shape

Dashboard-owned: an operator's note about work, with no home inside hermes. Kept deliberately
small — four columns, three priorities, both enforced by CHECK constraints rather than
application code (`schema.sql:17`).

`container_id` is a plain column, not an FK to anything (containers are not in SQLite), which is
why an upgrade has to repoint it explicitly.

## Shape

`board_tasks` — `schema.sql:12`.

- `col` ∈ `queued | running | review | done` — CHECK
- `priority` ∈ `low | med | high` — CHECK
- `tags` is a JSON array in a TEXT column
- `agent_id` is nullable — a task can belong to a container without naming a profile

## Connected to

- **owns:** nothing
- **owned-by:** a container, by id only
- **joins:** [Container](../docker/container.md) by `container_id` — **repointed in one
  transaction when an upgrade mints a new id**, alongside `mcp_agent_links`
  (`docker/ContainerUpdateService.java:53`). A failed remap is retried once after 200 ms and then
  logged; it does **not** undo a healthy update, because the container is already on the new
  image and undoing that to preserve a task link would trade a working Agent for a bookkeeping
  detail. All tables move in one transaction, so a partial remap cannot split rows across ids
  (`:77`).
- **looks-like-but-is-not:** a [cron job](../agents/cron-job.md). Nothing runs a board task.

## If you change this

- **Hits:** `BoardRepository`, `BoardController`, `ContainerUpdateService.remap` (via the
  `ContainerIdListener` interface), `pages/board.ts`, `core/store/board-store.ts`.
- **Does not hit:** anything an Agent does. Nothing inside a container reads the board.

## Surfaces

| Surface | Role |
|---|---|
| `/api/board/tasks` | reads / writes |
| SQLite `board_tasks` | stored |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/board/`
