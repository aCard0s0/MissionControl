---
type: process
status: verified
verified: main @ 976a9c9 · 2026-08-28
consumes: [container, profile, mcp-server-entry, board-task, prompt, image]
produces: []
---

# hydrate-poll

The frontend's clock. Probe the backend, load everything once it answers, then keep each domain
fresh on its own period. `LiveSync` — `core/store/live-sync.ts:43`.

## Input → Movement → Output

Nothing goes in but `window.__MC_CONFIG__`. The store starts **empty**, health-checks the
backend, loads each domain once, then re-reads each on a staggered timer. Out come signals the
pages render from.

## Why this shape

**There is one mode, and it is "talk to the real backend".** An unreachable backend shows an
empty dashboard and a banner naming the address it could not reach, retrying every 10 s
(`RETRY_MS`, `core/store/live-sync.ts:38`). That is deliberate and the architecture doc says why:
seeded demo inventory used to fill the same screens, and **an operator could not tell it from
real state**.

**The periods are staggered on purpose** (`core/store/live-sync.ts:24`): container state moves
fastest, published image tags change on the order of days and each lookup probes the daemon.

| Domain | Period | Why |
|---|---|---|
| stats | 3 s | per running container; network rates derived client-side from cumulative counters |
| logs | 5 s | selected container only, non-overlapping requests |
| containers | 10 s | the fleet |
| agents | 12 s | per profile; enriched with catalog links — the path that forced [`definition` vs `live`](../objects/mcp/mcp-server-entry.md) |
| jobs | 30 s | a schedule changes when a job runs or an operator edits one, and reading it is **one exec per profile** |
| imageCatalogs | 300 s | each lookup probes the daemon; tags change over days |

Log requests are **non-overlapping and container-scoped**, because Docker stdout/stderr has no
reliable profile identity. Each agent's Activity tab polls its *own* supervised gateway log under
`/opt/data/logs/gateways/{profile}` every five seconds — it does **not** reuse the container-wide
Docker stream.

**Conditions that hold across polls are reported once, not once per poll.** The fleet view
refreshes every 10 s and its exclusion warnings previously accounted for **93% of all log
output**.

## Steps

1. Read `window.__MC_CONFIG__` — served by the backend at `/config.js`, dev default in
   `public/config.js`. Carries `apiBaseUrl` and `dockerSocket`.
2. `probeBackend()`; on failure set the banner and retry in `RETRY_MS`
   (`core/store/live-sync.ts:88`).
3. Load every domain once through its store.
4. Start one timer per domain from `POLL` (`core/store/live-sync.ts:24`).

## If you change this

- **Hits:** every store in `core/store/` (they are the poll targets); the banner in
  `store-context.ts`; backend read paths — **adding a poll is adding load to a specific
  endpoint**, and if that endpoint refreshes MCP runtime state it also contends for the
  [per-host Compose lock](mcp-apply.md).
- **Does not hit:** the terminal. Its WebSockets are outside this clock entirely — see
  [terminal session](../objects/dashboard/terminal-session.md).

## Surfaces

| Surface | Role |
|---|---|
| `/config.js` | the only bootstrap input |
| `/health` | the probe |
| every `/api/**` read route | polled |

## See

- Source: `applications/mission-control-fe/src/app/core/store/live-sync.ts`
- `docs/architecture.md`, "Frontend data loading"
