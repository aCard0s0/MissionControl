# Mission Control — Architecture

Operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments.
Guiding principle (see [mission_control_guidelines.md](mission_control_guidelines.md)):

> Read and visualize almost everything. Edit only the smallest safe config surface.

## Modules

```
MissionControl/
├── applications/
│   ├── mission-control-fe/        Angular 22 dashboard (zoneless, signals, GSAP)
│   └── mission-control-server/    Spring Boot 3.5 backend (Java 21)
├── docs/                          this documentation
├── scripts/deploy-docker.sh       build + run the combined image
├── Dockerfile                     combined image (FE + BE, one container)
└── pom.xml                        maven aggregator
```

## How the pieces fit

```
┌─────────────────────────── one container ───────────────────────────┐
│  Spring Boot :8080                                                   │
│  ├── serves the Angular build (classpath:/static, SPA fallback)      │
│  ├── GET /config.js      runtime config from MC_* env (no rebuild)   │
│  ├── GET /health         liveness + docker connectivity              │
│  ├── /api/hosts          docker host registry (SQLite) + live probes │
│  ├── /api/containers     inventory / stats / logs / lifecycle        │
│  └── /api/board/tasks    kanban state (SQLite)                       │
│            │                                                         │
│            ▼ docker-java (zerodep transport)                         │
│  unix:///var/run/docker.sock  (mounted)  +  tcp://remote:2376 hosts  │
└──────────────────────────────────────────────────────────────────────┘
```

- The browser never talks to Docker — it physically can't (unix socket) and
  must not (daemon access is root-equivalent). The backend is the gateway.
- **Source of truth**: the Docker daemon and the Hermes containers themselves.
  The backend is read-through for all of that; nothing daemon-owned is cached
  or persisted.
- **SQLite** (file at `MC_DB_PATH`, volume `/data`) holds only dashboard-owned
  concepts that have no Hermes home: the remote docker-host registry and ops
  board tasks. Single-connection pool; no database server.

## Frontend data modes

`window.__MC_CONFIG__` (served by the backend at `/config.js`, dev default in
`public/config.js`) selects the mode:

- **mock** — seeded demo fleet + simulated telemetry; used for design work and demos.
- **live** — starts empty, health-checks the backend, then polls:
  containers every 10s, stats per running container every 3s (network rates
  derived client-side from cumulative counters), selected-container logs every
  5s. Failures fail closed: missing/broken config lands in live (empty +
  banner), never silently in demo data.

Hermes profile/agent introspection (SOUL.md, skills, MCP, sessions, cron) is
**not wired in live mode yet** — the UI says so explicitly. That requires a
hermes adapter in the backend (`docker exec hermes …` / profile file reads),
which is the next roadmap step.

## Environment variables (combined image)

| Var | Default | Meaning |
|---|---|---|
| `MC_DATA_MODE` | `live` | `live` or `mock` (demo data) |
| `MC_DOCKER_SOCKET` | `unix:///var/run/docker.sock` | local daemon endpoint |
| `MC_CONTAINER_FILTER` | `hermes` | substring marking Hermes-related containers (`?all=true` bypasses) |
| `MC_HERMES_IMAGE` | `nousresearch/hermes-agent` | image used by deploys |
| `MC_DB_PATH` | `/data/mission-control.db` | SQLite file |
| `MC_API_BASE_URL` | `` (same origin) | only for split FE/BE deployments |
| `MC_PORT` | `8080` | server port |

## Security notes

- Mounting `docker.sock` gives the container root-equivalent control of the
  host. For production, front the socket with a restricted proxy (e.g.
  docker-socket-proxy allowing only the endpoints used here) and add
  authentication in front of the dashboard — there is none built in yet.
- Remote hosts are plain `tcp://`; TLS daemon sockets are not implemented yet.
- Destructive UI actions (remove container/host) require typed confirmation,
  and the backend refuses to delete the local socket host.

## Development

```bash
# backend (terminal 1) — http://localhost:8080
cd applications/mission-control-server && mvn spring-boot:run

# frontend (terminal 2) — http://localhost:4300, proxies /api + /health to :8080
cd applications/mission-control-fe && npm start
```

Set `dataMode` in `applications/mission-control-fe/public/config.js` to `mock`
(default, no backend needed) or `live` (real daemon via the backend).
