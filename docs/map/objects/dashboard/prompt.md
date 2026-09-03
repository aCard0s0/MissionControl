---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/prompts/
---

# Prompt

Dashboard-owned text an operator keeps for later, with a category, notes and tags. `Prompt`,
`prompts` table, served at **`/api/prompts`**.

**Nothing inside a Hermes container reads it** — the schema says so where it is defined
(`schema.sql:159`). It is a library for the human, not a config surface.

## Why this shape

The interesting part is the second table. `prompt_meta` records that the sample prompt **has
already been seeded, so one an operator deleted does not come back on the next boot**
(`schema.sql:194`). Seeding on "is the table empty?" would resurrect it; seeding on a recorded
flag does not.

## Shape

- `prompts` — `schema.sql:159`; `tags` is a JSON array in TEXT; index on `category` (`:170`)
- `prompt_meta` — `schema.sql:194`, key/value
- Seeder: `prompts/PromptSeeder.java`

## Connected to

- **owns:** nothing
- **owned-by:** the dashboard
- **joins:** nothing. A prompt still reaches nothing and knows about nothing.
- **pointed-at-by:** any number of [prompt groups](prompt-group.md), which file the library.
  The link is the group's, in `prompt_groups.prompt_ids`; nothing on a prompt records it, and
  deleting a prompt just drops it out of whatever group named it.
- **looks-like-but-is-not:** the CLI reference at `/reference`, which is generated from
  `core/hermes-commands.ts` and is not stored at all.

## If you change this

- **Hits:** `PromptRepository`, `PromptController`, `PromptSeeder`, `pages/prompts.ts`,
  `core/store/prompt-store.ts`.
- **Does not hit:** any Agent, any profile, any container. Still the noun with no reach — but
  no longer the simplest: [prompt group](prompt-group.md) is four routes over a list of these
  ids and has no behaviour at all.

## Surfaces

| Surface | Role |
|---|---|
| `/api/prompts` | reads / writes |
| SQLite `prompts`, `prompt_meta` | stored |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/prompts/`
