# Mission Control — Backend API

Base: same origin as the dashboard (combined image) or `MC_API_BASE_URL`.
All responses are JSON. Errors: `{ "error": "<message>" }` with 400 / 404 / 502 (docker) / 503.

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
| `GET /api/containers/{hostId}/{id}/logs` | `?tail=100` (max 500) | `{ ts, level, source, msg }`, level inferred from markers/stream |
| `POST /api/containers` | `{ hostId, name, version?, profiles? }` | creates + starts `MC_HERMES_IMAGE:version` (pulls if missing), runs `gateway run`, mounts a per-container volume at `/opt/data`, sets restart policy `unless-stopped`; profiles stored as `mc.profiles` label. Requires `/opt/data/.env` (run `nousresearch/hermes-agent setup` once). |
| `POST /api/containers/{hostId}/{id}/start` | — | |
| `POST /api/containers/{hostId}/{id}/stop` | — | 10s graceful timeout |
| `DELETE /api/containers/{hostId}/{id}` | — | force remove |

Container DTO: `{ id, shortId, name, hostId, status, image, version, startedAt, sizeRootFsGb, profiles }`
with `status ∈ running | stopped | unhealthy | unknown`.

## Agents — Hermes profiles read through `docker exec`

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/agents` | `?hostId=&containerId=` | one DTO per profile (`/opt/data` = `default`, plus `/opt/data/profiles/*`) |
| `POST /api/agents` | `{ hostId, containerId, name, provider, model, apiKey?, cloneFrom? }` | `hermes profile create`, then sets model + provider API key |
| `DELETE /api/agents/{hostId}/{containerId}/{name}` | — | `hermes profile delete --yes` |
| `PUT  …/{name}/soul` | `{ soul }` | writes `SOUL.md` |
| `PUT  …/{name}/skills/{skillName}` | `{ enabled }` | toggles `skills.platform_disabled.cli` in `config.yaml` |
| `POST …/{name}/skills` | `{ name }` | `hermes skills install --force` |
| `DELETE …/{name}/skills/{skillName}` | — | removes the skill directory (the CLI uninstall is interactive-only) |
| `POST …/{name}/mcp` | `{ name, transport, url?, command?, args? }` | edits `mcp_servers` in `config.yaml` |
| `DELETE …/{name}/mcp/{serverName}` | — | |
| `GET  …/{name}/integrations` | — | parsed from `gateway_state.json` |
| `PUT  …/{name}/config` | `{ configYaml }` | full config.yaml replace — validated as a YAML mapping (400 otherwise); platform tokens (slack, whatsapp, honcho, …) and `model.default` / `model.base_url` overrides live here |

Create (`POST /api/agents`) accepts optional `baseUrl`; when set, the profile's
`model.default` + `model.base_url` are written directly (ollama / any
OpenAI-compatible endpoint) and no provider API key is required.

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
| `GET /api/images/tags` | `?hostId=` | local tags of `MC_HERMES_IMAGE`, semver-sorted |

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

- Hermes sessions, cron jobs, and webhooks introspection; would light up the
  Calendar and Webhooks pages in live mode.
- SSE/WebSocket streaming for logs and stats (currently polled).
- TLS for remote daemons; authentication for the dashboard itself.
