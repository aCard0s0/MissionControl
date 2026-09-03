---
type: process
status: verified
verified: main @ 976a9c9 · 2026-08-28
consumes: [profile]
produces: [profile, cron-job, webhook-subscription]
---

# profile-edit

Change something inside a Hermes profile. **Reads come from the files hermes owns; writes go
through its CLI.** Every per-agent write in the application is this movement.

## Input → Movement → Output

A `(host, container, profile)` triple and a change go in. The current state is read from the
profile's own files under `/opt/data/profiles/<name>`; the change is applied by invoking
`hermes -p <profile> <subcommand>` through a bounded `docker exec`. Out comes the file hermes
wrote, read back the same way.

## Why this shape

Not a style preference. Hermes **parses the schedule expression, mints the job id, computes the
next run, generates each webhook route's HMAC secret and owns its config schema** — so a write we
compose ourselves is a second implementation of its rules, and a wrong one the first time hermes
changes.

The read side is the mirror argument: `hermes cron list` prints a boxed table, which is
*presentation* and drifts on any release. `<profile>/cron/jobs.json` is a plain JSON document
carrying every field the API needs (`agents/HermesCron.java:19`).

Two seams, one each, and both are shared on purpose:

- **`HermesContainerFiles`** owns the exec seam — every profile file read.
- **`HermesCli`** owns how a profile-scoped command is spelled, and the reading of what it
  answers (`agents/HermesCli.java:13`). `HermesCron` and `HermesWebhooks` had each grown their
  own copy of the same five helpers, three of them byte-for-byte identical. One of those helpers
  carries a rule worth having once: hermes writes ISO-8601 with an offset but **not always**, so
  a bare instant has to be tried second, and a value neither parser understands must not fail
  the whole listing.

## Steps

1. Resolve the host at the edge — a `{hostId}` path segment binds straight to a probed
   `DockerHostRef` through the converter in `web/WebConfig.java:54`, so the handler never
   asks. A host id sent in a request body still calls `requireConnected` itself.
2. Read: `HermesContainerFiles` → the path from `ProfilePaths` (`agents/ProfilePaths.java:28`).
3. Write, as user `mc.terminal.user` (default `hermes`) — a root exec writing into
   `/opt/data` leaves files the agent can no longer read. **Two writers, and which one is
   not a style choice:**
   - `ProfilePaths.hermesCli(profile, args…)` (`:90`) → `DockerExecService`, for anything
     hermes mints or parses for itself — schedules, job ids, HMAC secrets, config keys.
     Composing those ourselves would mean re-implementing hermes' own rules.
   - `HermesContainerFiles.writeFile` / `writeFileAtomically`, for whole documents whose
     shape the dashboard owns: `SOUL.md`, `MEMORY.md`, `config.yaml`, `.env`, a skill's
     files. There is no CLI for these — or, for skill uninstall, the CLI is interactive-only
     and cannot be driven through a non-tty exec.
4. Read back through step 2. The file hermes wrote is the response, not what we sent.
5. Per-profile fan-out where the page is per container: one read each, capped like the other
   pollers, and a profile that cannot be read loses only its own entries.

## If you change this

- **Hits:** all nine controllers in `agents/web/`; `HermesCli` and `HermesContainerFiles` if you
  touch either seam — which reaches cron, webhooks, skills, sessions, setup, config and SOUL at
  once; `pages/agent-*-panel.ts`.
- **Does not hit:** anything in SQLite. Profile state is entirely in the container's volume. The
  one exception is [MCP agent links](../objects/mcp/agent-mcp-link.md), which record *our* view
  of a write we made — not the write itself.

## Surfaces

| Surface | Role |
|---|---|
| `/api/agents/{hostId}/{containerId}/{name}/**` | entry |
| profile files under `/opt/data` | read |
| `hermes -p <profile> …` via `docker exec` | writes what hermes mints or parses |
| `HermesContainerFiles.writeFile` via `docker exec` | writes whole documents the dashboard owns |

## See

- Objects: [profile](../objects/agents/profile.md), [cron job](../objects/agents/cron-job.md),
  [webhook subscription](../objects/agents/webhook-subscription.md)
- Source: `.../agents/HermesCli.java`, `.../agents/ProfilePaths.java`
