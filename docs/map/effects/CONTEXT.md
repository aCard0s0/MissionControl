# Change-impact index

"I am changing X — what do I open?" A catalog, not a waterfall. If this index and a card
disagree, **the card is the one to fix**.

## By what you are changing

| Changing | Open these | The thing people miss |
|---|---|---|
| an HTTP route (path or method) | [api-contract](../objects/dashboard/api-contract.md) | three places, one commit: the FE client, the controller, and a row in `docs/api.md`. `api-contract.txt` regenerates from the FE suite; CI fails if it drifts, and `ApiDocCoverageTest` fails if the route is undocumented. |
| a response shape | [api-contract](../objects/dashboard/api-contract.md), `core/store/wire-mappers.ts` | `ApiContractTest` pins JSON keys separately from the route contract. |
| a `mc.*` container label or the volume prefix | [container](../objects/docker/container.md) | four classes read it — deployer, upgrader, lifecycle, inventory. That is why `ManagedContainer` exists. |
| the MCP network or owner label | [managed-mcp-stack](../objects/mcp/managed-mcp-stack.md) | grep for the **string**, not just the constant: `agents/` once held its own copy, and the health probe would still have passed. |
| a SQLite column | [_meta/schema.md](../_meta/schema.md), the noun's card | new columns go in `config/SchemaUpgrades.java`, **not** the `CREATE TABLE` in `schema.sql`. `profile_templates.icon`/`category` are the precedent. |
| anything holding a secret | [secret](../objects/dashboard/secret.md) | two packages share `SecretsAtRest` — `mcp/McpConfigStore` **and** `agents/templates/TemplateSecrets`. Changing one is how they drifted last time. |
| an MCP read path on a timer | [mcp-server-entry](../objects/mcp/mcp-server-entry.md), [mcp-apply](../processes/mcp-apply.md) | `live(id)` forks `docker compose ps` under the host lock. A poll path must take `definition(id)`. |
| a profile write | [profile-edit](../processes/profile-edit.md) | writes go through `hermes`, never composed by us. Hermes mints ids, parses schedules, generates HMAC secrets. |
| what an upgrade copies | [upgrade-image](../processes/upgrade-image.md) | networks **and** published ports. Dropping ports silently un-exposes webhook listeners; dropping networks silently orphans MCP links. |
| a container id anywhere | [board-task](../objects/dashboard/board-task.md), [agent-mcp-link](../objects/mcp/agent-mcp-link.md), [upgrade-image](../processes/upgrade-image.md) | an upgrade mints a new id. **Three** `ContainerIdListener` impls repoint on it — `BoardRepository`, `AgentMcpLinkRepository` and `HermesProfileMcp` (in-memory, not a table). Anything new keyed by container id needs a listener too. |
| a poll period or a new poll | [hydrate-poll](../processes/hydrate-poll.md) | a poll is load on one endpoint; if that endpoint refreshes MCP runtime state it contends for the host lock. |
| anything named `provider` or `model` | [models/CONTEXT.md](../objects/models/CONTEXT.md) | four different nouns, and only one of them has a table. `/api/model-providers` and `model_providers` were that confusion; both are now `inference-endpoints` / `inference_endpoints`. |
| a webhook or listener port | [webhook-subscription](../objects/agents/webhook-subscription.md) | `published` is always `false` and must stay so. One listener port per **container**, not per profile. |
| a dialog, or anything modal | [scrim](../objects/dashboard/scrim.md) | one directive backs all fifteen backdrops. Click-outside is filtered by target, not by `stopPropagation`, and Escape is bound on the document. Nothing traps focus yet. |
| a form control's caption | [scrim](../objects/dashboard/scrim.md) — the `.field-cap` note | a `<label>` names one control. A caption over a button row or a picker is `role="group"` + `aria-labelledby`; `for` pointing at one arbitrary button would announce something false. |
| the terminal | [terminal-session](../objects/dashboard/terminal-session.md) | read `architecture.md`'s terminal section first. `pruneLayout` depends on dockview internals; the pin `~8.2.0` is load-bearing. |
| an `MC_*` variable | `config/AppProperties.java`, [../../architecture.md](../../architecture.md) | the table in `architecture.md` is the documented contract; `RuntimeConfigController` re-serves some of it to the FE at `/config.js`. |
| a log level or message | [../../architecture.md](../../architecture.md), "Logging" | conditions that hold across polls are reported **once**. The fleet view's exclusion warnings were once 93% of all log output. |

## By where you are standing

| In this folder | Also open |
|---|---|
| `docker/` | [container](../objects/docker/container.md) — the label vocabulary is shared by four classes here |
| `agents/` | [profile-edit](../processes/profile-edit.md) — the read-file/write-CLI rule governs everything |
| `mcp/` | [mcp-apply](../processes/mcp-apply.md) — the per-host lock |
| `inference/`, `models/` | [models/CONTEXT.md](../objects/models/CONTEXT.md) — check which noun you actually have |
| `core/store/` | [hydrate-poll](../processes/hydrate-poll.md) |
| `core/api/` | [api-contract](../objects/dashboard/api-contract.md) |

## What points INTO this tree from outside

This index walks outward. Nothing in the tree references these, so no card names them unless
someone goes looking — and they break silently.

| Consumer | Hardcodes | Breaks if |
|---|---|---|
| [Dockerfile](../../../Dockerfile) | `applications/mission-control-fe/`, `applications/mission-control-server/{pom.xml,src}`, `dist/mission-control/browser`, `target/mission-control-server-*.jar` | either module moves or is renamed; the Angular output path changes; the jar's artifactId changes |
| [.github/workflows/ci.yml](../../../.github/workflows/ci.yml) | both module dirs, `target/site/jacoco/jacoco.csv`, `coverage/mission-control/coverage-summary.json` | a module moves; jacoco or the vitest coverage reporter changes its output path |
| [mc](../../../mc) (488 lines) | `deploy/.env`, `deploy/compose.yml`, `deploy/compose.local.yml`, `deploy/tailscale/serve-*.json`, `docs/architecture.md` | any of those is renamed. It is a shell script — nothing type-checks it. |
| [deploy/compose.yml](../../../deploy/compose.yml) | image tag `hermes-mission-control:latest`, `./tailscale` mounted **as a directory** at `/config`, volume `mission-control-data:/data`, `MC_SECRET_KEY` from `./mc` | the image name changes; the serve file is mounted as a file instead of a directory |
| `RouteContractTest` | `applications/api-contract.txt`, then `api-contract.txt` walking up | the file moves |
| `.mission-control.env` (gitignored, generated by `./mc`) | `MC_SECRET_KEY` | it is deleted — **every stored secret becomes unopenable**, and by design is preserved rather than overwritten |
| `.vscode/mcp.json` | nothing in this tree — Angular CLI MCP only | — |

**Not enumerated:** anything outside this repo. If you have a scheduled job, another checkout, or
a bookmarked URL that hardcodes a path in here, it is invisible from inside the tree — add it to
this table when you find it.

## Hazard: `grep -r` double-hits

Claude Code puts worktrees under `.claude/worktrees/`, and a worktree is a second full copy of
this repo. None is on disk right now; one appears whenever a session opens a branch that way.

```bash
grep -rn 'thing' --exclude-dir=node_modules --exclude-dir=target \
  --exclude-dir=.angular --exclude-dir=.claude .
```
