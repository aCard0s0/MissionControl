# Mission Control — Backend API

Base: same origin as the dashboard (combined image) or `MC_API_BASE_URL`.
All responses are JSON. Errors: `{ "error": "<message>" }` with 400 / 404 / 409 / 502 (docker) / 503.

## Meta

| Method & path | Returns |
|---|---|
| `GET /health` | `{ status, version, dockerConnected }` |
| `GET /config.js` | frontend runtime config as JS (from `MC_*` env, `no-store`) |

## Docker hosts — registry in SQLite, status probed live (10s cache)

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/hosts` | — | each: `{ id, name, url, kind, status, engine, apiVersion, latencyMs, note }` |
| `POST /api/hosts` | `{ name, url }` | url must be `tcp://host:port`; duplicate urls rejected |
| `POST /api/hosts/{id}/check` | — | forces a fresh probe |
| `DELETE /api/hosts/{id}` | — | local socket host is not removable (400) |

## Containers — read through to the daemon, never cached

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/containers` | `?hostId=`, `?all=true` | filtered by `MC_CONTAINER_FILTER` unless `all`; skips unreachable hosts |
| `GET /api/containers/{hostId}/{id}/stats` | — | one-shot sample; `rxBytes`/`txBytes` are cumulative — clients compute rates |
| `GET /api/containers/{hostId}/{id}/logs` | `?tail=100` (max 500) | container-scoped `{ ts, level, source, msg }`; multiline frames are split, empty records dropped, and explicit severity preserved |
| `POST /api/containers` | `{ hostId, name, version?, profiles? }` | creates + starts `MC_HERMES_IMAGE:version`, waits for default-profile initialization, then creates each requested named profile. Any failure rolls back the container and managed volume; an existing same-name volume returns 409. |
| `POST /api/containers/{hostId}/{id}/start` | — | |
| `POST /api/containers/{hostId}/{id}/stop` | — | 10s graceful timeout |
| `POST /api/containers/{hostId}/{id}/update` | `{ version }` | recreates the container on another tag, reusing its data volume, and returns the **new** `{ id }`. Pulls first, then stops, parks the old container aside, creates the replacement under the same name/labels/networks, and only removes the parked original once readiness passes — a failure restores it. Never re-seeds profiles and never touches the volume. 400 if the container is not Mission Control-managed or runs another image; 409 if it already runs that tag. Held open through readiness, so it can take minutes on a cold pull. |
| `DELETE /api/containers/{hostId}/{id}` | — | force removes the container and its recorded Mission Control-managed volume; unowned/external mounts are preserved |

Container DTO: `{ id, shortId, name, hostId, status, image, version, startedAt, sizeRootFsGb, profiles }`
with `status ∈ running | stopped | unhealthy | unknown`.

## MCP server catalog — SQLite definitions + managed Compose lifecycle

Managed entries belong to one immutable Docker host and are rendered into that
daemon's `mission-control-mcp` Compose project. External HTTP/SSE and stdio
entries are registry-only and therefore have no container lifecycle or logs.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/mcp-servers` | — | Redacted catalog records plus desired/runtime/operation state and revisions |
| `POST /api/mcp-servers` | structured server definition | Managed creates return 202 and asynchronously pull/create a stopped service; external/stdio return 201 |
| `PUT /api/mcp-servers/{id}` | complete structured definition | Kind and managed `hostId` are immutable; running deployment changes remain pending until Apply |
| `DELETE /api/mcp-servers/{id}` | — | Disables/unlinks Agent copies first; managed deletion returns 202 and preserves named volumes |
| `POST …/{id}/start` | — | 202; applies pending config and starts the main/support services |
| `POST …/{id}/stop` | — | 202; stops the main/support services without deleting them |
| `POST …/{id}/apply` | — | 202; recreates a running service or refreshes its stopped container |
| `POST …/{id}/check` | — | Bounded, no-redirect HTTP reachability check for external entries only |
| `GET …/{id}/logs` | `?tail=200` (max 500 per container) | Merged Docker tail for a managed server and its private support services |
| `GET /api/mcp-servers/retained-resources` | — | Named volumes preserved by catalog deletion |
| `DELETE …/retained-resources/{id}` | — | Permanently purges one retained Mission Control-owned volume |

