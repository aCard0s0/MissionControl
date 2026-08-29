---
type: object
cluster: dashboard
universe: live
status: verified
verified: claude/skill-library @ ae0ebd6 · 2026-08-29
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/
---

# Skill library

Skills the dashboard holds, deployable onto any agent. `Skill`, `skills` table, served at
**`/api/skills`**.

Not the same noun as the [Skill](../_index.md) an agent has — see *looks-like-but-is-not*.

## Why this shape

**Hermes has no `skills create`.** `hermes skills install <id>` resolves an id against the
Skills Hub and does nothing else, so a skill authored in the dashboard, or written by an
agent's own curator, has no id anything can install it by. That single fact produces the
whole design: the library splits by origin rather than picking one mechanism.

| `kind` | Row holds | Deploy runs |
|---|---|---|
| `hub` | id, description, repo link. No content | `HermesSkills.install` → `hermes skills install <name> --force` |
| `local` | the file set | `HermesSkills.writeSkillFiles` → the files, written out |

Both halves are load-bearing. Storing a copy of a hub skill would be a second source of
truth that goes stale the moment the Hub moves. Refusing to store local content would make
dashboard-authored and curator-authored skills undeployable. The CHECK constraint at
`schema.sql:154` and the branch at `skills/SkillController.java:149` are the two places that stay
in step.

A local deploy is an **overlay, not a sync**: it writes what the row holds and removes
nothing, so a file renamed in the library leaves its old copy on the agent.

## Shape

- `skills` — `schema.sql:152`; `kind` CHECK at `:154`; `name` is `COLLATE NOCASE UNIQUE`
  (`:160`) so `pdf` and `PDF` cannot both address `skills/pdf`; `files` is a JSON array in
  TEXT, NULL for a hub row (`:167`); index on `category` (`:172`)
- `Skill` / `SkillFile` — `skills/Skill.java`, `skills/SkillFile.java`
- `SkillRepository` — plain JdbcTemplate, the `prompts` shape
- `SkillController` — `/api/skills`, six routes, no service layer

## The path guard

The relative path of each file is the only string in the application an operator types that
is then concatenated into a container path. `ProfilePaths.skillFile` (`agents/ProfilePaths.java:63`)
owns the rule: **every `/`-separated segment must pass `isValidName` on its own**, which
turns the existing profile-name whitelist into a per-segment one. `split("/", -1)` keeps
trailing empties, so `a/` and `a//b` are rejected rather than silently collapsing.

Depth is capped at three (`agents/ProfilePaths.java:49`) because `HermesSkills.listSkillFiles` runs
`find -maxdepth 3` — a file written deeper is invisible to the call that lists it back.

Every path is resolved **before the first write** (`agents/HermesSkills.java:157`). A path checked
as it is written would leave a half-deployed skill behind the rejection, and hermes would
still try to load it.

## Connected to

- **owns:** nothing on an agent. A deployed copy is not tracked.
- **owned-by:** the dashboard
- **joins:** a profile, only at the moment of a deploy or an import
- **looks-like-but-is-not:** the **Skill** an agent has (`agents/HermesSkills.java`,
  `SkillDto`), read through from that container's disk and listed on the agent's own Skills
  tab. Different lifetime, different owner: that one exists because a container has it, this
  one exists because an operator kept it. The FE spells the collision out too —
  `models.ts` `SkillRef` vs `Skill`, and `api.agents` vs `api.skills`.
- **looks-like-but-is-not:** a **Profile template** (`profile_templates.skills`), which
  holds skill *ids* to install when deploying a whole new agent. A blueprint creates an
  agent; this layers one skill onto an agent that exists.

## If you change this

- **Hits:** `SkillRepository`, `SkillController`, `HermesProfiles.installSkillFiles`
  (`agents/HermesProfiles.java:264`), `HermesSkills.writeSkillFiles`/`readSkillFiles`,
  `ProfilePaths.skillFile`, `pages/skills.ts`, `pages/skill-deploy-dialog.ts`,
  `core/store/skill-store.ts`, and the `save to library` button on
  `pages/agent-skills-panel.html`.
- **Does not hit:** a hub row never writes a file; a local row never shells
  `hermes skills install`. **Deleting a library row does not touch any deployed copy** —
  this is a stamp, not a live link, deliberately unlike an [MCP agent
  link](../mcp/agent-mcp-link.md), which exists because an MCP entry keeps drifting against
  a catalog revision. Files on a disk do not drift on their own, so there is no reverse
  link and no cascade. Removing a deployed skill is the agent's own Skills tab.

## Surfaces

| Surface | Role |
|---|---|
| `/api/skills` | reads / writes the library |
| `POST /api/skills/{id}/deploy` | the one write that reaches a container |
| `POST /api/skills/import` | the one read that reaches a container |
| SQLite `skills` | stored |
| `HermesContainerFiles` | the exec seam every deploy goes through |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/`
- The container write: `agents/HermesSkills.java:157`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The rule this widened: [mission_control_guidelines.md](../../../mission_control_guidelines.md)
