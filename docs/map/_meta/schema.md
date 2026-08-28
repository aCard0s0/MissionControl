# Schema — the rules of this map

Closed set. When practice and this file disagree, reconcile the same day.

## Card types

| `type:` | Lives at | Carries |
|---|---|---|
| object | `objects/<cluster>/<slug>.md` | one noun: shape, connections, waterfall, surfaces |
| process | `processes/<slug>.md` | one movement: input → movement → output, numbered steps |

Nothing else. `effects/CONTEXT.md` and `objects/_index.md` are indexes, not cards.

## Frontmatter

| Key | Values |
|---|---|
| `type` | `object` \| `process` |
| `cluster` | `docker` \| `agents` \| `models` \| `mcp` \| `dashboard` (objects only) |
| `universe` | `live` \| `leftover` \| `ghost` |
| `status` | `stub` \| `verified` \| `stale` |
| `verified` | `<branch> @ <short-sha> · <YYYY-MM-DD>` — required when `status: verified` |
| `entity` | the path that owns the fact |
| `consumes` / `produces` | links to object cards (processes only) |

## Naming

- Slugs are kebab-case and name the **noun**, not the class: `inference-endpoint.md`, not
  `InferenceEndpointDto.md`. The class name goes in the card body.
- Clusters group by **how an editor asks**, not by package or folder. `models/` holds cards
  from three different Java packages because that is where the confusion is.
- `_index.md` is hand-maintained (there is no generator). Every noun gets a line even with no
  card body — a stub line that names the owning file is worth more than a missing entry.

## The two rules that keep this small

1. **One home per fact.** If it is in `architecture.md`, the card links to it.
2. **Citations or it is a stub.** No `verified` without `path:line`.
