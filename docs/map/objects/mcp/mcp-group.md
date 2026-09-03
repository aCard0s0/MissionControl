---
type: object
cluster: mcp
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/McpGroupController.java
---

# MCP group

A named set of [catalog entries](mcp-server-entry.md), deployable onto an
[agent](../agents/profile.md) in one action. `McpGroup`, `mcp_groups` table, served at
**`/api/mcp-groups`**.

The third group in this application and the only one whose noun *does* something: a
[skill group](../dashboard/skill-group.md) and a [prompt group](../dashboard/prompt-group.md)
file a library, this one also has a deploy.

## Why this shape

**Because the set an agent needs is usually several servers at once**, and connecting them one
at a time through the agent's own MCP tab is the thing this exists to stop.

### It stores no agents, and that is the whole design

Deploying a group connects each of its servers to one agent — and every one of those
connections is already an [MCP agent link](agent-mcp-link.md), the dashboard's existing record
of "this profile is connected to this catalog entry, under this alias". So which agents a group
reaches is **derived** from those links on every read
(`mcp/McpGroupController.java:184`), never stored.

A stored group-to-agent association would be a second source of truth. Disconnect one server on
the agent's own MCP tab and the association would still claim the group was connected; the
derived count drops to `1/2` and says so. This is the same instinct as the link's own
`synced_revision` — drift is reported, not hidden — and the same rule as
[CLAUDE.md](../../../../CLAUDE.md)'s: never cache what something else owns.

The consequence worth stating, because it is a deliberate loss: a group deployed and then fully
disconnected reads as *on no agent*, indistinguishable from one never deployed. There is no
record of the intent, only of the state. That is the honest answer to "where is this group", and
the reason no reconciliation step exists here.

### Many-to-many falls out with nothing storing it

- the same group deploys onto **as many agents as you like** — each gets its own links
- an **agent's links may come from several groups**, and from servers connected individually
- a **server in two groups counts toward both**, because each group asks the links the same
  question independently

None of those needed a table, and none of them can disagree with the links.

## The deploy is not atomic, deliberately

Several independent writes to an agent someone else owns. Same rule as
[guide](../dashboard/guide.md) — *surface the error, do not roll back* — and the same
`DeployedPart` per part. Undoing half of it would mean disconnecting servers that may have been
on that agent **before the group ever ran**.

**An alias the agent already has is `skipped`, not `failed`** (`mcp/McpGroupController.java:149`).
Topping up an agent that holds part of the group is the *ordinary* use of this button — calling
that a failure would paint the normal case red. The operator's question is "is this server on the
agent?", and the answer is yes either way. The reason reads `already connected`.

**`AgentMcpCatalogService.connectIfAbsent` is what says so** (`agents/AgentMcpCatalogService.java:79`).
It reports the case instead of throwing, and `connect` — the single-server route, where an alias
already there really is a 409 — is the wrapper that turns it back into one. This handler and a
[guide](../dashboard/guide.md)'s deploy each used to recognise it by matching `getMessage()`
against a private copy of that conflict's prose; one of them classified it the other way, so the
same event was `skipped` here and `failed` there.

A server gone from the catalog is `skipped` with `no longer in the catalog`. Anything else is
`failed` with its message.

No ordering is load-bearing, unlike a guide's deploy: the servers are independent and nothing is
written afterwards that names what landed.

## Two things called a group on that page

The MCP Servers page arranges the roster by kind and host — *Managed stack*, *External
endpoints*, *Reusable stdio definitions*. That arrangement used to be called `serverGroups` in
the code while meaning nothing an operator creates. It is now `mcpServerSections`
(`pages/mcp-server-sections.ts`), so **group** on that page means only this noun.

The groups are a **tab**, not a second band under the roster: the roster answers "what is
registered and is it up", the groups answer "what does an agent get", and stacking a user
grouping under a kind grouping made neither readable. The tab is local state rather than a
`?tab=` link, unlike the Skills page — nothing links here yet. Retained data stays with the
roster, which is what it is about.

## Shape

- `mcp_groups` — `schema.sql:145`; `name` is `COLLATE NOCASE UNIQUE` (`:150`); `server_ids` is
  JSON in TEXT (`:152`)
- `McpGroup` — `mcp/McpGroup.java`
- `McpGroupDto` / `McpGroupAgentDto` — `mcp/McpGroupDto.java`, the read shape with coverage
- `McpGroupRepository` — `mcp/McpGroupRepository.java:40` reads by name, like the other two
  group tables
- `McpGroupController` — `/api/mcp-groups`, five routes: four for the set, one deploy
- `AgentMcpLinkRepository.findByServer` — where the coverage comes from
- `pages/mcp-servers.ts:99` resolves the ids for display and marks what the catalog has lost

## Connected to

- **owns:** nothing. Not the servers, not the links a deploy wrote.
- **owned-by:** the dashboard
- **joins:** [MCP server entries](mcp-server-entry.md) by id — a JSON list, so unlike
  `mcp_agent_links.server_id` there is nothing for a `REFERENCES … ON DELETE CASCADE` to attach
  to. An entry deleted from the catalog stays in the group's list and is reported as skipped by a
  deploy rather than silently dropped.
- **reaches:** [profiles](../agents/profile.md), only through
  `AgentMcpCatalogService.connect` — it writes no profile itself and knows nothing about how a
  server is materialized.
- **derived-from:** [MCP agent links](agent-mcp-link.md). One direction only: the links know
  nothing about groups.
- **looks-like-but-is-not:** a [skill group](../dashboard/skill-group.md) or a
  [prompt group](../dashboard/prompt-group.md). Those file a library and have no deploy. This one
  is the reason the three are not one table.
- **looks-like-but-is-not:** a [guide](../dashboard/guide.md), which also connects a set of MCP
  servers to an agent — but as one part of deploying skills plus prose the agent reads. A guide
  is a document that happens to name servers; this is a set of servers and nothing else.
- **looks-like-but-is-not:** the roster's own kind sections. See *Two things called a group*.

## If you change this

- **Hits:** `McpGroupRepository`, `McpGroupController`, `core/store/mcp-group-store.ts`,
  `pages/mcp-servers.ts` and its template.
- **The coverage is a read of `mcp_agent_links`**, so anything changing that table's shape or
  keys changes what a group can report. A deploy is followed by a store refresh for the same
  reason — the counts come off the links the deploy just wrote.
- **Does not hit:** the catalog entries, the managed compose stack, or any profile's config
  beyond what `connect` already does. Deleting a group disconnects nothing.

## Surfaces

| Surface | Role |
|---|---|
| `/api/mcp-groups` | reads / writes the sets |
| `POST /api/mcp-groups/{id}/deploy` | the one call that reaches a container |
| SQLite `mcp_groups` | the set, stored |
| SQLite `mcp_agent_links` | where the agent coverage is read from, and never written by this |
| `AgentMcpCatalogService.connect` | how each server is actually linked |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The link it derives from: [MCP agent link](agent-mcp-link.md)
- The other two groups: [skill group](../dashboard/skill-group.md),
  [prompt group](../dashboard/prompt-group.md)
