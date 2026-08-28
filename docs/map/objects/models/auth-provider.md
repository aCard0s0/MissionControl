---
type: object
cluster: models
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/api/AuthProviderDto.java
---

# Auth provider

Which vendors **one container already holds credentials for**. Code name `AuthProviderDto`.
Served at **`/api/agents/{hostId}/{containerId}/auth-providers`**.

Fourth of the four "provider"-shaped nouns, and the only one that is per-container.

## Why this shape

Container-scoped rather than profile-scoped, and the controller shows why: it answers from the
`"default"` profile's setup (`agents/web/AgentSetupController.java:39`). Credentials live in the
container's data volume, not in one profile — so asking any profile answers for all of them, and
the endpoint takes no profile name.

Read-only. Mission Control reports what is there; hermes owns the credential store.

## Shape

`AuthProviderDto` — `agents/api/AuthProviderDto.java`. Derived from `HermesSetup`, which reads
the container. See also `ApiKeyStatusDto` and `ApiKeyProviderDto` in the same package.

## Connected to

- **owns:** nothing — this is a read of hermes' state
- **owned-by:** the container's `/opt/data` volume, via hermes
- **joins:** [Provider](provider-registry.md) by provider key — that list says a vendor *needs*
  a key, this one says whether this container *has* one
- **looks-like-but-is-not:** [Inference endpoint](inference-endpoint.md) status. An endpoint
  being reachable is not a credential.

## If you change this

- **Hits:** the agent Setup panel (`pages/agent-setup-panel.ts`); `HermesSetup`;
  `core/store/agent-setup-store.ts`.
- **Does not hit:** the [Provider](provider-registry.md) list, which is compiled in and
  container-independent. Does not hit any other container — this is per-container by
  construction.

## Surfaces

| Surface | Role |
|---|---|
| `/api/agents/{hostId}/{containerId}/auth-providers` | reads |
| hermes, inside the container | owns the truth |
| FE `pages/agent-setup-panel.ts` | reads |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesSetup.java`
- Controller: `agents/web/AgentSetupController.java:36`
