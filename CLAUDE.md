# Mission Control

Operations dashboard for Hermes Agent deployments. Angular 22 frontend + Spring Boot 3.5
backend, shipped as **one container**: Spring serves the Angular build and the API on the
same origin.

> Read and visualize almost everything. Edit only the smallest safe config surface.

This file routes. It holds no facts of its own — every row below points at the file that
owns the fact.

## Where things live

| Path | What it holds |
|---|---|
| [docs/map/](docs/map/) | **the edit map** — what the nouns are, what a change hits. Start here. |
| [docs/architecture.md](docs/architecture.md) | the as-built spec: module layout, why each decision, env vars, security |
| [docs/api.md](docs/api.md) | HTTP surface |
| [docs/testing.md](docs/testing.md) | test seams and conventions — read before writing a test |
| [docs/mission_control_guidelines.md](docs/mission_control_guidelines.md) | product rules: what may be edited, what is read-only |
| [docs/deployment-tailscale.md](docs/deployment-tailscale.md) | tailnet runbook |
| `applications/mission-control-server/` | Spring Boot backend, Java 24, Maven |
| `applications/mission-control-fe/` | Angular frontend, signals, zoneless |
| `deploy/`, `mc`, `Dockerfile` | build and deploy |

## Route by what you are doing

| If you are | Go to |
|---|---|
| changing anything and want to know the blast radius | [docs/map/effects/CONTEXT.md](docs/map/effects/CONTEXT.md) |
| asking "what is X" | [docs/map/objects/_index.md](docs/map/objects/_index.md) |
| confused by `providers` vs `models` vs `inference-endpoints` | [docs/map/CLAUDE.md](docs/map/CLAUDE.md) — name collisions |
| adding or changing an HTTP route | `docs/map/objects/dashboard/api-contract.md` — the route list is snapshot-tested from both sides |
| writing a test | [docs/testing.md](docs/testing.md) |
| touching deploy, upgrade, MCP apply, profile edit or polling | [docs/map/processes/](docs/map/processes/) |

## Build and test

```bash
cd applications/mission-control-server && mvn test        # backend
cd applications/mission-control-fe && npm ci && npm run lint && npm run test:coverage
```

There is no aggregator POM. Each app builds from its own directory. Both run in CI
([.github/workflows/ci.yml](.github/workflows/ci.yml)).

## The one rule

The Docker daemon and the Hermes containers are the source of truth. SQLite holds only
dashboard-owned concepts that have no Hermes home. Never cache what the daemon owns.
