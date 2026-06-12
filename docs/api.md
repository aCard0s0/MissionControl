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
| `POST /api/containers` | `{ hostId, name, version?, profiles? }` | creates + starts `MC_HERMES_IMAGE:version`, pulls if missing; profiles stored as `mc.profiles` label |
| `POST /api/containers/{hostId}/{id}/start` | — | |
| `POST /api/containers/{hostId}/{id}/stop` | — | 10s graceful timeout |
| `DELETE /api/containers/{hostId}/{id}` | — | force remove |

Container DTO: `{ id, shortId, name, hostId, status, image, version, startedAt, sizeRootFsGb, profiles }`
with `status ∈ running | stopped | unhealthy | unknown`.

## Ops board — dashboard-owned state in SQLite

| Method & path | Body / params |
|---|---|
| `GET /api/board/tasks` | `?containerId=` |
| `POST /api/board/tasks` | `{ containerId, agentId?, title, column?, priority?, tags? }` |
| `PATCH /api/board/tasks/{id}` | `{ column }` — `queued | running | review | done` |
| `DELETE /api/board/tasks/{id}` | — |

## Roadmap (not implemented)

- Hermes adapter: profile/agent introspection (`SOUL.md`, skills, MCP,
  sessions, cron jobs) via `docker exec hermes …`; would light up the Agents,
  Calendar, and Webhooks pages in live mode.
- SSE/WebSocket streaming for logs and stats (currently polled).
- TLS for remote daemons; authentication for the dashboard itself.
