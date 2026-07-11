# Mission Control — Architecture

Operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments.
Guiding principle (see [mission_control_guidelines.md](mission_control_guidelines.md)):

> Read and visualize almost everything. Edit only the smallest safe config surface.

## Modules

```
MissionControl/
├── applications/
│   ├── mission-control-fe/        Angular 22 dashboard (zoneless, signals, GSAP)
│   └── mission-control-server/    Spring Boot 3.5 backend (Java 24)
├── deploy/tailscale/              compose stack — tailscale sidecar + app + optional ollama
├── docs/                          this documentation
├── mc                             manager script — build + deploy (tailscale or plain docker) + ollama
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
│  ├── /api/mcp-servers    MCP registry + Compose lifecycle             │
│  ├── /api/board/tasks    kanban state (SQLite)                       │
│  └── /ws/terminal        xterm.js ↔ docker exec (multi-tab shells)   │
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
  concepts that have no Hermes home: Docker hosts, MCP catalog definitions and
  agent links, profile templates, model providers, retained MCP volume metadata,
  and ops board tasks. Single-connection pool; no database server.

## MCP server catalog and Compose projects

The MCP Servers page is global rather than scoped to the active Hermes
container. Catalog entries are either a managed container, an external HTTP/SSE
endpoint, or a reusable stdio definition. Managed entries belong to one Docker
host and are rendered into a real Compose project named
`mission-control-mcp` on that daemon. The existing/user-owned project named
`mcp` is unrelated and is never adopted or modified.

Each host's generated Compose file lives below `/data/mcp-stacks`; SQLite is
the source of truth and the file can be regenerated. Compose operations are
serialized per host and run without a shell. Managed services use the shared
`mission-control-mcp-net` network and stable service aliases. Same-host Hermes
containers are attached to that network when a catalog entry is connected;
cross-host connections require an explicit agent-reachable URL.

Container configuration is allowlisted (image, list-form command, environment,
ports, support services and named volumes). Host binds, host networking,
privileged mode, devices, capabilities and Docker-socket mounts are rejected.
Secret environment/header values use the same encrypted-at-rest key as profile
templates and are passed to Compose at execution time rather than written into
the generated YAML. As with all container environment variables, their runtime
values remain visible to principals with Docker-daemon access.

## Frontend data modes

`window.__MC_CONFIG__` (served by the backend at `/config.js`, dev default in
`public/config.js`) selects the mode:

- **mock** — seeded demo fleet + simulated telemetry; used for design work and demos.
- **live** — starts empty, health-checks the backend, then polls:
  containers every 10s, stats per running container every 3s (network rates
  derived client-side from cumulative counters), selected-container logs every
  5s. Log requests are non-overlapping and container-scoped because Docker
  stdout/stderr has no reliable profile identity. Failures fail closed:
  missing/broken config lands in live (empty +
  banner), never silently in demo data.

Hermes profile/agent introspection is wired in live mode through bounded
`docker exec` calls and profile-file reads. SOUL, config, setup, skills, MCP,
integrations, and sessions are live. Each agent Activity tab polls its own
supervised gateway log under `/opt/data/logs/gateways/{profile}` every five
seconds; it does not reuse the container-wide Docker stream. Calendar jobs and
webhooks remain mock-only.

## Terminal

A VSCode-style panel pinned to the bottom of the dashboard
(`applications/mission-control-fe/src/app/shared/terminal-panel.ts`) bridges xterm.js to
the backend `/ws/terminal` endpoint, which runs `docker exec` (interactive tty, bash/sh)
inside a container — so `hermes` commands behave exactly as over `docker exec -it`. Binary
WebSocket frames carry raw terminal bytes; a text frame (`{"type":"resize",…}`) sets the size.

- **Tabs.** Each tab is an independent shell — its own xterm instance and its own WebSocket
  to a chosen host+container (`terminal-session.ts`) — so you can run two agents in the same
  container, or shells across different containers/hosts, at once. The backend treats every
  connection as a separate `docker exec` keyed by WebSocket session id, so N concurrent
  sessions need no server-side change.
- **Targets.** A new tab defaults to the active container but is re-pointable to any container
  via a per-tab picker. The tab list (host+container per tab) persists to `localStorage`
  (`mc-terminal-tabs`) and is restored on reload; the exec sessions themselves always restart
  on reconnect, since a shell is bound to its connection. Background tabs keep their socket
  open, so their output keeps streaming while another tab is on screen.
- **Live only.** The panel needs the live backend — it is disabled in mock mode.

## Environment variables (combined image)

| Var | Default | Meaning |
|---|---|---|
| `MC_DATA_MODE` | `live` | `live` or `mock` (demo data) |
| `MC_DOCKER_SOCKET` | `unix:///var/run/docker.sock` | local daemon endpoint |
| `MC_CONTAINER_FILTER` | `hermes` | substring marking Hermes-related containers (`?all=true` bypasses) |
| `MC_HERMES_IMAGE` | `nousresearch/hermes-agent` | image used by deploys |
| `MC_DB_PATH` | `/data/mission-control.db` | SQLite file |
| `MC_MCP_STACK_DIR` | `/data/mcp-stacks` in the image | generated non-secret per-host Compose files |
| `MC_API_BASE_URL` | `` (same origin) | only for split FE/BE deployments |
| `MC_PORT` | `8080` | server port |
| `MC_SECRET_KEY` | required | AES key for encrypted template secrets; `./mc` creates and reuses it automatically |
| `MC_SECRET_KEY_PREVIOUS` | empty | prior key accepted temporarily during rotation |

### Container deploy defaults

Mission Control deploys Hermes containers with:
- `gateway run` as the command
- a per-container volume mounted at `/opt/data`
- restart policy `unless-stopped`
- bounded readiness checks followed by creation of requested named profiles

The current Hermes image initializes the default profile on first boot. Mission
Control waits for that initialization and rolls the container and volume back
if readiness or named-profile creation fails. Permanent removal deletes only
the recorded Mission Control-managed volume; external mounts are never guessed
or deleted.

## Security notes

- Mounting `docker.sock` gives the container root-equivalent control of the
  host. For production, front the socket with a restricted proxy (e.g.
  docker-socket-proxy allowing only the endpoints used here) and add
  authentication in front of the dashboard — there is none built in yet.
- Remote hosts are plain `tcp://`; TLS daemon sockets are not implemented yet.
- Managed MCP lifecycle uses the Docker CLI Compose plugin inside the Mission
  Control image. Only the `mission-control-mcp` project is owned; project-label
  collisions fail closed instead of deleting unknown containers.
- Destructive UI actions (remove container/host) require typed confirmation,
  and the backend refuses to delete the local socket host.
- Plain `./mc start --ts=off` binds to `127.0.0.1`; setting `BIND_ADDRESS`
  explicitly can expose the unauthenticated dashboard and prints a warning.

## Development

```bash
# backend (terminal 1) — http://localhost:8080
cd applications/mission-control-server && MC_ALLOW_DEV_KEY=true mvn spring-boot:run

# frontend (terminal 2) — http://localhost:4300, proxies /api + /health to :8080
cd applications/mission-control-fe && npm start
```

Set `dataMode` in `applications/mission-control-fe/public/config.js` to `mock`
(default, no backend needed) or `live` (real daemon via the backend).
