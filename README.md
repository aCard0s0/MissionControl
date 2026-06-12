# Hermes Mission Control

Operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments — deploy and inspect Hermes containers across Docker hosts, watch live telemetry and logs, and perform small safe edits.

> Read and visualize almost everything. Edit only the smallest safe config surface.

Documentation: [docs/architecture.md](docs/architecture.md) · [docs/api.md](docs/api.md) · [docs/mission_control_guidelines.md](docs/mission_control_guidelines.md)

## Modules

| Module | Stack | Role |
|---|---|---|
| [applications/mission-control-fe](applications/mission-control-fe) | Angular 22, signals, GSAP, CDK | "Night Ops" dashboard UI |
| [applications/mission-control-server](applications/mission-control-server) | Spring Boot 3.5, Java 21, SQLite, docker-java | Docker gateway API + serves the UI |

Both ship in **one container**: Spring Boot serves the Angular build and the API on the same origin.

## Quick start (Docker)

```bash
scripts/deploy-docker.sh            # build combined image + run on :8080
PORT=9000 scripts/deploy-docker.sh  # custom port
MC_DATA_MODE=mock scripts/deploy-docker.sh   # demo mode with mock data
```

The script mounts `/var/run/docker.sock` so the dashboard can see and manage Hermes containers on the host, and a `mission-control-data` volume for the SQLite file. Mounting the socket grants daemon-level access — see the security notes in [docs/architecture.md](docs/architecture.md).

## Development

```bash
# backend — http://localhost:8080
cd applications/mission-control-server && mvn spring-boot:run

# frontend — http://localhost:4300 (proxies /api and /health to :8080)
cd applications/mission-control-fe && npm install && npm start
```

The frontend dev default is `dataMode: 'mock'` (no backend needed) — switch to `live` in [public/config.js](applications/mission-control-fe/public/config.js) to drive it from the real backend.

## Status

Live mode today: Docker hosts (local socket + remote `tcp://`), container inventory/stats/logs/lifecycle, persisted ops board. Hermes profile introspection (SOUL.md, skills, MCP, cron) is mock-only until the hermes adapter lands — the UI states this explicitly.
