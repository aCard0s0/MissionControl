# Mission Control — edit map

The catalog for a tree you do not hold in your head. It answers *what is X* and *what else
moves if I change X*. It is not a second spec: [docs/architecture.md](../architecture.md)
owns the as-built "why", and the code owns the truth. Every card cites both.

Verified against `main @ 976a9c9` · 2026-08-28.

## Where things live

| Folder | What it holds |
|---|---|
| [objects/](objects/) | one card per noun, clustered by how an editor asks. `_index.md` lists every noun. |
| [processes/](processes/) | the five movements that actually run |
| [effects/](effects/) | change-impact index: "I am changing X, open these cards" |
| [_meta/schema.md](_meta/schema.md) | the closed set of card types and labels |
| [_templates/](_templates/) | blank starters — a new card is a copy |

## Universes

| Mark | Meaning |
|---|---|
| **live** | in force; implement and cite against it |
| **leftover** | still present, no longer the main path; touch only if that path is in scope |
| **ghost** | named but not wired; do not implement against it |

Everything in `applications/`, `deploy/`, `docs/`, `mc` and `Dockerfile` is **live**. No
leftover or ghost areas were found at the tree level; per-noun marks are on the cards.

## Name collisions — read this before touching anything model-shaped

Four different nouns spell themselves "provider" or "model". They are not variants of each
other, and three of them are one route rename apart from being confused in a diff.

| Route | Owner | What it actually is |
|---|---|---|
| `/api/providers` | `ModelProviderRegistry.PROVIDERS` (`agents/`) | a **static, compiled-in list of upstream LLM vendors** — key, label, env var, oauth, hasCatalog. Mirrors hermes' own `CANONICAL_PROVIDERS`. No database, no network. |
| `/api/models` | `ModelCatalogService` (`models/`) | **model names** available per vendor. `source: config \| live`. |
| `/api/inference-endpoints` | `InferenceEndpointService` (`inference/`) | **self-hosted inference endpoints** — Ollama or OpenAI-compatible, probed for protocol and status. Table `inference_endpoints`. Both shipped as `model-providers` / `model_providers`; `SchemaUpgrades` moves an older database across. |
| `/api/agents/{host}/{container}/auth-providers` | `AgentSetupController` | which vendors **this container has credentials for**. |

Other product-word / code-name disagreements:

| Product word | Code name |
|---|---|
| Agent (in the UI, one row per profile) | `ProfileSpec` / `HermesProfiles` — a *profile* inside a container, not a container |
| MCP Servers page | `McpServerRepository` catalog rows, of three kinds: `managed`, `external`, `stdio` |
| "group" on the MCP Servers page | the operator-made set in `mcp_groups` / `McpGroup` — **not** the roster's arrangement by kind and host, which is `mcpServerSections` (`pages/mcp-server-sections.ts`) and stored nowhere |
| "the MCP project" | Compose project **`mission-control-mcp`**. A pre-existing project named `mcp` is never touched. |
| ops board | `board_tasks` table, `BoardTask` |

## Route by what you are doing

| If | Go to |
|---|---|
| "what is X" | [objects/_index.md](objects/_index.md) |
| "what does changing X hit" | [effects/CONTEXT.md](effects/CONTEXT.md) |
| adding a movement, not a noun | [processes/](processes/) |
| the card contradicts the code | the code wins — fix the card, same day |

## The one rule

The card cites the source. It never copies it. If you find as-built behaviour written out in
a card, that is a defect: replace it with a `path:line` citation.