Catalog input uses `kind ∈ managed | external | stdio`. Managed fields include
`hostId`, `image`, optional `platform`, list-form `entrypoint`/`command`,
`internalPort`, optional `publishedPort`, `path`, optional `crossHostUrl`,
environment/headers, named volumes, healthcheck, and private support services.
External entries use `transport + url + headers`; stdio entries use
`stdioCommand + args + environment`. Configuration values have
`{ key, value?, secret, clear? }`; secret values are never returned, only
`set/recoverable` flags. Raw Compose YAML, bind mounts, Docker socket mounts,
host networking, privileged mode, devices, and capabilities are not accepted.

## Agents — Hermes profiles read through `docker exec`

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/agents` | `?hostId=&containerId=` | one DTO per profile (`/opt/data` = `default`, plus `/opt/data/profiles/*`) |
| `POST /api/agents` | `{ hostId, containerId, name, provider, model, apiKey?, cloneFrom?, auxiliary? }` | `hermes profile create`, then sets model + auxiliary tasks + provider API key |
| `DELETE /api/agents/{hostId}/{containerId}/{name}` | — | `hermes profile delete --yes` |
| `GET  …/{name}/logs` | `?tail=100` (max 500) | profile-scoped supervised gateway log from `/opt/data/logs/gateways/{name}`; returns `{ ts, level, source, msg }` |
| `PUT  …/{name}/soul` | `{ soul }` | writes `SOUL.md` |
| `PUT  …/{name}/skills/{skillName}` | `{ enabled }` | toggles `skills.platform_disabled.cli` in `config.yaml` |
| `POST …/{name}/skills` | `{ name }` | `hermes skills install --force` |
| `DELETE …/{name}/skills/{skillName}` | — | removes the skill directory (the CLI uninstall is interactive-only) |
| `POST …/{name}/mcp` | `{ name, transport, url?, command?, args?, enabled?, headers?, environment? }` | adds/upserts one `mcp_servers` entry; persists explicit SSE transport and per-server stdio env |
| `PUT …/{name}/mcp/{serverName}` | same as POST | atomically updates or renames the saved entry; `headers: {}` explicitly clears headers |
| `PUT …/{name}/mcp/{serverName}/enabled` | `{ enabled }` | non-destructive disconnect/reconnect; preserves all connection configuration |
| `DELETE …/{name}/mcp/{serverName}` | — | permanently forgets the saved entry |
| `POST …/{name}/mcp/{serverName}/test` | — | runs Hermes' MCP handshake; returns `{ status, tools, latencyMs, error, checkedAt }` |
| `POST …/{name}/mcp/catalog` | `{ serverId, alias }` | materializes a catalog definition; same-host managed entries attach the Agent container to the MCP network |
| `POST …/{name}/mcp/{serverName}/sync` | — | manually applies the current catalog revision while preserving enabled state |
| `DELETE …/{name}/mcp/{serverName}/link` | — | converts a linked entry into an Agent-local custom definition without deleting config |
| `GET  …/{name}/integrations` | — | parsed from `gateway_state.json` |
| `PUT  …/{name}/config` | `{ configYaml }` | full config.yaml replace — validated as a YAML mapping (400 otherwise); platform tokens (slack, whatsapp, honcho, …) and `model.default` / `model.base_url` overrides live here |

Configured MCP servers report `unknown` until tested, then `connected` or
`error`; disabled entries report `disabled`. Every server DTO also carries an
explicit `enabled` boolean so clients do not need to derive persistence state
from probe status. Catalog-linked entries also expose catalog/synced revisions
and `updateAvailable`. Probe results remain cached only while the server
definition is unchanged.

Create (`POST /api/agents`) accepts optional `baseUrl`; when set, the profile's
`model.default` + `model.base_url` are written directly (ollama / any
OpenAI-compatible endpoint) and no provider API key is required.

Create also pins hermes' auxiliary side tasks — compression, summarization,
memory flush and the rest — to the profile's own provider/model, and fails the
create (rolling the profile back) if `config.yaml` ends up without a model.
`hermes profile create` never seeds `config.yaml`, and every auxiliary slot ships
as `provider: auto`, which resolves through the main model before OpenRouter /
Nous / a custom endpoint — so a profile with no model config logs
`no provider available … compression, summarization, and memory flush will not
work` on its first long session.

Optional `auxiliary: { provider?, model, baseUrl?, apiKey? }` runs those side
tasks on a different model — useful when the main model is expensive, since side
tasks are frequent, short and mechanical. `model` is the only required field; a
blank `provider` means "same provider as the main model" and inherits its
endpoint. `vision` is deliberately left on `auto`: its chain skips a main model
known to be text-only and falls back to OpenRouter/Nous, so pinning it would aim
image payloads at a model that may reject them.

## Model catalogs — what the create-agent form offers

| Method & path | Body | Notes |
|---|---|---|
| `GET /api/models/{provider}` | — | curated list from `MC_MODELS_ANTHROPIC` / `MC_MODELS_OPENAI` (sensible defaults baked in) |
| `POST /api/models/{provider}` | `{ apiKey }` | live fetch from the provider's `/v1/models` (truth source); falls back to the config list on any failure |

## Model providers — ollama registry in SQLite

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/model-providers` | — | status probed via `GET {url}/api/version` (10s cache) |
| `POST /api/model-providers` | `{ name, url }` | http(s) urls only; duplicates rejected |
| `POST /api/model-providers/{id}/check` | — | fresh probe |
| `DELETE /api/model-providers/{id}` | — | |
| `GET /api/model-providers/{id}/models` | — | proxied `GET {url}/api/tags` |
| `POST /api/model-providers/{id}/models/pull` | `{ name }` | 202; async pull, progress via `GET …/pulls` |
| `POST /api/model-providers/{id}/models/delete` | `{ name }` | |

## Images

| Method & path | Params | Notes |
|---|---|---|
| `GET /api/images/tags` | `?hostId=`, `?remote=true` | tags of `MC_HERMES_IMAGE` from the host's image store merged with the registry's published tags, newest first |

Returns `{ repository, tags, entries, newest, registryStatus, registryDetail, registryCheckedAt }`.
`tags` is every known tag as a flat list; `entries` is the same order as
`{ tag, pulled, remote, lastUpdated, sizeBytes, digest }`, so callers can tell a
locally cached tag from one that still needs a pull. `newest` is the highest
pinned release — floating tags (`latest`, `main`, `edge`, `nightly`, `dev`) are
excluded, since calling a moving pointer "newest" would mark every pinned
container permanently out of date.

Remote lookup is Docker Hub only and is cached per repository for 10 minutes
(failures for 1 minute), so callers may poll this freely. It never fails the
request: `registryStatus` reports `ok | cached | unavailable | unsupported |
disabled` and the response falls back to local tags. Repositories on another
registry report `unsupported`; `MC_REGISTRY_TAGS=false` reports `disabled`.
Ordering handles calendar tags of any depth, so `v2026.7.7.2` ranks between
`v2026.7.20` and `v2026.7.7`.

## Web terminal — WebSocket bridge to `docker exec`

| Endpoint | Params | Notes |
|---|---|---|
| `WS /ws/terminal` | `?hostId=&containerId=` | spawns `bash -i` (or `sh -i`) with a tty inside the container |

Protocol: binary frames carry raw terminal bytes both ways; text frames carry
client control messages — `{ "type": "resize", "cols": n, "rows": n }`.
Handshake enforces same-origin (or the dev origins `localhost:4200/4300`).
The exec ends when the socket closes (stdin EOF exits the shell).

## Ops board — dashboard-owned state in SQLite

| Method & path | Body / params |
|---|---|
| `GET /api/board/tasks` | `?containerId=` |
| `POST /api/board/tasks` | `{ containerId, agentId?, title, column?, priority?, tags? }` |
| `PATCH /api/board/tasks/{id}` | `{ column }` — `queued | running | review | done` |
| `DELETE /api/board/tasks/{id}` | — |

## Roadmap (not implemented)

- Hermes cron jobs and webhooks introspection; would light up the Calendar and
  Webhooks pages in live mode.
- SSE/WebSocket streaming for logs and stats (currently polled).
- TLS for remote daemons; authentication for the dashboard itself.
