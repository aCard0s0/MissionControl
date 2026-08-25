# Hermes Mission Control

Operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments — deploy and inspect Hermes containers across Docker hosts, watch live telemetry and logs, and perform small safe edits.

> Read and visualize almost everything. Edit only the smallest safe config surface.

Documentation: [docs/architecture.md](docs/architecture.md) · [docs/api.md](docs/api.md) · [docs/testing.md](docs/testing.md) · [docs/hermes-cli.md](docs/hermes-cli.md) · [docs/mission_control_guidelines.md](docs/mission_control_guidelines.md)

## Modules

| Module | Stack | Role |
|---|---|---|
| [applications/mission-control-fe](applications/mission-control-fe) | Angular 22, signals, GSAP, CDK | "Night Ops" dashboard UI |
| [applications/mission-control-server](applications/mission-control-server) | Spring Boot 3.5, Java 24, SQLite, docker-java | Docker gateway API + serves the UI |

Both ship in **one container**: Spring Boot serves the Angular build and the API on the same origin.

## Quick start (Docker)

```bash
./mc start --build      # build combined image + deploy behind tailscale (default)
./mc start --ts=off     # no tailscale at all — http://127.0.0.1:8080
./mc start --local      # tailnet + a loopback port (bypasses the ACL — see the runbook)
./mc start --ts=off --port=9000          # …on a custom port
./mc status             # which flavor is running, where
./mc logs -f            # follow app logs
./mc ollama up          # optional local model runtime (not started by default)
./mc ollama pull llama3.2   # pull a model into the stack's ollama service
```

Both flavors mount `/var/run/docker.sock` so the dashboard can manage Hermes containers and its dedicated `mission-control-mcp` Compose project, plus a `mission-control-data` volume for SQLite and generated Compose state. `./mc` generates a persistent encryption key in the gitignored `.mission-control.env` on first start. Mounting the socket grants daemon-level access — see the security notes in [docs/architecture.md](docs/architecture.md).

The stack also defines an **optional ollama** service — a local model runtime on host port `11434` (override with `OLLAMA_PORT`), models persisted in an `ollama-models` volume. It is **not started by default**: bring it up with `./mc start --ollama=on` (or `./mc ollama up` on a running deploy) and remove it with `./mc ollama down` / `./mc start --ollama=off`; a plain `./mc start` leaves it exactly as it found it. Register it in the dashboard's Models page as `http://host.docker.internal:11434` (agent containers) or `http://localhost:11434` (this machine), and manage models with `./mc ollama …` (`list`, `pull`, `logs -f`, …).

The **MCP Servers** page owns a separate Compose project named
`mission-control-mcp` on each registered Docker host. On the first run it seeds
Playwright, Context7, Sequential Thinking, and Postgres MCP on the local daemon,
pulling/creating their containers in the stopped state. Seed ports are internal
to `mission-control-mcp-net`; publish a port and configure an explicit
cross-host URL only when Agents on another daemon must reach it. Mission Control
never adopts or changes a pre-existing project named `mcp`.

## Remote access (tailscale)

The default `./mc start` flavor ([deploy/](deploy)) runs the image behind a tailscale sidecar in userspace mode — reachable from any of your devices at `https://mission-control.<tailnet>.ts.net`, with TLS terminated by Tailscale Serve, and unreachable from the LAN or internet (no host ports published). The dashboard has no login of its own and mounts the host docker socket, so the tailnet ACL in [deploy/acl.hujson](deploy/acl.hujson) is the access control, not a nicety. Runbook: [docs/deployment-tailscale.md](docs/deployment-tailscale.md).

## Development

```bash
# backend — http://localhost:8080
cd applications/mission-control-server && MC_ALLOW_DEV_KEY=true mvn spring-boot:run

# frontend — http://localhost:4300 (proxies /api and /health to :8080)
cd applications/mission-control-fe && npm install && npm start
```

The frontend always talks to the backend — run both, and `npm start` proxies `/api` and `/health` to `:8080`. There is no demo-data mode: an unreachable backend shows an empty dashboard and a banner saying so, rather than inventory nobody can act on.

## Status

Working today: Docker hosts (local socket + remote `tcp://`), container inventory/stats/logs/lifecycle, a persisted MCP server catalog with managed Compose services and external endpoints, a persisted ops board, a prompt library, and Hermes profile introspection/editing for SOUL, config, setup, skills, MCP connections, integrations, and sessions. Scheduled jobs and inbound webhooks drive hermes' own `cron` and `webhook`
commands — Mission Control manages them but never carries webhook traffic itself, so a route
stays unreachable from outside the docker network until an operator exposes the agent's
listener deliberately.

For everything the dashboard has no button for there is the web terminal, and next to it a
searchable **CLI Reference** ([docs/hermes-cli.md](docs/hermes-cli.md) is the same catalog):
pick a profile and every line carries its `-p`, then insert one at the prompt — unrun, so
pressing Enter stays your decision.
