---
type: object
cluster: models
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/models/ModelCatalogService.java
---

# Model catalog

The list of **model names** available for one provider. Code name `ModelCatalogDto`.
Served at **`/api/models`**. Persisted in the `model_catalog` table.

## Why this shape

A curated fallback plus an opportunistic refresh. Some providers will not list their models
without a key, so a background job cannot refresh everything
(`models/ModelCatalogService.java:40`). A refreshed list therefore wins over the curated one
and *says so* through `source`, which is how the page tells an operator which one they are
looking at (`models/ModelCatalogService.java:61`).

## Shape

`ModelCatalogDto(provider, models, source)` — `models/ModelCatalogDto.java:6`.

**`source` has three values**, and where each is emitted is the thing to know — the card used
to record that only two of them were named in the declarations, which is no longer true:

| Value | Emitted at | Means |
|---|---|---|
| `catalog` | `models/ModelCatalogService.java:69` | rows refreshed into `model_catalog` |
| `config` | `:71`, `:125` | the curated compiled-in list |
| `live` | `:121` | fetched from the provider just now, with a key |

All three are now named on both sides: the record comment lists them
(`models/ModelCatalogDto.java:9`) and the frontend union is `'catalog' \| 'config' \| 'live'`
(`core/api/api-types.ts:476`), with no trailing `\| string` left to let an unnamed value
type-check. [docs/api.md:148](../../../api.md) had it right throughout.

The frontend carries it through as `ModelCatalog` (`core/models.ts`) and shows it as a hint on
the model field — `ModelPicker.sourceLabel` (`shared/model-picker.ts:28`). Worth showing rather
than hiding: a shipped list and a list read from the provider are identical in a dropdown, and
picking a model the provider no longer serves fails much later, at the agent's first turn.

`live` is deliberately **not** labelled — the operator supplied the key that fetched it — and
neither is an empty list, nor an endpoint's own installed models.

A failed read answers **empty**, not from a shipped copy. The frontend used to keep its own
mirror of `mc.models` for that case; it only fired when the backend was unreachable, at which
point the create it fed could not be submitted either.

## Connected to

- **owns:** `model_catalog` rows
- **owned-by:** [Provider](provider-registry.md) — only a provider with `hasCatalog` has one
- **joins:** [Provider](provider-registry.md) by provider key
- **looks-like-but-is-not:** the model list an [Inference endpoint](inference-endpoint.md)
  reports. That comes from the endpoint's own `/api/tags` or `/v1/models`, not from here.

## If you change this

- **Hits:** the model picker in create-agent, profile edit and templates; `ModelCatalogRefresher`
  (the background job); the `model_catalog` table and therefore `schema.sql` + `SchemaUpgrades`.
- **Does not hit:** which providers exist ([Provider](provider-registry.md) is compiled in), and
  what a profile is actually *running* — that is read from the container, not from this catalog.

## Surfaces

| Surface | Role |
|---|---|
| `/api/models` | reads |
| `ModelCatalogRefresher` | writes |
| FE `core/api/providers-api.ts:20` | reads |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/models/`
