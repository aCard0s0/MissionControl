---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ ae0ebd6 · 2026-08-29
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/SkillGuideController.java
---

# Guide

Prose that teaches how to use several library skills together, with the MCP servers they
need. `SkillGuide`, `skill_guides` table, served at **`/api/skill-guides`**.

Lives beside the [skill library](skill-library.md) — same package, same page, different noun.

## Why this shape

A guide deploys as three things at once: every skill it names onto the agent, every MCP
server linked to it, and **the prose itself written into the agent's skills directory as an
umbrella `SKILL.md`**.

That third part is what makes a guide more than a note. Hermes' own curator authors umbrella
skills exactly like this (`agents/HermesSkills.java:306`), so writing one is the native move
rather than an invention — and it is the difference between the operator having context and
the *agent* having it. A guide the agent cannot read would leave it choosing between the
parts with no idea they belong together.

The guide's `name` is therefore a directory name as well as a label (`schema.sql:240`), and
carries the same charset rule as a skill's.

## The deploy is not atomic, deliberately

Several independent writes to an agent someone else owns, and they fail one at a time: a
skill deleted from the library since the guide named it, an MCP alias already on that agent,
a managed server that is not running.

`skills/SkillGuideController.java:137` follows the rule
[profile-edit](../../processes/profile-edit.md) inherits from
`agents/templates/TemplateApplier.java:60` — *layering onto a profile the caller does not own
surfaces the error and does not roll back*. Undoing half a guide would mean removing skills
and MCP entries that may have been on that agent **before the guide ever ran**.

So it reports instead: one `DeployedPart` per part
(`agents/api/DeployedPart.java:23`), each `deployed | skipped | failed` with a reason.
`skipped` is "gone from the library or the catalog", or — for an MCP server — "already on the
agent" (`skills/SkillGuideController.java:179`); `failed` is "attempted and refused".

An already-connected server is still named in the umbrella document, because that document tells
the agent what it can reach and it can reach that one. This route used to call the case `failed`
while an [MCP group](../mcp/mcp-group.md) deploy called it `skipped` — both were reading the
message a conflict carried; `AgentMcpCatalogService.connectIfAbsent` now answers it for both.

A guide and a library skill of the same name both resolve to `skills/<name>/`, so a deploy
that wrote the umbrella there would replace that skill's own `SKILL.md` — silently, and on the
agent. The deploy refuses that one part and says which name to rename; there is no cross-table
constraint, because the two names only collide at the moment they are written.

Two orderings are load-bearing:

- the umbrella skill is written **last**, and names only what landed. Telling an agent to
  reach for a skill that then failed to deploy is worse than not mentioning it.
- the frontmatter is **generated, not authored** (`skills/GuideDocument.java:66`).
  `HermesSkills.parseSkillMeta` parses it, and an operator's description containing a colon
  would otherwise produce a skill hermes cannot read. snakeyaml does the quoting rather than
  this code guessing at it.

## Shape

- `skill_guides` — `schema.sql:236`; `name` is `COLLATE NOCASE UNIQUE` (`:240`) because two
  guides would otherwise write the same umbrella directory; both id lists are JSON in TEXT
  (`:244`); index on `category` (`:250`)
- `SkillGuide` — `skills/SkillGuide.java`
- `GuideDocument.render` — `skills/GuideDocument.java:30`, the umbrella SKILL.md
- `SkillDeployer` — `skills/SkillDeployer.java:33`, shared with the skill library

## Connected to

- **owns:** nothing on an agent. What a deploy left is not tracked.
- **owned-by:** the dashboard
- **joins:** [skill library](skill-library.md) rows and
  [MCP server entries](../mcp/mcp-server-entry.md), by id — **not** by foreign key. Production
  runs with `PRAGMA foreign_keys` off (`support/SqliteTestDatabase.java` says why), so a
  CASCADE would be decoration. A guide resolves its ids at deploy time and reports what is
  gone; the page marks a missing part before the operator clicks rather than after.
- **looks-like-but-is-not:** a [profile template](../agents/profile-template.md), which
  creates a whole new agent from a blueprint. A guide layers onto an agent that already
  exists, and carries prose the agent reads — a template carries none.
- **pointed-at-by:** any number of [skill groups](skill-group.md), optionally. The link is
  the group's, in `skill_groups.guide_id`; nothing on a guide records it, and deleting a
  guide leaves the group showing `guide missing ⚠` rather than silently unlinked.
- **looks-like-but-is-not:** a [prompt](prompt.md). A prompt is text for the human to paste;
  a guide's text is deployed and read by the agent itself.

## If you change this

- **Hits:** `SkillGuideRepository`, `SkillGuideController`, `GuideDocument`, `SkillDeployer`,
  `pages/skill-guides-panel.ts`, `pages/guide-deploy-dialog.ts`,
  `core/store/skill-guide-store.ts`.
- **Changing the umbrella document's shape hits hermes' parser**, not just this app: the
  frontmatter has to stay something `HermesSkills.parseSkillMeta` reads, which is what
  `GuideDocumentTest` pins by parsing it back.
- **Does not hit:** deleting a guide reaches no agent at all — not its skills, not its MCP
  links, not its umbrella skill. Same stamp-not-a-link rule as the skill library.

## Surfaces

| Surface | Role |
|---|---|
| `/api/skill-guides` | reads / writes the library |
| `POST /api/skill-guides/{id}/deploy` | the one call that reaches a container |
| SQLite `skill_guides` | stored |
| `AgentMcpCatalogService.connect` | how each MCP server is linked |
| `HermesProfiles.installSkillFiles` | how each skill, and the umbrella, is written |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The half-applied rule it inherits: [profile-edit](../../processes/profile-edit.md)
