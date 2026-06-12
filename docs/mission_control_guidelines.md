# Hermes Mission Control — Product & Design Guidelines

> A lightweight operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments.
> Read and visualize almost everything. Edit only the smallest safe config surface.

---

## 1. Product Direction

Mission Control is a thin visualization and configuration layer on top of existing Hermes Agent capabilities. It is **not** a new agent runtime, scheduler, memory engine, chat engine, or orchestration platform — Hermes already provides all of those.

The dashboard exists to answer three questions fast:

1. **What is deployed?** — Which Hermes containers are running, and which profiles/agents live inside each one.
2. **What is each agent connected to and doing?** — Gateways, MCP servers, skills, scheduled jobs, recent sessions and logs.
3. **What small thing do I need to change?** — Deploy/remove a container, add/remove a profile, edit a system prompt (`SOUL.md`), toggle a skill or MCP server, manage a cron job or webhook.

### Hermes concepts the dashboard maps to

Mission Control mirrors real Hermes Agent structures rather than inventing its own:

| Dashboard concept | Hermes reality |
|---|---|
| Container | A Docker deployment of Hermes (CLI + gateway modes, volume-mounted config). Sandbox backends: local, Docker, SSH, Singularity, Modal. |
| Agent / Profile | A Hermes profile — an isolated home directory (`~/.hermes/profiles/<name>/`) with its own `config.yaml`, `.env`, `SOUL.md`, memory, sessions, skills, state DB, cron jobs, gateway state, and logs. |
| System prompt / Soul | `SOUL.md` — personality directives and instructions. |
| Active config | `config.yaml` — model selection, provider settings, toolsets, `terminal.cwd`. |
| Integrations | Per-profile gateway processes: Telegram, Discord, Slack, WhatsApp, Signal, Email, and other platforms. Each gateway has its own bot token; token conflicts across profiles are surfaced. |
| Skills | Open-standard skills (agentskills.io compatible), bundled + user + Skills Hub. |
| MCP | MCP servers connected per profile, with tool filtering. |
| Jobs | Hermes built-in cron scheduler — supports 5-field cron (`0 9 * * *`), natural phrases (`every monday 9am`), durations (`30m`), and ISO timestamps, with delivery to any platform. |
| Sessions / Subagents | Conversation sessions and isolated parallel subagents. |

### Two supported deployment styles

1. **Multiple Hermes containers** — one profile each, or
2. **One container, multiple profiles/agents** inside it.

The dashboard treats both uniformly: select a container → see its profiles.

---

## 2. Pages

1. **Overview** — system health cards for the selected container.
2. **Containers** — detect, deploy, stop, and remove Hermes containers; create them pre-seeded with one or more profiles.
3. **Agents** — profile cards (active / idle / dormant), statistics, and a detail view per agent:
   - Active config summary (model, provider, toolsets)
   - `SOUL.md` and other key files (`MEMORY.md`, context files) — view always, edit only the approved surface
   - Enabled skills (add / remove)
   - MCP servers (add / remove, connection state)
   - Messaging integrations and connectivity checks (Slack, WhatsApp, Discord, Telegram, Signal, Email)
   - Scheduled jobs owned by the agent
   - Recent sessions and minimal logs
4. **Ops Board** — Kanban-style execution view of agent tasks (queued / running / review / done).
5. **Calendar** — scheduled cron jobs across the container; create, pause, edit prompt, reassign to another agent.
6. **Webhooks** — manage inbound webhooks per agent (URL, secret, event filters, recent deliveries).

---

## 3. User Flows

### Flow A — Select Container

1. User opens Mission Control and lands on a container rail/selector with all detected Hermes containers, plus a "deploy new" action.
2. Selecting a container makes it the **active context** for every page.
3. All data shown afterwards belongs to that container only.

Container selector shows: name, short ID hash, status (`running / stopped / unhealthy / unknown`), image + version, uptime, profile count, and a resource usage summary.

**Hard rule:** the dashboard never mixes data from multiple containers in one view. Switching containers is always explicit.

### Flow B — Container Overview

Cards on the overview page:

- CPU, RAM, disk, network I/O (live sparklines)
- Container uptime and health
- Active / idle / dormant profiles
- Recent errors and minimal log tail
- Jobs enabled + last run
- MCP servers connected
- Communication integrations detected (Slack, WhatsApp, Discord, Telegram, Signal, Email…)

### Flow C — Inspect / Create Agent

1. User picks an agent card (or "new agent").
2. Detail view answers: *what is this agent connected to, what can it do, what has it been doing recently?*
3. Creating an agent asks only for: name, model provider + model, API key, optional clone source (mirrors `hermes profile create --clone`).
4. Edits allowed in v1: `SOUL.md` content, display metadata, skill/MCP toggles, job enable/pause. Everything else is read-only with deep links to native Hermes tooling.

---

## 4. MVP Definition

The MVP is successful when a user can:

1. Select a Hermes container.
2. See real container resource usage.
3. See the profiles/agents inside the selected container.
4. See active agents and minimal logs.
5. Inspect active config and integrations for one agent.
6. View agent work on a Kanban-style ops board.
7. Safely edit only the system prompt / `SOUL.md` and small approved config fields.
8. Jump to existing Hermes features instead of replacing them.

---

## 5. Visual & Interaction Design

Deliberately **not** the Hermes website aesthetic. Mission Control has its own identity: a flight-operations room, calm and precise.

### Direction — "Night Ops"

- **Theme:** deep near-black canvas with a single high-voltage accent (signal green) plus a small semantic palette (amber = warning, red = critical, cyan = info). Light is information: anything glowing is live data.
- **Typography:** a characterful grotesque for display headings, a clean grotesque for UI text, and a monospace for telemetry (IDs, logs, metrics). Tabular numerals everywhere data ticks.
- **Surfaces:** flat panels separated by hairline borders rather than drop shadows; subtle grain/scanline texture allowed at very low opacity. Corners barely rounded.
- **Data first:** sparklines, gauges, and status dots over decorative illustration. Every metric ticks live; numbers animate when they change.
- **Motion (GSAP):** purposeful and physical — staggered panel entrances on route change, counters that roll, status transitions that pulse once then settle. Nothing loops endlessly except genuine live indicators. Respect `prefers-reduced-motion`.
- **Density:** operator-grade. Compact rows, generous information per screen, but a strict 8-pt rhythm so it never reads as clutter.
- **Voice:** terse system language — "3 agents active · 1 dormant", "gateway up 14d".

### Component conventions

- Status is always shown as dot + word, never color alone (accessibility).
- Destructive actions (remove container, delete profile) require typed confirmation.
- Read-only fields visibly locked, with a hint pointing to the native Hermes CLI command.
- Empty states teach: show the `hermes profile create` command that would populate the view.

---

## 6. Architecture Notes (frontend)

- Angular (standalone components, signals, OnPush/zoneless), SCSS design tokens.
- GSAP for entrance/transition animation; CSS for micro-states.
- A mock Hermes API layer (signal stores + simulated telemetry streams) stands in for the real container/agent endpoints, kept behind an interface so a real adapter can replace it without touching UI.
- All pages scoped to a `selectedContainer` signal — enforcing the no-mixing rule at the store level, not per page.
