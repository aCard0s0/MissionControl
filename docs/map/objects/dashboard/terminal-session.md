---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-fe/src/app/shared/terminal-session.ts
---

# Terminal session

One shell: an xterm instance plus its own WebSocket to a chosen host+container, bridged to
`docker exec` over **`/ws/terminal`**. FE `terminal-session.ts` / `terminal-panel.ts` /
`terminal-dock.ts`; BE `terminal/TerminalSocketHandler.java`.

## Why this shape

**Read the terminal section of [../../../architecture.md](../../../architecture.md) before
touching any of this.** It is the most subtle subsystem in the tree and the doc argues every
non-obvious choice at length — tabs, splits, the keyboard chord that must be *declined* by
xterm to reach the dock's capture listener, why fits are debounced 120 ms after layout settles,
the `localStorage` layout envelope and its `pruneLayout`, and the two things `pruneLayout` leans
on that are **dockview's private business** (the serialized layout shape and the
`dist/dockview-core.js` path), which is why the dependency is pinned to `~8.2.0`.

The one design idea worth carrying in your head, because it explains behaviour that otherwise
reads as a bug: **the column floor.** Growing a terminal is harmless; *shrinking* one rewraps
every hard-wrapped line already in the buffer, and output printed once and never redrawn cannot
survive that — hermes draws a full-width bordered banner at startup and does not repaint on
`SIGWINCH`. So the grid a pane has *printed at* is a floor it never goes below: a narrower box
scrolls sideways instead of reflowing. A pane being held wide says so through chrome along its
bottom edge (`terminal-notice-view.ts`), carrying the `refit` that clears it — **not** as text
written into the buffer, which would land in the scrollback operators copy and the streams tools
parse.

The floor lifts when the buffer is empty, which is what makes `⌫` and `↻` the way back to a
pane fitting its box. Deliberately with no expiry.

## Shape

- Backend treats **every connection as a separate `docker exec`** keyed by WebSocket session id,
  so N concurrent sessions need no server-side change.
- Binary frames carry raw terminal bytes; a text frame `{"type":"resize",…}` sets the size.
- Limits — `terminal/TerminalProperties.java:17`: `maxSessions` default **50**,
  `maxSessionsPerClient` default **5** per remote address, enforced by `TerminalSessionLimiter`.
- Exec user: `mc.terminal.user` / `MC_TERMINAL_USER`, default `hermes` — the same user every
  profile-scoped exec uses, because a root shell writing into `/opt/data` leaves files the agent
  can no longer read. Empty keeps the image default.
- Layout persists to `localStorage` key `mc-terminal-tabs`, envelope `v: 2` (a `v: 1` payload
  restores its tabs into one group). The exec sessions themselves always restart on reconnect.

## Connected to

- **owns:** its exec, its xterm buffer, its saved layout slot
- **owned-by:** a [container](../docker/container.md) on a [host](../docker/docker-host.md)
- **joins:** [Profile](../agents/profile.md) — `shell →` on an agent card opens a tab pinned to
  that container and types `hermes -p <profile>` as **ordinary stdin once the first output frame
  proves the exec is wired** (the WebSocket opens before the backend registers the shell, so
  anything sent on `open` is dropped). Re-pointing that tab clears the command.
- **looks-like-but-is-not:** the CLI reference drawer. Its lines are *inserted* at the prompt
  without a newline — pressing Enter stays the operator's decision.

## If you change this

- **Hits:** `terminal-panel.ts`, `terminal-dock.ts`, `terminal-session.ts`, `terminal-tabs.ts`,
  `terminal-notice-view.ts`, `terminal-request-store.ts`, `panel-height.ts`;
  `TerminalSocketHandler`, `TerminalWebSocketConfig`, `TerminalSessionLimiter`; and the
  `pruneLayout` spec, which puts a real `toJSON()` back through the prune into a fresh dock so a
  dockview shape change fails a test instead of silently costing the saved arrangement.
- **Does not hit:** anything persisted server-side. No session, tab or layout reaches SQLite.
  Bumping dockview past `~8.2.0` **does** hit it — nothing fails at compile time if a release
  moves the private bits.

## Surfaces

| Surface | Role |
|---|---|
| `/ws/terminal` | one `docker exec` per connection |
| `localStorage` `mc-terminal-tabs` | tab list + arrangement |
| the container's shell | whatever the operator types |

## See

- Source: `applications/mission-control-fe/src/app/shared/terminal-*.ts`,
  `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/terminal/`
- The full design: `docs/architecture.md`, "Terminal"
