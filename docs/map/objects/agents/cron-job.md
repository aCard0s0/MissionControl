---
type: object
cluster: agents
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesCron.java
---

# Cron job

A scheduled run belonging to one [profile](profile.md). Hermes' own feature; Mission Control
manages it and fires none of it. `CronJobDto` / `CronJobsDto`, served at
**`/api/agents/{hostId}/{containerId}/{name}/cron`**.

## Why this shape

**Reads go to the file, writes go through the CLI** — the class comment gives it directly
(`agents/HermesCron.java:19`). Hermes keeps the schedule in `<profile>/cron/jobs.json`, a plain
JSON document carrying every field this API needs, so listing reads that rather than parsing the
boxed table `hermes cron list` prints — presentation that would drift on any release. Writes go
to `hermes cron create/edit/pause/resume/run/remove` because hermes parses the schedule
expression, mints the job id and computes the next run.

**Only a `cron` schedule carries an expression.** `once` stores a timestamp and `interval` a
minute count, so the UI shows hermes' own display string — the one form every kind has.

The page also reports when the **gateway is down**: hermes stores jobs either way, but nothing
fires them.

## Shape

- File: `/opt/data/profiles/<name>/cron/jobs.json` — `agents/ProfilePaths.java:55`
- Kinds: `cron` (expression) | `once` (timestamp) | `interval` (minutes)
- Timestamp parsing is shared, and carries a rule worth having once: hermes writes ISO-8601
  with an offset but **not always**, so a bare instant is tried second, and a value neither
  parser understands must not fail the whole listing (`agents/HermesCli.java:13`)

## Connected to

- **owns:** nothing
- **owned-by:** a [profile](profile.md) — **per profile, while the page is per container**, so
  each listing fans out over the container's profiles, capped like the other pollers. A profile
  that cannot be read loses only its own entries.
- **joins:** the profile's gateway state (`HermesGatewayState`) — jobs exist but do not fire
  without it
- **looks-like-but-is-not:** anything Mission Control schedules. There is no scheduler here.

## If you change this

- **Hits:** `HermesCron`, `HermesCli` (shared helpers), `AgentCronController`,
  `pages/calendar.ts`, `core/store/job-store.ts` — which polls on a **30 s** period, the slowest
  useful one, because reading is one exec per profile (`core/store/live-sync.ts:24`).
- **Does not hit:** [Webhook subscription](webhook-subscription.md). Both are per-profile hermes
  features read the same way, and both go through `HermesCli`, but they share no state.

## Surfaces

| Surface | Role |
|---|---|
| `…/{name}/cron` | reads / writes |
| `<profile>/cron/jobs.json` | read directly |
| `hermes cron …` | the only writer |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesCron.java`
- The deeper reasoning: `docs/architecture.md`, "Scheduled jobs and inbound webhooks"
