# Mission Control — Architecture

Operations dashboard for [Hermes Agent](https://hermes-agent.nousresearch.com/) deployments.
Guiding principle (see [mission_control_guidelines.md](mission_control_guidelines.md)):

> Read and visualize almost everything. Edit only the smallest safe config surface.

## Modules

```
mission-control/
├── applications/
│   ├── mission-control-fe/        Angular 22 dashboard (zoneless, signals, GSAP)
│   └── mission-control-server/    Spring Boot 3.5 backend (Java 24)
├── deploy/                        compose stack — tailscale sidecar + app + optional ollama
│   ├── compose.yml                base stack: no host ports, userspace sidecar
│   ├── compose.local.yml          opt-in loopback port (./mc start --local)
│   ├── acl.hujson                 the tailnet policy that guards the node
│   └── tailscale/                 mounted as /config — serve-https.json, serve-funnel.json
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
│  └── /ws/terminal        xterm.js ↔ docker exec (split shell panes)  │
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

Reading a catalog record comes in two forms, and which one a call site uses matters
because Compose operations are serialized per host. `definition` answers from the
SQLite row alone; `live` first re-reads the managed service's runtime state, which
costs a `docker compose ps` **under that host's compose lock** plus a container
listing, and persists what it finds. Only a call about to act on whether the server
is up — connecting an Agent to it, an explicit check — takes `live`. The Agents page
enriches every profile with its catalog links on a 12-second poll and needs one
column, so that path takes `definition`.

Container configuration is allowlisted (image, list-form command, environment,
ports, support services and named volumes). Host binds, host networking,
privileged mode, devices, capabilities and Docker-socket mounts are rejected.
Secret environment/header values use the same encrypted-at-rest key as profile
templates and are passed to Compose at execution time rather than written into
the generated YAML. As with all container environment variables, their runtime
values remain visible to principals with Docker-daemon access.

## Frontend data loading

`window.__MC_CONFIG__` (served by the backend at `/config.js`, dev default in
`public/config.js`) carries `apiBaseUrl` and `dockerSocket`. There is one mode: the
store starts empty, health-checks the backend, then polls — containers every 10s,
stats per running container every 3s (network rates derived client-side from
cumulative counters), selected-container logs every 5s. Log requests are
non-overlapping and container-scoped because Docker stdout/stderr has no reliable
profile identity.

An unreachable backend shows an empty dashboard and a banner naming the address it
could not reach, and retries every 10s. That is deliberate: seeded demo inventory
used to fill the same screens, and an operator could not tell it from real state.

Hermes profile/agent introspection runs through bounded `docker exec` calls and
profile-file reads. SOUL, config, setup, skills, MCP, integrations and sessions are
all read from the container. Each agent Activity tab polls its own supervised
gateway log under `/opt/data/logs/gateways/{profile}` every five seconds; it does
not reuse the container-wide Docker stream.

### Scheduled jobs and inbound webhooks

Both are hermes' own features, driven the way profiles are: **reads come from the
files hermes owns, writes go through its CLI.**

- **Jobs** live in `<profile>/cron/jobs.json`. Listing reads that file rather than
  parsing the table `hermes cron list` prints; `hermes cron create/edit/pause/
  resume/run/remove` does the writing, because hermes parses the schedule
  expression, mints the job id and computes the next run. Only a `cron` schedule
  carries an expression — `once` stores a timestamp and `interval` a minute count —
  so the UI shows hermes' own display string, which is the one form every kind has.
  The page also reports when the gateway is down: hermes stores jobs either way,
  but nothing fires them.
- **Webhooks** live in `<profile>/webhook_subscriptions.json`, keyed by route name.
  A route needs the profile's `platforms.webhook` listener enabled first, which is
  a config write. Hermes generates each route's HMAC secret, so no secret ever
  travels through the dashboard to reach it.

Both are **per profile** while their pages are per container, so each listing fans
out over the container's profiles — one read each, capped like the other pollers —
and a profile that cannot be read loses only its own entries.

A route's HMAC secret is stored by hermes in plaintext and the sending provider
needs it, so the listing carries only a masked tail and revealing it in full is a
separate, deliberate request.

#### Exposing a webhook listener is the operator's job, deliberately

Mission Control never carries webhook traffic and **never publishes a port for it**.
It manages the listener and the routes; the mapping that makes a route reachable is
the operator's own `docker run -p`. That is a decision rather than a gap, for three
reasons that all point the same way:

- The listener is **per profile**, and every profile defaults to port 8644. A container
  with three agents can have three listeners on three ports, and which ports those are
  is only decided when an operator enables them — long after the container was created.
  A deploy-time publish could only guess, and would reserve a host port for a feature
  most agents never turn on.
- **Docker cannot add a port mapping to a running container.** Publishing on demand, at
  the moment a listener is enabled, would mean recreating the agent container and
  restarting the agent every time someone toggles a webhook.
- Host ports are **one namespace per host**. Two agent containers cannot both hold 8644,
  so Mission Control would have to allocate and record host ports itself — a second
  source of truth about a mapping the operator can change underneath it.

Proxying inbound hooks through the dashboard was considered and rejected for a different
reason: Mission Control has no authentication of any kind, so a proxy route would be an
unauthenticated public trigger for agent runs. Note the asymmetry — hermes' own listener
verifies an HMAC signature per route, which is why exposing *it* is a reasonable thing to
ask an operator to do.

Three things follow, and are implemented:

- **The page never claims reachability.** It shows the route URL with the listener's own
  port, not the `localhost` hermes prints, and says the route is unreachable until the
  port is exposed. `WebhookPlatformDto.published` is always `false`: Mission Control
  publishes nothing itself and does not inspect manual mappings.
- **A manual `-p` survives an image update.** The upgrade copies port bindings, exposed
  ports and `PublishAllPorts` onto the replacement container, alongside the binds and
  networks. Without that, moving an agent to a newer tag would silently un-expose its
  listener, with nothing on any page to say the hooks had stopped arriving.
- **One listener port per container, not per profile.** Enabling a listener refuses a port
  another profile in the same container already holds, and walks a defaulted one up from
  8644 to the first free port. Profiles share one network namespace, so a second listener
  on 8644 never binds — and hermes reports that only in the gateway log of a profile
  nobody has open.

Bind it deliberately when you do expose it: `-p 127.0.0.1:8644:8644` unless the sending
provider is off-host, which is the same default `./mc` uses for the dashboard itself.

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
- **Splits.** The panel body is a [dockview](https://dockview.dev) grid (`terminal-dock.ts`):
  `+` stacks a shell as another tab in the focused group, `◫` puts one *beside* it, and tabs
  drag between groups with the sashes resizing them against each other. Panes render `always`
  rather than only while visible, which is what keeps a hidden shell attached and streaming
  instead of torn out of the DOM. Floating and popped-out groups are disabled: xterm binds a
  terminal to the document it was opened in. The dock is a lazy chunk fetched on first open —
  the panel starts collapsed and shouldn't cost a layout engine before then. Every group is
  held above `220x80` px, so a twelve-way split cannot produce panes too small to hold a
  terminal.
- **Keyboard.** `Ctrl+Shift+←/→` moves the keyboard between panes, walking the tabs inside a
  group before stepping to the next one — a stack and a row read as one ring. The session
  *declines* the chord (`isPaneChord`, returned as `false` from xterm's custom key handler) so
  it reaches the dock's single capture-phase listener instead of every open terminal's PTY.
  Tabs are `role="tab"` on a `role="tablist"` strip with `aria-selected` and a roving
  tabindex, so the whole strip is one Tab stop and `Enter`/`Space` activates a pane.
- **Fits.** Only a pane that is on screen fits and reports its grid, and a fit lands 120ms
  after the layout stops moving (`fitLater`). Every fit is a SIGWINCH the far end answers by
  redrawing its prompt, and a sash drag reports a new size per pointer frame with no
  drag-ended event to hang one fit off — unbounded, that is the prompt stamped across the
  input line. The panel-height drag brackets its own fits explicitly on top of that.
- **The column floor.** Growing a terminal is harmless; *shrinking* one rewraps every
  hard-wrapped line it already holds, and output printed once and never redrawn cannot
  survive that — hermes draws a full-width bordered banner at startup and does not repaint
  on `SIGWINCH`, so a rule printed at 236 columns rewrapped at 118 puts its right-hand text
  across the seam. Any terminal does this; drag a window narrower after running `hermes` to
  see the same wreckage. So the grid a pane has *printed at* is a floor it never goes below:
  a narrower box scrolls sideways to it (xterm's viewport declares `overflow-y: scroll`,
  which resolves `overflow-x` to `auto`) instead of reflowing. The floor is raised by output
  arriving, not by any box the pane once had, and it lifts when the buffer is empty again —
  which is what makes `⌫` and `↻` the way back to a pane fitting its box. Deliberately with no
  expiry: while the floor binds it is holding the grid wide, so everything printed since was
  drawn wide too, and dropping it after some number of rows would rewrap a buffer that is
  *entirely* wide content — one mangled banner traded for a shredded history. A pane being held
  wide says so, since intact-but-offscreen output otherwise reads as truncated — as chrome
  along the pane's bottom edge (`terminal-notice-view.ts`) carrying the `refit` that clears it,
  not as text written into the buffer. It *was* written into the buffer, which put it where the
  operator was looking at the cost of putting it into the scrollback they copy, into a stream
  tools parse, and across whatever line the shell was mid-way through drawing.
- **Targets.** A new tab defaults to the active container but is re-pointable to any container
  via a per-tab picker. The tab list (host+container per tab) *and the arrangement* persist to
  `localStorage` (`mc-terminal-tabs`, envelope `v: 2`; a `v: 1` payload restores its tabs into
  one group) and are restored on reload; the exec sessions themselves always restart on
  reconnect, since a shell is bound to its connection. A saved arrangement is pruned to the
  tabs that actually came back before it is loaded (`pruneLayout`) — an unconfigured tab is
  never saved, so its pane has to go with it and the split it was half of collapses. Two things
  `pruneLayout` leans on are dockview's private business, not its API: the serialized layout's
  shape, and the `dist/dockview-core.js` path the styled bundle lives at. Neither fails at
  compile time if a release moves it, so the dependency is pinned (`~8.2.0`) and a spec puts a
  real `toJSON()` back through the prune into a fresh dock — a shape change fails that instead
  of silently costing the saved arrangement.
- **Agent shortcut.** `shell →` on an agent card (`/agents`) opens a tab pinned to that agent's
  container and types `hermes -p <profile>` into it, so one click lands in a session with that
  agent. `HermesStore.openTerminal()` carries the target; the command is sent as ordinary stdin
  once the first output frame proves the exec is wired (the WebSocket opens before the backend
  registers the shell, so anything sent on `open` would be dropped). Clicking the same agent
  again focuses the tab it already made; re-pointing that tab at another container clears the
  command, so `hermes -p <profile>` never runs where the profile does not exist.
- **Exec user.** The shell runs as `mc.terminal.user` (`MC_TERMINAL_USER`, default `hermes`) —
  the same user every profile-scoped exec uses, because a root shell writing into `/opt/data`
  leaves files the agent itself can no longer read. An image without that account sets the
  variable empty, which keeps the image default.
- **Command drawer.** The `cmds` button opens the hermes CLI reference inside the panel
  ([docs/hermes-cli.md](hermes-cli.md) is the same catalog, and a spec keeps the two in step).
  Its lines carry the focused pane's profile, and **insert** types one at the prompt *without* a
  newline — the operator still presses Enter. The same list is a full page at `/reference`,
  which sends its lines through the existing terminal request channel.

## Environment variables (combined image)

| Var | Default | Meaning |
|---|---|---|
| `MC_DOCKER_SOCKET` | `unix:///var/run/docker.sock` | local daemon endpoint |
| `MC_CONTAINER_FILTER` | `hermes` | substring marking Hermes-related containers (`?all=true` bypasses) |
| `MC_HERMES_IMAGE` | `nousresearch/hermes-agent` | image used by deploys |
| `MC_REGISTRY_TAGS` | `true` | look up published tags on Docker Hub; set `false` for air-gapped installs (tag listing then shows only pulled images) |
| `MC_DB_PATH` | `/data/mission-control.db` | SQLite file |
| `MC_MCP_STACK_DIR` | `/data/mcp-stacks` in the image | generated non-secret per-host Compose files |
| `MC_API_BASE_URL` | `` (same origin) | only for split FE/BE deployments |
| `MC_PORT` | `8080` | server port |
| `MC_TERMINAL_USER` | `hermes` | container user the web terminal's shell runs as; empty keeps the image default |
| `MC_SECRET_KEY` | required | AES key for encrypted template secrets; `./mc` creates and reuses it automatically |
| `MC_SECRET_KEY_PREVIOUS` | empty | prior key accepted temporarily during rotation |
| `MC_LOG_LEVEL` | `INFO` | level for `io.hermes.missioncontrol` only; `DEBUG` adds per-operation detail without turning on the libraries' own logging |

### Logging

The console pattern is `timestamp LEVEL SimpleClassName : message`. The PID, the application
name and the fully-qualified logger are dropped — one process per container makes all three
constant — and Spring's banner, its startup-info lines and the boot chatter from Tomcat,
Hikari and the servlet context are switched off. `StartupSummary` replaces them with one
block naming the port, the Docker endpoint and whether it answered, the database file, the
MCP stack directory, and whether secrets use a real key or the dev one.

What each level means here:

- **ERROR** — a defect or a state the operator must repair: an unhandled request failure, an
  MCP Compose operation that failed on its own executor (where no request ever sees it), an
  upgrade that could not remove its replacement.
- **WARN** — degraded but handled, and anything destructive: removing a container and its
  data volume, deleting an MCP server whose volumes are retained, a rolled-back deploy, an
  unreachable daemon, a container hidden from the fleet view.
- **INFO** — one line per real state change: deploy, upgrade, start, stop, host added or
  removed, MCP provisioned/started/stopped, startup summary.
- **DEBUG** — per-operation detail that is normally uninteresting: terminal teardown and
  sweeps, best-effort refreshes, a terminal opened on a stopped container.

Conditions that hold across polls are reported once, not once per poll — the fleet view
refreshes every 10 seconds, and its exclusion warnings previously accounted for 93% of all
log output.

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

### Container image updates

Docker cannot swap the image of a running container, so moving an Agent onto a
newer tag means replacing the container. Mission Control does that without
touching the Agent's data:

- The target tag is **pulled first**, before anything is stopped, so a bad tag
  or an unreachable registry costs no downtime.
- The old container is **renamed aside**, not removed. The replacement is created
  under the original name with the same labels, binds, restart policy, command,
  user-defined networks and published ports — notably the managed MCP network, which
  is attached after deploy and would otherwise be silently lost, and any port an
  operator mapped by hand to reach a webhook listener. Only once the replacement
  passes readiness is the parked original removed; any failure restores it.
- The `mc-hermes-<name>` volume is **reattached, never recreated or deleted**, so
  profiles, souls, memory, skills, sessions and credentials carry over. No
  bootstrap one-shots run — the profiles already exist, and the `mc.profiles`
  label records only what the original deploy seeded, not what exists now.
- A container that was **stopped stays stopped**; an operator parked it on purpose.
- Recreating mints a new container id, so `board_tasks` and `mcp_agent_links`
  rows are repointed at it in one transaction. A failed remap is logged but does
  not undo a healthy update.
- Containers left behind by an interrupted update keep a `-mc-upgrade-<hex>`
  suffix. They are hidden from the fleet listing and reachable via `?all=true`.

Host-config customizations applied out of band (`docker update`, CPU or memory
limits) are outside the copied set and are not preserved. Port mappings are inside
it, because a mapping cannot be re-applied to a running container and is the only
way a webhook listener is reachable.

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
- Agent containers publish no ports at all. Exposing a profile's webhook listener is
  the operator's own `docker run -p`, and `127.0.0.1` is the right bind unless the
  sending provider is off-host. That listener does authenticate — hermes verifies an
  HMAC signature per route — which the dashboard itself has no equivalent of.

## Development

```bash
# backend (terminal 1) — http://localhost:8080
cd applications/mission-control-server && MC_ALLOW_DEV_KEY=true mvn spring-boot:run

# frontend (terminal 2) — http://localhost:4300, proxies /api + /health to :8080
cd applications/mission-control-fe && npm start
```

`public/config.js` points the dev frontend at the backend; the combined image
serves the same file from `MC_*` environment variables instead.

Backend tests follow the seams and conventions in [testing.md](testing.md) — most of the code
worth testing here sits behind a Docker daemon, a provider API or an async executor, and that
document is how it is reached without one.
