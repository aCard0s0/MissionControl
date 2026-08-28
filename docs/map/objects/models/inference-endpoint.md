---
type: object
cluster: models
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/inference/InferenceEndpointService.java
---

# Inference endpoint

A **self-hosted model server** Mission Control can administer — ollama on this machine, a Mac
across the LAN, a rented box. Code name `InferenceEndpointDto` / `InferenceEndpointService`.
Served at **`/api/inference-endpoints`**. Stored in the **`inference_endpoints`** table.

Both shipped as `model-providers` / `model_providers`, which named a different noun entirely and
was the worst collision in the tree. `SchemaUpgrades.moveEndpointsOffModelProviders()` carries an
older database across and drops the old table — see [../../CLAUDE.md](../../CLAUDE.md) for the
three names still in play.

## Why this shape

**The protocol is probed, never stored** — the class comment gives the full reasoning at
`inference/InferenceEndpointService.java:23`. Which protocol answers at a URL is a property of
the server, not the row: storing it would need a CHECK-constraint table rebuild per protocol,
would go stale the moment a different server appeared behind the same URL, and would force an
`add` to refuse an endpoint that happened to be switched off. Adding a protocol is one
`EndpointClient` bean and nothing else.

The row is therefore three columns; everything else on the DTO is derived from a probe on a
short cache (`:49`, `:202`).

## Shape

Stored: `id, name, url, created_at` only — `inference/InferenceEndpointRepository.java:24`,
table at `schema.sql:29`.

Derived per probe — `inference/InferenceEndpointDto.java:4`:

| Field | Source |
|---|---|
| `kind` | `ollama \| openai \| null` — whichever client answered. Detection order is Ollama first, and that order matters (`inference/InferenceEndpointService.java:47`) |
| `status`, `version`, `detail` | the probe. `version` is null for openai — the protocol has no version endpoint |
| `canManageModels` | ollama only (`:239`). The UI hides pull/delete when false rather than offering a button that 400s |

## Connected to

- **owns:** `inference_endpoints` rows; running/pulled model state per endpoint
  (`EndpointModelDto`, `RunningModelDto`, `PullStatusDto`)
- **owned-by:** nothing — endpoints are operator-registered
- **joins:** a profile's `baseUrl` points at one of these (see [Profile](../agents/profile.md))
- **looks-like-but-is-not:** [Provider](provider-registry.md), the compiled-in vendor list at
  `/api/providers`. The service comment states the difference explicitly at `:32`: that one is
  *a capability description*, this one is *a URL you run*.

## If you change this

- **Hits:** the Models page (`pages/models.ts`) — which uses *only* this noun;
  `core/store/inference-endpoint-store.ts` and `core/api/inference-endpoints-api.ts`;
  `EndpointClient` implementations (`OllamaProtocolClient`, `OpenAiCompatClient`); the probe
  cache; `inference_endpoints` in `schema.sql`; the `/api/inference-endpoints` rows in
  `applications/api-contract.txt`.
- **Does not hit:** [Provider](provider-registry.md) or [Model catalog](model-catalog.md). A
  new inference endpoint adds no vendor and no catalog entry. It also does not hit any Agent
  until a profile's `baseUrl` is pointed at it — the endpoint does not know about profiles.

## Surfaces

| Surface | Role |
|---|---|
| `/api/inference-endpoints` | reads / writes |
| FE `pages/models.ts`, `core/store/inference-endpoint-store.ts` | reads / writes |
| the endpoint's own daemon | probed; and written for pull/delete when ollama |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/inference/`
