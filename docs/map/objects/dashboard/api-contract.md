---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/api-contract.txt
---

# API contract

`applications/api-contract.txt` — a **generated index** of every URL the frontend actually asks
for, one `METHOD /path` per line. The frontend writes it; the backend reads it back and asserts
every line resolves to a real handler. Never hand-edit it.

## Why this shape

The gap it closes is stated in `RouteContractTest`'s comment
(`contract/RouteContractTest.java:25`): other tests pin
JSON keys and sweep the routes the app *maps*, but **neither says whether the dashboard calls
those routes**. A controller can move from `@PutMapping` to `@PatchMapping`, or gain a path
segment, and every test on both sides stays green — the frontend's suite asserts only the URL its
own client composes, against a stubbed backend.

**Published rather than parsed.** Paths are assembled from template literals and helpers
(`agentPath(ref)`), so there is no declaration to read. Running the client is the only honest way
to learn what it asks for.

**It must be `RequestMappingHandlerMapping` specifically** (`:41`). `WebConfig` serves the SPA
from a resource handler that answers unknown paths with `index.html` — which is why a mistyped
endpoint is *invisible in production*: the client asks for JSON and gets a page. Asking the
annotation mapping directly makes an unmapped path fail instead of quietly succeeding.

## Shape

- Written by `core/api/api-routes.spec.ts:502` via `toMatchFileSnapshot`, deduped and sorted
- Read by `RouteContractTest` and `ApiDocCoverageTest`, which find it by walking up from the
  working directory — Maven runs from the module, a developer often from the repo root
  (`contract/RepoDocs.java:19`)
- **On CI a drifted file fails the snapshot instead of being rewritten**
  (`.github/workflows/ci.yml:43`), so a route renamed on one side and not the other cannot reach
  `main`. Locally, running the FE tests rewrites it.

## Connected to

- **owns:** nothing
- **owned-by:** the frontend's route suite
- **joins:** every controller in the backend; every client method in `core/api/`
- **joins:** [docs/api.md](../../../api.md) — `ApiDocCoverageTest` asserts every route in this
  file is written down there. It recovers the pattern Spring matched rather than parsing the
  url, because a published url carries concrete values where the doc writes `{placeholders}`.
  It is a floor, not a proof: the `…/{name}/…` shorthand is matched as a suffix, so two routes
  sharing a method and a tail satisfy each other — the test names the live instance.

## If you change this

- **Hits:** whichever side you did not change. Renaming a route means the FE client, the
  controller, and this file, in one commit.
- **Does not hit:** response shapes. Those are pinned separately by `ApiContractTest`; this file
  is method + path only.

## Surfaces

| Surface | Role |
|---|---|
| FE `core/api/api-routes.spec.ts` | writes |
| BE `contract/RouteContractTest` | reads and asserts |
| CI | fails on drift |

## See

- Source: `applications/api-contract.txt` (generated)
- Test seams: [../../../testing.md](../../../testing.md)
