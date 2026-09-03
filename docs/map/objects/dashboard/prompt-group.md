---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/prompts/PromptGroupController.java
---

# Prompt group

How the [prompt](prompt.md) library is filed: a named set of prompts. `PromptGroup`,
`prompt_groups` table, served at **`/api/prompt-groups`**.

## Why this shape

**Four routes and no behaviour.** A prompt is text for a person to paste, so neither one nor a
set of them ever reaches a container — there is nothing here that could deploy, apply or
install. That makes this the smallest noun in the tree, and the smallness is the design: the
alternative was a fourth filing axis on the prompt row itself, which cannot be renamed,
described, or emptied.

### Not the same axis as `category` or `tags`

`prompts.category` is one word on one row and `prompts.tags` is a loose label set. Both filter;
neither is a record. A group is a row, so it can be renamed, described, and hold prompts that
disagree about their category. All three survive because they answer different questions.

### The twin of a skill group, deliberately not shared with it

[Skill group](skill-group.md) is the same shape over a different table. They are two records
and two controllers rather than one polymorphic `groups` table with a `kind` column: the ids
point at different tables, so every read would have to be told which, and this package is
already the duplicated shape `skills/SkillRepository` copied on purpose.

That is the **backend**. The frontend went the other way once there were enough of them: the
slices are one `LibraryStore` (`core/store/library-store.ts`, `694cab2`), the editors one
`GroupDraft` and the filing one `fileIntoSections` (`core/filing.ts`, `49d622d`), and the CSS
one `.group-head` / `.picker` in `styles.scss`. Each collapse waited for the count that paid
for it — three was declined at `8562693`, eight was not.

The difference worth knowing: a skill group can point at a guide. A prompt group cannot,
because there is no prose object over prompts to point at.

## A prompt can be in two groups, and shows in both

`prompt_ids` lives on the group, so nothing stops two groups claiming one prompt. The page
lists it under **both** (`pages/prompts.ts:90`) rather than picking a winner silently. The
cost is paid where the filing is done instead: the group editor ambers a prompt another group
already holds (`pages/prompts.ts:213`).

No constraint enforces one group per prompt. Adding one would mean an edit to group A silently
removing a prompt from group B.

## Shape

- `prompt_groups` — `schema.sql:181`; `name` is `COLLATE NOCASE UNIQUE` (`:185`); `prompt_ids`
  is JSON in TEXT (`:187`)
- `PromptGroup` — `prompts/PromptGroup.java`
- `PromptGroupRepository` — `prompts/PromptGroupRepository.java:43` reads **by name**, not
  newest-edit like the library it files: these are the headers the prompt list is filed under,
  so a group that jumped on a rename would move every prompt beneath it
- `PromptGroupController` — `/api/prompt-groups`, four routes, no service layer

## Connected to

- **owns:** nothing. Not even the prompts it names.
- **owned-by:** the dashboard
- **joins:** [prompt](prompt.md) rows, **by id and not by foreign key** — production runs with
  `PRAGMA foreign_keys` off (`support/SqliteTestDatabase.java` says why), so a CASCADE would
  be decoration. Ids are resolved on read and what is gone is dropped.
- **looks-like-but-is-not:** a [skill group](skill-group.md). See above — same shape, different
  table, and only one of the two can point at a guide.
- **looks-like-but-is-not:** `prompts.category` / `prompts.tags`. See *Not the same axis*.

## If you change this

- **Hits:** `PromptGroupRepository`, `PromptGroupController`,
  `core/store/prompt-group-store.ts`, `pages/prompts.ts` and its template — the list renders
  from `sections()`, not from `visible()`, so anything changing what a group claims changes the
  page's layout.
- **Also hits the Skills page** if you touch `.group-head` or `.picker`: those are in
  `styles.scss`, shared with [skill group](skill-group.md).
- **Does not hit:** any container, ever. Nor the prompts: deleting a group leaves every one it
  named in the library. Only the filing goes.

## Surfaces

| Surface | Role |
|---|---|
| `/api/prompt-groups` | reads / writes the groups. The whole surface |
| SQLite `prompt_groups` | stored |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/prompts/`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The twin over skills: [skill group](skill-group.md)
