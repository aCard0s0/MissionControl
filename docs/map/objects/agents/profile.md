---
type: object
cluster: agents
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/ProfilePaths.java
---

# Profile — the UI's "Agent"

One hermes agent identity inside a [container](../docker/container.md): its SOUL, config,
memory, skills, sessions, MCP connections, cron jobs and webhook routes. Code names
`ProfileSpec` (validated create input), `HermesProfiles` (the service), `ProfilePaths` (every
path and the CLI spelling), `ProfileInventory` (which exist), `CatalogLinkOverlay` (the MCP
catalog links every read carries).

**The UI calls this an Agent. One container holds several.** Every per-agent route is
`/api/agents/{hostId}/{containerId}/{name}` — three keys, because a profile name is only unique
inside a container.

## Why this shape

**Reads come from the files hermes owns; writes go through its CLI.** That rule is the whole
design of this cluster, and it is not a preference: hermes parses schedule expressions, mints
ids, generates HMAC secrets and owns its config schema. Reading its JSON is stable; parsing its
printed tables is not.

`ProfileSpec` exists for a defect its comment records (`agents/ProfileSpec.java:6`):
`CreateAgentRequest` was doing this job, and the template-deploy caller — which never serves an
HTTP request — had to pass `null` for a `@NotBlank hostId` to use it. Bean validation does not
run on a hand-built record, so the annotation neither held nor mattered on that path, and the
name rule it declared was duplicated further in. The name rule now lives in the canonical
constructor, so it holds for both flows.

`ProfileInventory` is a separate component because two unrelated callers need the same listing:
the agent inventory, and the webhook code working out which ports the *other* profiles already
bound (`agents/ProfileInventory.java:8`).

**Every profile is read through `CatalogLinkOverlay`** (`agents/HermesProfiles.java:131`), which
lays the dashboard's [MCP catalog links](../mcp/agent-mcp-link.md) over the entries the container
reports. It sits in the read because `readProfile` is the only place an `AgentProfileDto` is
built; as a public method on `AgentMcpCatalogService` it was fifteen call sites in six
controllers, and the two that forgot it answered with profiles whose catalog-linked MCP entries
read as custom (`agents/CatalogLinkOverlay.java:51`).

## Shape

Paths inside the container — `agents/ProfilePaths.java:17`:

| Thing | Path |
|---|---|
| hermes home | `/opt/data` |
| profiles | `/opt/data/profiles` |
| a profile | `/opt/data/profiles/<name>` |
| config | `…/<name>/config…` (`configFile`, `:46`) |
| skills | `skillsDir`, `:42` |
| state db | `stateDb`, `:50` |
| cron jobs | `…/cron/jobs.json`, `:55` |
| webhook routes | `…/webhook_subscriptions.json`, `:61` |
| e-stop | `estopFile`, `:70` |
| gateway log | `/opt/data/logs/gateways/<name>`, `:96` |

The CLI spelling lives in one place: `hermesCli(profileName, args…)` — `:81`, `:90`.
Name validity: `isValidName`, `:101`, pattern from `ProfileSpec.NAME_PATTERN`.

## Connected to

- **owns:** [Cron job](cron-job.md), [Webhook subscription](webhook-subscription.md),
  skills, sessions, SOUL, config, and its `mcp_agent_links` rows
- **owned-by:** a [container](../docker/container.md), via the `mc-hermes-<name>` data volume
- **joins:** [Provider](../models/provider-registry.md) by `provider` key;
  [Inference endpoint](../models/inference-endpoint.md) when `baseUrl` points at one;
  [MCP agent link](../mcp/agent-mcp-link.md) by `(hostId, containerId, profile)`
- **looks-like-but-is-not:** a container. The `mc.profiles` label records only what the original
  deploy *seeded*, never what exists now — read the container, not the label.

## If you change this

- **Hits:** every `agents/` service and all nine controllers in `agents/web/`; the Agents page
  and all four panels (`pages/agent-{detail,mcp,setup,skills}-panel.ts`);
  `core/store/agent-*.ts`; the 12 s agents poll (`core/store/live-sync.ts:26`).
- **Does not hit:** the container's own lifecycle. A profile is a directory in a volume —
  creating or removing one does not restart anything, though a *gateway* may need to be running
  for the profile to actually do work.

## Surfaces

| Surface | Role |
|---|---|
| `/api/agents/**` | reads / writes |
| hermes CLI, via `docker exec` | the only writer of profile state |
| profile files under `/opt/data` | read directly |
| `/ws/terminal` | `hermes -p <profile>` |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/`
- The read-file/write-CLI rule: [../../processes/profile-edit.md](../../processes/profile-edit.md)
