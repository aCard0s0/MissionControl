---
type: object
cluster: dashboard
universe: live
status: verified
verified: claude/skill-library @ 8d85b3d · 2026-09-01
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/SkillGroupController.java
---

# Skill group

How the [skill library](skill-library.md) is filed: a named set of skills and, optionally,
the [guide](guide.md) that explains them. `SkillGroup`, `skill_groups` table, served at
**`/api/skill-groups`**.

## Why this shape

**A group has no deploy.** That is the whole boundary between this and a guide, which also
names a set of skills. A guide names one in order to *push* it at an agent — and carries the
prose the agent then reads. A group names one so a person can read the library. Nothing here
reaches a container, and there is no route that could.

So the association points **group → guide**, not the other way about. A guide already owns
the set it deploys and the button that does it; a group that wants deploying links that guide
rather than growing a second, worse deploy of its own. `guideId` is null for a group that is
filing and nothing more (`schema.sql:271`), which is what makes the link the optional half.

### Not the same axis as `category`

A skill's `category` is one word on one skill, so a skill has exactly one and nothing owns
the set — you cannot rename a category, describe it, or point it at a guide. Both survive
because they answer different questions: `category` filters the list, a group files it. The
filter bar still filters by category *within* the filed view.

### The name is a label

Unlike a skill's name or a guide's, nothing writes a group to disk, so it carries no
directory charset rule (`schema.sql:268`) — only `COLLATE NOCASE UNIQUE`, because two headers
reading the same is the one way the filed list stops being readable.

## A skill can be in two groups, and shows in both

`skill_ids` lives on the group, so nothing stops two groups claiming one skill. The page lists
it under **both** (`pages/skills.ts:142`) rather than picking a winner silently. The cost is
paid where the filing is done instead: the group editor ambers a skill another group already
holds (`pages/skills.ts:151`), so double-filing is visible at the moment of it.

No constraint enforces one group per skill. Adding one would mean an edit to group A silently
removing a skill from group B, which is the same class of surprise the deploy rules in this
package refuse.

## Shape

- `skill_groups` — `schema.sql:263`; `name` is `COLLATE NOCASE UNIQUE` (`:268`); `skill_ids`
  is JSON in TEXT (`:270`); `guide_id` is nullable (`:271`)
- `SkillGroup` — `skills/SkillGroup.java`
- `SkillGroupRepository` — `skills/SkillGroupRepository.java:43` reads **by name**, not
  newest-edit like every other library here: these are the headers the skills list is filed
  under, so a group that jumped to the top on a rename would move every skill beneath it
- `SkillGroupController` — `/api/skill-groups`, four routes, no service layer, no deploy

## Connected to

- **owns:** nothing. Not the skills it names, not the guide it points at.
- **owned-by:** the dashboard
- **joins:** [skill library](skill-library.md) rows and one [guide](guide.md), **by id and
  not by foreign key** — the same rule a guide's own id lists follow, and for the same
  reason: production runs with `PRAGMA foreign_keys` off
  (`support/SqliteTestDatabase.java` says why), so a CASCADE would be decoration. Both are
  resolved on read; a deleted guide shows as `guide missing ⚠` on the header rather than as
  no link at all.
- **looks-like-but-is-not:** a [guide](guide.md). Same package, same page, and both name a
  set of skills — but a guide deploys and carries prose an agent reads, and a group does
  neither.
- **looks-like-but-is-not:** a skill's `category`. See *Not the same axis* above.
- **looks-like-but-is-not:** a [prompt group](prompt-group.md), the same shape over the
  prompt library. Two tables and two controllers on purpose; they share only the
  `.group-head` and `.picker` CSS in `styles.scss`. Only this one can point at a guide.

## If you change this

- **Hits:** `SkillGroupRepository`, `SkillGroupController`, `core/store/skill-group-store.ts`,
  `pages/skills.ts` and its template — the list is rendered from `sections()`, not from
  `visible()`, so anything that changes what a group claims changes the page's layout.
- **Does not hit:** any container, ever. Nor the skills themselves: deleting a group leaves
  every skill it named in the library and the guide it pointed at where it is. Only the
  filing goes. Same stamp-not-a-link rule as the rest of this package.

## Surfaces

| Surface | Role |
|---|---|
| `/api/skill-groups` | reads / writes the groups. The whole surface — there is no fifth route |
| SQLite `skill_groups` | stored |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The set that *does* deploy: [guide](guide.md)
