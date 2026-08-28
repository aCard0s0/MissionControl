# models — the cluster that exists because the names collide

Four nouns, three Java packages, four routes, and every one of them says "provider" or "model".
They are not variants of each other. This cluster is grouped by *the confusion*, not by package.

| Card | Route | One line |
|---|---|---|
| [provider-registry](provider-registry.md) | `/api/providers` | compiled-in list of LLM vendors. No DB, no network. |
| [model-catalog](model-catalog.md) | `/api/models` | model *names* per vendor. `model_catalog` table. |
| [inference-endpoint](inference-endpoint.md) | `/api/inference-endpoints` | a self-hosted server you run. `inference_endpoints` table. |
| [auth-provider](auth-provider.md) | `.../auth-providers` | which vendors *this container* has keys for. |

Before you rename anything here, read all four. The provider registry has no table at all, so a
rename that looks obvious from one card is wrong from another. The one rename that has happened
went the other way: `model-providers` / `model_providers` → `inference-endpoints` /
`inference_endpoints`, because that pair genuinely named the wrong noun.

The frontend mirrors the confusion four more ways: `core/api/providers-api.ts`,
`core/store/provider-store.ts`, `core/store/provider-defaults.ts`, `shared/provider-resolve.ts`.
Check which noun each one means before editing it.
